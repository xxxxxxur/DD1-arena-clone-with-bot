package com.circus.server;

import com.circus.Config;
import com.circus.assets.AssetResolver;
import com.circus.assets.FmodAudioExtractor;
import com.circus.assets.LocalizationStore;
import com.circus.assets.SpineConverter;
import com.circus.bot.BotRegistry;
import com.circus.bot.TeamConfigStore;
import com.circus.engine.ArenaEngine;
import com.circus.engine.GameData;
import com.circus.engine.Models.*;
import com.circus.engine.Simulator;
import com.circus.server.HttpWsServer.HttpRequest;
import com.circus.server.HttpWsServer.HttpResponse;
import com.circus.server.HttpWsServer.WsSession;
import com.circus.server.RoomManager.Room;
import com.circus.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Full REST API, Asset/Spine/FMOD Bridge, Static Web UI & WebSocket PvP Server.
 * Drop-in 100% compatible replacement for Python Starlette server/app.py.
 */
public final class ArenaServer {
    private static final Map<String, String> CACHE_1DAY = Map.of("Cache-Control", "public, max-age=86400");

    private final HttpWsServer server;
    private volatile byte[] cachedBootstrapBytes;

    public ArenaServer(String host, int port) {
        this.server = new HttpWsServer(host, port, this::handleHttp, this::handleWs);
    }

    public int start() throws IOException {
        // Warm up GameData, Localization, and FMOD bank manifest so first browser load is instant
        GameData.get();
        LocalizationStore.get();
        FmodAudioExtractor.get();
        return server.start();
    }

    public void stop() {
        server.close();
    }

    private byte[] getBootstrapBytes() {
        byte[] cached = cachedBootstrapBytes;
        if (cached != null) return cached;

        synchronized (this) {
            if (cachedBootstrapBytes != null) return cachedBootstrapBytes;
            GameData gd = GameData.get();
            LocalizationStore loc = LocalizationStore.get();
            FmodAudioExtractor ae = FmodAudioExtractor.get();

            Map<String, Object> heroesMap = new LinkedHashMap<>();
            for (String hid : gd.heroOrder) {
                heroesMap.put(hid, serializeHeroDef(gd.heroes.get(hid)));
            }

            List<Map<String, Object>> trinketList = new ArrayList<>(gd.trinkets.size());
            for (TrinketDef t : gd.trinkets.values()) {
                Map<String, Object> tm = new LinkedHashMap<>();
                tm.put("id", t.id);
                tm.put("name_en", t.nameEn);
                tm.put("name_zh", t.nameZh);
                tm.put("hero_class", t.heroClass);
                tm.put("description_en", t.descriptionEn);
                tm.put("description_zh", t.descriptionZh);
                trinketList.add(tm);
            }

            Map<String, Object> traitsMap = new LinkedHashMap<>();
            for (TraitDef td : gd.traits.values()) {
                Map<String, Object> tm = new LinkedHashMap<>();
                tm.put("id", td.id);
                tm.put("name_en", td.nameEn);
                tm.put("name_zh", td.nameZh);
                tm.put("is_virtue", td.isVirtue);
                traitsMap.put(td.id, tm);
            }

            List<Map<String, Object>> presetsList = new ArrayList<>();
            for (PresetDef p : gd.getPresets()) presetsList.add(p.toMap());

            Map<String, Object> boot = new LinkedHashMap<>();
            boot.put("hero_order", gd.heroOrder);
            boot.put("heroes", heroesMap);
            boot.put("trinkets", trinketList);
            boot.put("traits", traitsMap);
            boot.put("presets", presetsList);
            boot.put("teams", TeamConfigStore.reload().values().stream().map(TeamConfigStore.TeamEntry::toMap).toList());
            boot.put("bots", BotRegistry.availableTypes());
            boot.put("bot_classes", BotRegistry.availableBotClasses());
            boot.put("cpu_cores", Math.max(1, Runtime.getRuntime().availableProcessors()));
            boot.put("arena_tiers", List.of("novice", "apprentice", "veteran", "champion", "darkest"));
            boot.put("audio_manifest", ae.buildSoundManifest());
            boot.put("loc_en", loc.exportUiBundle("en"));
            boot.put("loc_zh", loc.exportUiBundle("zh"));

            cachedBootstrapBytes = Json.stringify(boot).getBytes(StandardCharsets.UTF_8);
            return cachedBootstrapBytes;
        }
    }

    private static Map<String, Object> serializeHeroDef(HeroDef h) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", h.id);
        m.put("name_en", h.nameEn);
        m.put("name_zh", h.nameZh);
        m.put("max_hp", h.maxHp);
        m.put("dodge", h.dodge);
        m.put("prot", h.prot);
        m.put("dmg_min", h.dmgMin);
        m.put("dmg_max", h.dmgMax);
        m.put("crit", h.crit);
        m.put("move_forward", h.moveForward);
        m.put("move_backward", h.moveBackward);
        m.put("deathblow_res", h.deathblowRes);
        m.put("stun_res", h.stunRes);
        m.put("blight_res", h.blightRes);
        m.put("bleed_res", h.bleedRes);
        m.put("debuff_res", h.debuffRes);
        m.put("move_res", h.moveRes);
        m.put("stress_res", h.stressRes);
        m.put("default_skills", Arrays.asList(h.defaultSkills));
        List<Map<String, Object>> skills = new ArrayList<>(h.skills.length);
        for (SkillDef s : h.skills) {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("id", s.id);
            sm.put("name_en", s.nameEn);
            sm.put("name_zh", s.nameZh);
            sm.put("icon", s.icon);
            sm.put("anim", s.anim);
            sm.put("fx", s.fx);
            sm.put("targfx", s.targfx);
            sm.put("sfx_hint", s.sfxHint);
            sm.put("launch_ranks", s.launchRanks);
            sm.put("target_ranks", s.targetRanks);
            sm.put("target_team", s.targetTeam);
            sm.put("is_aoe", s.isAoe);
            sm.put("is_attack", s.isAttack);
            sm.put("acc", s.acc);
            sm.put("dmg_mod", s.dmgMod);
            sm.put("crit_mod", s.critMod);
            sm.put("stress_dmg", s.stressDmg);
            sm.put("stress_heal", s.stressHeal);
            sm.put("heal_min", s.healMin);
            sm.put("heal_max", s.healMax);
            sm.put("deathblow_bonus", s.deathblowBonus);
            sm.put("armor_piercing", s.armorPiercing);
            sm.put("breaks_guard", s.breaksGuard);
            sm.put("clears_corpses", s.clearsCorpses);
            sm.put("stun_chance", s.stunChance);
            sm.put("daze_chance", s.dazeChance);
            sm.put("max_uses", s.maxUses);
            sm.put("requires_hp_below_pct", s.requiresHpBelowPct);
            sm.put("abom_form", s.abomForm);
            sm.put("description_en", s.descriptionEn);
            sm.put("description_zh", s.descriptionZh);
            skills.add(sm);
        }
        m.put("skills", skills);
        return m;
    }

    private void handleHttp(HttpRequest req, HttpResponse res) throws Exception {
        String path = req.path;
        String range = req.header("range");

        // Index
        if ("/".equals(path)) {
            res.sendFile(Config.WEB_DIR.resolve("index.html"), "text/html; charset=utf-8", range, null);
            return;
        }

        // Static web UI files (/static/*)
        if (path.startsWith("/static/")) {
            String rel = path.substring("/static/".length());
            if (!rel.contains("..")) {
                Path rootNorm = Config.WEB_DIR.toAbsolutePath().normalize();
                Path p = rootNorm.resolve(rel).normalize();
                if (p.startsWith(rootNorm) && Files.isRegularFile(p)) {
                    res.sendFile(p, HttpWsServer.guessMimeType(p), range, null);
                    return;
                }
            }
            res.sendText(404, "Not found", null);
            return;
        }

        // Core API routes
        if ("/api/bootstrap".equals(path)) {
            res.sendBytes(200, "application/json; charset=utf-8", getBootstrapBytes(), null);
            return;
        }

        if ("/api/rooms".equals(path) && "POST".equals(req.method)) {
            Map<String, Object> body = req.bodyJson();
            Room room = RoomManager.INSTANCE.createRoom(
                    Json.str(body, "mode", "vs_bot"),
                    Json.str(body, "arena_tier", "darkest"),
                    Json.str(body, "bot0_type", "tactical"),
                    Json.str(body, "bot1_type", "tactical"),
                    emptyToNull(Json.str(body, "bot0_preset", null)),
                    emptyToNull(Json.str(body, "bot1_preset", null))
            );
            res.sendJson(200, Map.of("room_id", room.roomId, "room", room.seatStatus()));
            return;
        }

        if (path.startsWith("/api/rooms/")) {
            String rid = path.substring("/api/rooms/".length());
            Room room = RoomManager.INSTANCE.getRoom(rid);
            if (room == null) {
                res.sendJson(404, Map.of("error", "Room not found"));
            } else {
                res.sendJson(200, Map.of("room", room.seatStatus()));
            }
            return;
        }

        if ("/api/replays".equals(path)) {
            handleListReplays(res);
            return;
        }

        if (path.startsWith("/api/replays/")) {
            String fname = Path.of(path.substring("/api/replays/".length())).getFileName().toString();
            Path p = Config.REPLAY_DIR.resolve(fname);
            if (!Files.isRegularFile(p)) {
                res.sendJson(404, Map.of("error", "Replay not found"));
            } else {
                res.sendFile(p, "application/json; charset=utf-8", range, null);
            }
            return;
        }

        if ("/api/teams".equals(path)) {
            List<Map<String, Object>> teams = TeamConfigStore.reload().values().stream()
                    .map(TeamConfigStore.TeamEntry::toMap).toList();
            res.sendJson(200, Map.of(
                    "teams", teams,
                    "bots", BotRegistry.availableTypes(),
                    "bot_classes", BotRegistry.availableBotClasses()
            ));
            return;
        }

        if ("/api/tournaments".equals(path)) {
            handleListTournaments(res);
            return;
        }

        if (path.startsWith("/api/tournaments/")) {
            String fname = Path.of(path.substring("/api/tournaments/".length())).getFileName().toString();
            Path p = Config.TOURNAMENT_DIR.resolve(fname);
            if (!Files.isRegularFile(p)) {
                res.sendJson(404, Map.of("error", "Tournament report not found"));
            } else {
                res.sendFile(p, "application/json; charset=utf-8", range, null);
            }
            return;
        }

        if ("/api/tournament".equals(path) && "POST".equals(req.method)) {
            handleRunTournament(req, res);
            return;
        }

        // Asset Bridge Endpoints
        AssetResolver resolver = AssetResolver.get();

        if (path.startsWith("/api/spine/hero/")) {
            String[] seg = splitPath(path.substring("/api/spine/hero/".length()));
            if (seg.length == 2) {
                var info = resolver.resolveHeroAnim(seg[0], seg[1], "A");
                if (info == null) { res.sendJson(404, Map.of("error", "Hero animation not found")); return; }
                byte[] json = SpineConverter.convertSkelFileToJsonBytes(info.skel(), Config.SPINE_CACHE_DIR);
                res.sendBytes(200, "application/json; charset=utf-8", json, CACHE_1DAY);
                return;
            }
        }
        if (path.startsWith("/api/atlas/hero/")) {
            String[] seg = splitPath(path.substring("/api/atlas/hero/".length()));
            if (seg.length == 2) {
                var info = resolver.resolveHeroAnim(seg[0], seg[1], "A");
                if (info == null) { res.sendText(404, "Not found", null); return; }
                res.sendFile(info.atlas(), "text/plain; charset=utf-8", range, CACHE_1DAY);
                return;
            }
        }
        if (path.startsWith("/api/texture/hero/")) {
            String[] seg = splitPath(path.substring("/api/texture/hero/".length()));
            if (seg.length == 3) {
                var info = resolver.resolveHeroAnim(seg[0], seg[2], seg[1]);
                if (info == null) { res.sendText(404, "Not found", null); return; }
                res.sendFile(info.png(), "image/png", range, CACHE_1DAY);
                return;
            }
        }

        if (path.startsWith("/api/spine/herofx/")) {
            String[] seg = splitPath(path.substring("/api/spine/herofx/".length()));
            if (seg.length == 2) {
                var info = resolver.resolveHeroFx(seg[0], seg[1]);
                if (info == null) { res.sendJson(404, Map.of("error", "Hero FX not found")); return; }
                byte[] json = SpineConverter.convertSkelFileToJsonBytes(info.skel(), Config.SPINE_CACHE_DIR);
                res.sendBytes(200, "application/json; charset=utf-8", json, CACHE_1DAY);
                return;
            }
        }
        if (path.startsWith("/api/atlas/herofx/")) {
            String[] seg = splitPath(path.substring("/api/atlas/herofx/".length()));
            if (seg.length == 2) {
                var info = resolver.resolveHeroFx(seg[0], seg[1]);
                if (info == null) { res.sendText(404, "Not found", null); return; }
                res.sendFile(info.atlas(), "text/plain; charset=utf-8", range, CACHE_1DAY);
                return;
            }
        }
        if (path.startsWith("/api/texture/herofx/")) {
            String[] seg = splitPath(path.substring("/api/texture/herofx/".length()));
            if (seg.length == 2) {
                var info = resolver.resolveHeroFx(seg[0], seg[1]);
                if (info == null) { res.sendText(404, "Not found", null); return; }
                res.sendFile(info.png(), "image/png", range, CACHE_1DAY);
                return;
            }
        }

        if (path.startsWith("/api/spine/fx/")) {
            String fx = path.substring("/api/spine/fx/".length());
            var info = resolver.resolveGlobalFx(fx);
            if (info == null) { res.sendJson(404, Map.of("error", "Global FX not found")); return; }
            byte[] json = SpineConverter.convertSkelFileToJsonBytes(info.skel(), Config.SPINE_CACHE_DIR);
            res.sendBytes(200, "application/json; charset=utf-8", json, CACHE_1DAY);
            return;
        }
        if (path.startsWith("/api/atlas/fx/")) {
            String fx = path.substring("/api/atlas/fx/".length());
            var info = resolver.resolveGlobalFx(fx);
            if (info == null) { res.sendText(404, "Not found", null); return; }
            res.sendFile(info.atlas(), "text/plain; charset=utf-8", range, CACHE_1DAY);
            return;
        }
        if (path.startsWith("/api/texture/fx/")) {
            String fx = path.substring("/api/texture/fx/".length());
            var info = resolver.resolveGlobalFx(fx);
            if (info == null) { res.sendText(404, "Not found", null); return; }
            res.sendFile(info.png(), "image/png", range, CACHE_1DAY);
            return;
        }

        if (path.startsWith("/api/portrait/")) {
            String[] seg = splitPath(path.substring("/api/portrait/".length()));
            if (seg.length == 2) {
                Path p = resolver.resolveHeroPortrait(seg[0], seg[1]);
                res.sendFile(p, "image/png", range, CACHE_1DAY);
                return;
            }
        }
        if (path.startsWith("/api/skill_icon/")) {
            String[] seg = splitPath(path.substring("/api/skill_icon/".length()));
            if (seg.length == 2) {
                Path p = resolver.resolveSkillIcon(seg[0], seg[1]);
                res.sendFile(p, "image/png", range, CACHE_1DAY);
                return;
            }
        }
        if (path.startsWith("/api/trinket_icon/")) {
            String tid = path.substring("/api/trinket_icon/".length());
            Path p = resolver.resolveTrinketIcon(tid);
            res.sendFile(p, "image/png", range, CACHE_1DAY);
            return;
        }
        if (path.startsWith("/api/arena_bg/")) {
            String tier = path.substring("/api/arena_bg/".length());
            Path p = resolver.resolveArenaBackground(tier);
            res.sendFile(p, "image/png", range, CACHE_1DAY);
            return;
        }
        if (path.startsWith("/api/game_asset/")) {
            String rel = path.substring("/api/game_asset/".length());
            Path p = resolver.resolveStaticAsset(rel);
            if (p == null) { res.sendText(404, "Not found", null); return; }
            res.sendFile(p, HttpWsServer.guessMimeType(p), range, CACHE_1DAY);
            return;
        }

        // FMOD Audio endpoints
        if (path.startsWith("/api/audio/")) {
            String rest = path.substring("/api/audio/".length());
            int slash = rest.indexOf('/');
            if (slash > 0) {
                String bank = rest.substring(0, slash);
                String sample = rest.substring(slash + 1);
                Path wav = FmodAudioExtractor.get().extractWavPath(bank, sample);
                if (wav == null) {
                    res.sendText(404, "Audio sample not found", null);
                } else {
                    res.sendFile(wav, "audio/wav", range, CACHE_1DAY);
                }
                return;
            }
        }
        if (path.startsWith("/api/audio_mix/")) {
            String trackId = path.substring("/api/audio_mix/".length());
            Path wav = FmodAudioExtractor.get().extractMixedMusicPath(trackId);
            if (wav == null) {
                res.sendText(404, "Music mix not found", null);
            } else {
                res.sendFile(wav, "audio/wav", range, CACHE_1DAY);
            }
            return;
        }

        res.sendText(404, "Not found", null);
    }

    private void handleListReplays(HttpResponse res) throws IOException {
        Files.createDirectories(Config.REPLAY_DIR);
        List<Path> files;
        try (Stream<Path> s = Files.list(Config.REPLAY_DIR)) {
            files = s.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted((a, b) -> {
                        try {
                            return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a));
                        } catch (IOException e) {
                            return 0;
                        }
                    })
                    .limit(30)
                    .toList();
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (Path p : files) {
            try {
                Map<String, Object> meta = Json.parseObject(Files.readString(p, StandardCharsets.UTF_8));
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("filename", p.getFileName().toString());
                item.put("bot0", meta.get("bot0"));
                item.put("bot1", meta.get("bot1"));
                item.put("winner", meta.get("winner"));
                item.put("rounds", meta.get("rounds"));
                item.put("frames", Json.listVal(meta, "frames").size());
                items.add(item);
            } catch (Exception ignored) {}
        }
        res.sendJson(200, Map.of("replays", items));
    }

    private void handleListTournaments(HttpResponse res) throws IOException {
        Files.createDirectories(Config.TOURNAMENT_DIR);
        List<Path> files;
        try (Stream<Path> s = Files.list(Config.TOURNAMENT_DIR)) {
            files = s.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted((a, b) -> {
                        try {
                            return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a));
                        } catch (IOException e) {
                            return 0;
                        }
                    })
                    .limit(50)
                    .toList();
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (Path p : files) {
            try {
                Map<String, Object> meta = Json.parseObject(Files.readString(p, StandardCharsets.UTF_8));
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("filename", p.getFileName().toString());
                item.put("tournament_id", meta.get("tournament_id"));
                item.put("created_at", meta.get("created_at"));
                item.put("total_matches", meta.get("total_matches"));
                item.put("avg_rounds", meta.get("avg_rounds"));
                items.add(item);
            } catch (Exception ignored) {}
        }
        res.sendJson(200, Map.of("tournaments", items));
    }

    @SuppressWarnings("unchecked")
    private void handleRunTournament(HttpRequest req, HttpResponse res) throws IOException {
        Map<String, Object> body = req.bodyJson();
        List<Object> rawBots = Json.listVal(body, "bots");
        if (rawBots.isEmpty()) {
            rawBots = List.of("direct_kill", "stun_kill", "iron_wall", "iron_hound", "shuffle_kill");
        }
        int defaultWorkers = Math.max(1, Runtime.getRuntime().availableProcessors());
        int matchesRaw = body.containsKey("matches")
                ? Json.intVal(body, "matches", 30)
                : Json.intVal(body, "matches_per_pair", 30);
        int matchesPerPair = Math.min(1000, Math.max(1, matchesRaw));
        int workers = Math.min(Math.max(32, defaultWorkers * 2), Math.max(1, Json.intVal(body, "workers", defaultWorkers)));
        long seed = parseLongVal(body, "seed", 42L);
        int maxRounds = Math.min(200, Math.max(1, Json.intVal(body, "max_rounds", 40)));
        int replaysRaw = body.containsKey("replays")
                ? Json.intVal(body, "replays", 2)
                : Json.intVal(body, "save_replays", 2);
        int saveReplays = Math.min(100, Math.max(0, replaysRaw));
        Double timeBudget = parseOptionalBudget(body);

        List<Simulator.BotSpec> specs = new ArrayList<>();
        for (Object entry : rawBots) {
            if (entry instanceof String s) {
                if (!s.isBlank()) specs.add(Simulator.BotSpec.parse(s));
            } else if (entry instanceof Map<?, ?> m) {
                Map<String, Object> mp = (Map<String, Object>) m;
                String type = Json.str(mp, "type", Json.str(mp, "team", "tactical"));
                String preset = emptyToNull(Json.str(mp, "preset", null));
                String botCls = emptyToNull(Json.str(mp, "bot", Json.str(mp, "bot_class", null)));
                specs.add(new Simulator.BotSpec(type, preset, botCls));
            }
        }
        if (specs.size() < 2) {
            res.sendJson(400, Map.of("error", "Need at least 2 bots"));
            return;
        }

        Simulator.TournamentReport report = Simulator.runTournament(
                specs, matchesPerPair, workers, seed, maxRounds, saveReplays, Config.REPLAY_DIR, timeBudget
        );
        res.sendJson(200, report.toApiMap());
    }

    private static long parseLongVal(Map<String, Object> m, String key, long def) {
        Object v = m.get(key);
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s && !s.isBlank()) {
            try { return Long.parseLong(s.trim()); } catch (NumberFormatException ignored) {}
        }
        return def;
    }

    private static Double parseOptionalBudget(Map<String, Object> m) {
        Object v = m.containsKey("time_budget") ? m.get("time_budget") : m.get("budget");
        if (v == null) return null;
        double d;
        if (v instanceof Number n) {
            d = n.doubleValue();
        } else if (v instanceof String s && !s.isBlank()) {
            try { d = Double.parseDouble(s.trim()); } catch (NumberFormatException ignored) { return null; }
        } else {
            return null;
        }
        if (!Double.isFinite(d) || d <= 0) return null;
        return Math.min(10.0, Math.max(0.005, d));
    }

    // ------------------------------------------------------------------ WebSocket Room Handler
    private void handleWs(WsSession ws, HttpRequest req) throws Exception {
        if (!req.path.startsWith("/ws/room/")) {
            ws.close();
            return;
        }
        String rid = req.path.substring("/ws/room/".length());
        Room room = RoomManager.INSTANCE.getRoom(rid);
        if (room == null) {
            ws.sendJson(Map.of("type", "ERROR", "message", "Room " + rid + " not found."));
            ws.close();
            return;
        }

        String reqSeat = req.query.getOrDefault("seat", "auto");
        int seat;
        if ("bot_vs_bot".equals(room.mode)) {
            seat = -1;
        } else if ("0".equals(reqSeat)) {
            seat = 0;
        } else if ("1".equals(reqSeat)) {
            seat = 1;
        } else if ("spectator".equals(reqSeat)) {
            seat = -1;
        } else {
            if (room.sockets.get(0).isEmpty() && !room.bots.containsKey(0)) seat = 0;
            else if (room.sockets.get(1).isEmpty() && !room.bots.containsKey(1)) seat = 1;
            else seat = -1;
        }

        room.sockets.get(seat).add(ws);
        try {
            room.broadcastRoomState();
            if (("COMBAT".equals(room.phase) || "FINISHED".equals(room.phase)) && room.engine != null) {
                int viewer = (seat == 1) ? 1 : 0;
                Map<String, Object> msg = new LinkedHashMap<>();
                msg.put("type", "COMBAT_UPDATE");
                msg.put("seat", seat);
                msg.put("viewer_team", viewer);
                msg.put("events", Collections.emptyList());
                msg.put("observation", room.engine.getObservation(viewer).toMap());
                msg.put("room", room.seatStatus());
                ws.sendJson(msg);
            } else if ("bot_vs_bot".equals(room.mode) && "SETUP".equals(room.phase)) {
                room.startCombatIfReady();
            }

            String text;
            while ((text = ws.receiveText()) != null) {
                Map<String, Object> msg = Json.parseObject(text);
                String mtype = Json.str(msg, "type", "");

                if ("SET_TEAM".equals(mtype) && (seat == 0 || seat == 1)) {
                    if ("SETUP".equals(room.phase)) {
                        Map<String, Object> teamMap = Json.mapVal(msg, "team");
                        if (teamMap != null) {
                            TeamConfig cfg = TeamConfig.fromMap(teamMap);
                            String err = ArenaEngine.validateTeamConfig(cfg);
                            if (err != null) {
                                ws.sendJson(Map.of("type", "ERROR", "message", err));
                                continue;
                            }
                            room.teamConfigs[seat] = cfg;
                        }
                        String pname = Json.str(msg, "player_name", "");
                        if (!pname.isEmpty()) {
                            room.playerNames[seat] = pname.length() > 24 ? pname.substring(0, 24) : pname;
                        }
                        String tier = Json.str(msg, "arena_tier", "");
                        if (!tier.isEmpty()) room.arenaTier = tier;
                        room.broadcastRoomState();
                    }
                } else if ("SET_READY".equals(mtype) && (seat == 0 || seat == 1)) {
                    if ("SETUP".equals(room.phase)) {
                        Map<String, Object> teamMap = Json.mapVal(msg, "team");
                        if (teamMap != null) {
                            TeamConfig cfg = TeamConfig.fromMap(teamMap);
                            String err = ArenaEngine.validateTeamConfig(cfg);
                            if (err != null) {
                                ws.sendJson(Map.of("type", "ERROR", "message", err));
                                continue;
                            }
                            room.teamConfigs[seat] = cfg;
                        } else if (room.teamConfigs[seat] != null) {
                            String err = ArenaEngine.validateTeamConfig(room.teamConfigs[seat]);
                            if (err != null) {
                                ws.sendJson(Map.of("type", "ERROR", "message", err));
                                continue;
                            }
                        }
                        room.ready[seat] = Json.boolVal(msg, "ready", true);
                        room.broadcastRoomState();
                        room.startCombatIfReady();
                    }
                } else if ("ACTION".equals(mtype)) {
                    Map<String, Object> actMap = Json.mapVal(msg, "action");
                    if (actMap != null) {
                        String err = room.handleAction(seat, actMap);
                        if (err != null) {
                            ws.sendJson(Map.of("type", "ERROR", "message", err));
                        }
                    }
                } else if ("SET_SPEED".equals(mtype)) {
                    double spd = Json.dblVal(msg, "speed", 1.0);
                    room.botDelaySec = Math.max(0.25, 1.65 / Math.max(0.25, Math.min(6.0, spd)));
                } else if ("TOGGLE_PAUSE".equals(mtype)) {
                    room.paused = !room.paused;
                    room.broadcastRoomState();
                } else if ("REMATCH".equals(mtype)) {
                    room.resetForRematch();
                }
            }
        } finally {
            room.sockets.get(seat).remove(ws);
            room.broadcastRoomState();
        }
    }

    private static String[] splitPath(String s) {
        return s.split("/");
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }
}
