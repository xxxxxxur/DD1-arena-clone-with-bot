package com.circus.server;

import com.circus.bot.Bot;
import com.circus.bot.BotRegistry;
import com.circus.engine.ArenaEngine;
import com.circus.engine.GameData;
import com.circus.engine.Models.*;
import com.circus.server.HttpWsServer.WsSession;
import com.circus.util.Json;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Multiplayer Room Manager: Local 2-Player PvP, Human vs Bot, and Bot vs Bot Spectate.
 * 100% protocol parity with server/rooms.py.
 */
public final class RoomManager {
    public static final RoomManager INSTANCE = new RoomManager();

    private final ConcurrentHashMap<String, Room> rooms = new ConcurrentHashMap<>();
    private final SplittableRandom idRng = new SplittableRandom();

    public static final class Room {
        public final String roomId;
        public final String mode; // "pvp" | "vs_bot" | "bot_vs_bot"
        public volatile String arenaTier;
        public final String bot0Type;
        public final String bot1Type;
        public final String bot0Preset;
        public final String bot1Preset;
        public volatile double botDelaySec = 1.65;

        public final TeamConfig[] teamConfigs = new TeamConfig[2];
        public final boolean[] ready = new boolean[2];
        public final String[] playerNames = new String[]{"Player 1", "Player 2"};

        public volatile ArenaEngine engine = null;
        public final Map<Integer, Bot> bots = new ConcurrentHashMap<>();
        public volatile String phase = "SETUP"; // SETUP | COMBAT | FINISHED
        public volatile boolean paused = false;

        // seat (0, 1, -1) -> connected WebSockets
        public final Map<Integer, Set<WsSession>> sockets = new ConcurrentHashMap<>();
        private final ReentrantLock lock = new ReentrantLock();
        private volatile Thread botLoopThread = null;
        private volatile int matchEpoch = 0;
        public volatile Map<String, Object> lastSearchStats = null;

        public Room(String roomId, String mode, String arenaTier,
                    String bot0Type, String bot1Type, String bot0Preset, String bot1Preset) {
            this.roomId = roomId;
            this.mode = mode;
            this.arenaTier = arenaTier;
            this.bot0Type = bot0Type;
            this.bot1Type = bot1Type;
            this.bot0Preset = bot0Preset;
            this.bot1Preset = bot1Preset;
            sockets.put(0, ConcurrentHashMap.newKeySet());
            sockets.put(1, ConcurrentHashMap.newKeySet());
            sockets.put(-1, ConcurrentHashMap.newKeySet());
        }

        public Map<String, Object> seatStatus() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("room_id", roomId);
            m.put("mode", mode);
            m.put("arena_tier", arenaTier);
            m.put("phase", phase);
            m.put("paused", paused);
            m.put("p0_connected", !sockets.get(0).isEmpty() || bots.containsKey(0));
            m.put("p1_connected", !sockets.get(1).isEmpty() || bots.containsKey(1));
            m.put("p0_ready", ready[0]);
            m.put("p1_ready", ready[1]);
            m.put("p0_name", playerNames[0]);
            m.put("p1_name", playerNames[1]);
            m.put("p0_team", teamConfigs[0] != null ? teamConfigs[0].toMap() : null);
            m.put("p1_team", teamConfigs[1] != null ? teamConfigs[1].toMap() : null);
            return m;
        }

        public void broadcastRoomState() {
            Map<String, Object> status = seatStatus();
            for (int seat : new int[]{0, 1, -1}) {
                Set<WsSession> set = sockets.get(seat);
                if (set == null || set.isEmpty()) continue;
                Map<String, Object> msg = new LinkedHashMap<>();
                msg.put("type", "ROOM_STATE");
                msg.put("seat", seat);
                msg.put("room", status);
                sendMany(set, msg);
            }
        }

        public void broadcastCombatUpdate(List<Map<String, Object>> events) {
            ArenaEngine eng = this.engine;
            if (eng == null) return;
            Map<String, Object> status = seatStatus();
            Map<String, Object> obs0 = null;
            Map<String, Object> obs1 = null;
            for (int seat : new int[]{0, 1, -1}) {
                Set<WsSession> set = sockets.get(seat);
                if (set == null || set.isEmpty()) continue;
                int viewer = (seat == 1) ? 1 : 0;
                Map<String, Object> obsMap;
                if (viewer == 0) {
                    if (obs0 == null) obs0 = eng.getObservation(0, true).toMap();
                    obsMap = obs0;
                } else {
                    if (obs1 == null) obs1 = eng.getObservation(1, true).toMap();
                    obsMap = obs1;
                }
                Map<String, Object> msg = new LinkedHashMap<>();
                msg.put("type", "COMBAT_UPDATE");
                msg.put("seat", seat);
                msg.put("viewer_team", viewer);
                msg.put("events", events);
                msg.put("observation", obsMap);
                msg.put("room", status);
                if (lastSearchStats != null) {
                    msg.put("search_stats", lastSearchStats);
                }
                sendMany(set, msg);
            }
        }

        private void sendMany(Set<WsSession> set, Map<String, Object> payload) {
            String json = Json.stringify(payload);
            List<WsSession> dead = null;
            for (WsSession ws : set) {
                try {
                    ws.sendText(json);
                } catch (Exception e) {
                    if (dead == null) dead = new ArrayList<>();
                    dead.add(ws);
                }
            }
            if (dead != null) set.removeAll(dead);
        }

        public void startCombatIfReady() {
            lock.lock();
            try {
                if (!"SETUP".equals(phase)) return;
                if (!ready[0] || !ready[1]) return;
                if (teamConfigs[0] == null || teamConfigs[1] == null) return;

                long seed = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
                this.engine = new ArenaEngine(teamConfigs[0], teamConfigs[1], seed, 40);
                this.phase = "COMBAT";
                this.matchEpoch++;
                List<Map<String, Object>> initEvs = new ArrayList<>();
                for (CombatEvent e : engine.consumeInitEvents()) initEvs.add(e.toMap());
                broadcastCombatUpdate(initEvs);
                scheduleBotTurnLocked();
            } finally {
                lock.unlock();
            }
        }

        private void scheduleBotTurnLocked() {
            ArenaEngine eng = this.engine;
            if (eng == null || eng.state.finished) return;
            int active = eng.state.activeTeam;
            if (!bots.containsKey(active)) return;
            Thread t = this.botLoopThread;
            if (t != null && t.isAlive()) return;
            int epoch = this.matchEpoch;
            this.botLoopThread = Thread.ofVirtual().name("bot-loop-" + roomId).start(() -> runBotLoop(epoch));
        }

        private void runBotLoop(int epoch) {
            try {
                ArenaEngine eng0 = this.engine;
                if (eng0 != null && eng0.state.turnCount == 0) {
                    Thread.sleep(2200L);
                }
                while (this.matchEpoch == epoch) {
                    ArenaEngine eng = this.engine;
                    if (eng == null || eng.state.finished) return;
                    while (paused) {
                        Thread.sleep(150L);
                        if (this.matchEpoch != epoch || this.engine == null || this.engine.state.finished) return;
                    }
                    int active = eng.state.activeTeam;
                    if (!bots.containsKey(active)) return;

                    long delayMs = (long) (Math.max(0.25, botDelaySec) * 1000.0);
                    Thread.sleep(delayMs);

                    lock.lock();
                    try {
                        if (this.matchEpoch != epoch || this.engine == null || this.engine.state.finished) return;
                        int curActive = this.engine.state.activeTeam;
                        Bot bot = bots.get(curActive);
                        if (bot == null) return;
                        Observation obs = this.engine.getObservation(curActive);
                        Action action = bot.chooseAction(obs, this.engine);
                        Map<String, Object> stats = bot.getLastSearchStats();
                        if (stats != null) {
                            Map<String, Object> enriched = new LinkedHashMap<>(stats);
                            enriched.put("team", curActive);
                            enriched.put("round", this.engine.state.roundNum);
                            enriched.put("turn", this.engine.state.turnCount + 1);
                            this.lastSearchStats = enriched;
                        } else {
                            this.lastSearchStats = null;
                        }
                        List<CombatEvent> evs = this.engine.step(action);
                        List<Map<String, Object>> evMaps = new ArrayList<>(evs.size());
                        for (CombatEvent e : evs) evMaps.add(e.toMap());
                        if (this.engine.state.finished) {
                            this.phase = "FINISHED";
                        }
                        broadcastCombatUpdate(evMaps);
                    } finally {
                        lock.unlock();
                    }
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                System.err.println("[Room " + roomId + "] Bot loop error: " + e.getMessage());
            }
        }

        public String handleAction(int seat, Map<String, Object> rawAction) {
            lock.lock();
            try {
                if (engine == null || engine.state.finished) return "Match is not active.";
                if (seat != 0 && seat != 1) return "Spectators cannot perform actions.";
                String kind = Json.str(rawAction, "kind", "");
                if (seat != engine.state.activeTeam && !"SURRENDER".equals(kind)) return "Not your turn.";

                if ("SURRENDER".equals(kind)) {
                    int winner = 1 - seat;
                    engine.state.finished = true;
                    engine.state.winner = winner;
                    engine.state.winReason = "surrender";
                    phase = "FINISHED";
                    List<Map<String, Object>> evs = List.of(
                            Map.of("type", "SURRENDER", "data", Map.of("team", seat, "winner", winner)),
                            Map.of("type", "MATCH_END", "data", Map.of("winner", winner, "reason", "surrender", "round", engine.state.roundNum))
                    );
                    broadcastCombatUpdate(evs);
                    return null;
                }

                List<Map<String, Object>> evMaps;
                try {
                    Action action = Action.fromMap(rawAction);
                    List<CombatEvent> evs = engine.step(action);
                    evMaps = new ArrayList<>(evs.size());
                    for (CombatEvent e : evs) evMaps.add(e.toMap());
                } catch (Exception exc) {
                    return exc.getMessage() != null ? exc.getMessage() : exc.toString();
                }

                if (engine.state.finished) {
                    phase = "FINISHED";
                }
                broadcastCombatUpdate(evMaps);
                scheduleBotTurnLocked();
                return null;
            } finally {
                lock.unlock();
            }
        }

        public void resetForRematch() {
            lock.lock();
            try {
                matchEpoch++;
                Thread t = botLoopThread;
                if (t != null && t.isAlive()) t.interrupt();
                engine = null;
                phase = "SETUP";
                paused = false;
                ready[0] = bots.containsKey(0);
                ready[1] = bots.containsKey(1);
                broadcastRoomState();
                if (ready[0] && ready[1]) {
                    startCombatIfReady();
                }
            } finally {
                lock.unlock();
            }
        }
    }

    private String genRoomId() {
        return String.format("CIRCUS-%04d", idRng.nextInt(10000));
    }

    public Room createRoom(String mode, String arenaTier,
                           String bot0Type, String bot1Type, String bot0Preset, String bot1Preset) {
        return createRoom(mode, arenaTier, bot0Type, bot1Type, bot0Preset, bot1Preset, null, null, null, null);
    }

    public Room createRoom(String mode, String arenaTier,
                           String bot0Type, String bot1Type, String bot0Preset, String bot1Preset,
                           String bot0Class, String bot1Class) {
        return createRoom(mode, arenaTier, bot0Type, bot1Type, bot0Preset, bot1Preset, bot0Class, bot1Class, null, null);
    }

    public Room createRoom(String mode, String arenaTier,
                           String bot0Type, String bot1Type, String bot0Preset, String bot1Preset,
                           String bot0Class, String bot1Class,
                           Double bot0Budget, Double bot1Budget) {
        String rid = genRoomId();
        while (rooms.containsKey(rid)) rid = genRoomId();

        List<PresetDef> presets = GameData.get().getPresets();
        Room room = new Room(rid, mode, arenaTier, bot0Type, bot1Type, bot0Preset, bot1Preset);
        room.teamConfigs[0] = presets.get(0).team.copy();
        room.teamConfigs[1] = presets.get(1 % presets.size()).team.copy();

        if ("vs_bot".equals(mode)) {
            Bot bot = BotRegistry.create(bot1Type, null, bot1Preset, null, bot1Budget, bot1Class);
            room.bots.put(1, bot);
            String label = bot.getName();
            if (bot1Class != null && !bot1Class.isBlank()) {
                label += " [" + shortBotClassName(bot1Class) + "]";
            }
            room.playerNames[1] = label + " (AI)";
            room.teamConfigs[1] = bot.chooseTeam(ThreadLocalRandom.current().nextInt(1, 9999));
            room.ready[1] = true;
        } else if ("bot_vs_bot".equals(mode)) {
            String n0 = "P1 " + capitalize(bot0Type)
                    + (bot0Class != null && !bot0Class.isBlank() ? "@" + shortBotClassName(bot0Class) : "Bot");
            String n1 = "P2 " + capitalize(bot1Type)
                    + (bot1Class != null && !bot1Class.isBlank() ? "@" + shortBotClassName(bot1Class) : "Bot");
            Bot b0 = BotRegistry.create(bot0Type, n0, bot0Preset, null, bot0Budget, bot0Class);
            Bot b1 = BotRegistry.create(bot1Type, n1, bot1Preset, null, bot1Budget, bot1Class);
            room.bots.put(0, b0);
            room.bots.put(1, b1);
            room.playerNames[0] = b0.getName();
            room.playerNames[1] = b1.getName();
            room.teamConfigs[0] = b0.chooseTeam(ThreadLocalRandom.current().nextInt(1, 9999));
            room.teamConfigs[1] = b1.chooseTeam(ThreadLocalRandom.current().nextInt(10000, 19999));
            room.ready[0] = true;
            room.ready[1] = true;
        }

        rooms.put(rid, room);
        return room;
    }

    private static String shortBotClassName(String cls) {
        String trimmed = cls.trim();
        int dot = trimmed.lastIndexOf('.');
        return dot >= 0 ? trimmed.substring(dot + 1) : trimmed;
    }

    public Room getRoom(String roomId) {
        if (roomId == null) return null;
        return rooms.get(roomId.toUpperCase(Locale.ROOT));
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return "";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
