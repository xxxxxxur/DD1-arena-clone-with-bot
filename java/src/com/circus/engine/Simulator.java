package com.circus.engine;

import com.circus.Config;
import com.circus.bot.Bot;
import com.circus.bot.BotRegistry;
import com.circus.bot.TeamConfigStore;
import com.circus.engine.Models.*;
import com.circus.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Headless Butcher's Circus match runner & GIL-free multi-threaded Bot tournament engine
 * with complete per-match combat analytics and persistent tournament report export.
 */
public final class Simulator {
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
            .withZone(ZoneId.systemDefault());

    private Simulator() {}

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(int completed, int total, double elapsedSec);
    }

    public record BotSpec(String type, String preset, String botClass) {
        public BotSpec(String type, String preset) {
            this(type, preset, null);
        }

        /**
         * Parse a spec string supporting:
         * - {@code teamId}
         * - {@code teamId@botClass}
         * - {@code botType:presetId}
         * - {@code teamId:presetId@botClass}
         */
        public static BotSpec parse(String raw) {
            String s = raw == null ? "" : raw.trim();
            String botCls = null;
            int at = s.indexOf('@');
            if (at >= 0) {
                String after = s.substring(at + 1).trim();
                botCls = after.isEmpty() ? null : after;
                s = s.substring(0, at).trim();
            }
            String preset = null;
            int colon = s.indexOf(':');
            if (colon >= 0) {
                String pr = s.substring(colon + 1).trim();
                preset = pr.isEmpty() ? null : pr;
                s = s.substring(0, colon).trim();
            }
            return new BotSpec(s, preset, botCls);
        }

        public String displayLabel() {
            StringBuilder sb = new StringBuilder(type);
            if (preset != null && !preset.isEmpty()) {
                sb.append('(').append(preset).append(')');
            }
            if (botClass != null && !botClass.isEmpty()) {
                String shortCls = botClass.contains(".")
                        ? botClass.substring(botClass.lastIndexOf('.') + 1)
                        : botClass;
                sb.append('@').append(shortCls);
            }
            return sb.toString();
        }
    }

    public record MatchResult(
            long seed,
            String bot0Name,
            String bot1Name,
            String team0Id,
            String team1Id,
            String team0Name,
            String team1Name,
            String team0BotClass,
            String team1BotClass,
            List<String> team0Heroes,
            List<String> team1Heroes,
            int winner,
            String winReason,
            int rounds,
            int turns,
            int deathblowKills,
            int heartAttackKills,
            int team0TotalDamage,
            int team1TotalDamage,
            int team0TotalStress,
            int team1TotalStress,
            Integer firstDeathRound,
            Integer firstDeathsDoorRound,
            Integer firstAfflictionRound,
            Integer firstHeartAttackRound,
            Integer team0FirstDeathRound,
            Integer team1FirstDeathRound,
            Integer team0FirstDeathsDoorRound,
            Integer team1FirstDeathsDoorRound,
            Integer team0FirstAfflictionRound,
            Integer team1FirstAfflictionRound,
            Integer team0FirstHeartAttackRound,
            Integer team1FirstHeartAttackRound,
            int team0Survivors,
            int team1Survivors,
            double elapsedMs,
            Map<String, Object> replay
    ) {
        public Map<String, Object> toMatchMap(int matchIndex) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("match_index", matchIndex);
            m.put("seed", seed);
            m.put("rounds", rounds);
            m.put("turns", turns);
            m.put("winner", winner);
            m.put("winner_name", winner == 0 ? bot0Name : bot1Name);
            m.put("loser_name", winner == 0 ? bot1Name : bot0Name);
            m.put("win_reason", winReason);
            m.put("team0_id", team0Id);
            m.put("team0_name", bot0Name);
            m.put("team0_lineup_name", team0Name);
            m.put("team0_bot_class", team0BotClass);
            m.put("team0_heroes", team0Heroes);
            m.put("team1_id", team1Id);
            m.put("team1_name", bot1Name);
            m.put("team1_lineup_name", team1Name);
            m.put("team1_bot_class", team1BotClass);
            m.put("team1_heroes", team1Heroes);
            m.put("team0_total_damage", team0TotalDamage);
            m.put("team1_total_damage", team1TotalDamage);
            m.put("team0_total_stress", team0TotalStress);
            m.put("team1_total_stress", team1TotalStress);
            m.put("first_death_round", firstDeathRound);
            m.put("first_deaths_door_round", firstDeathsDoorRound);
            m.put("first_affliction_round", firstAfflictionRound);
            m.put("first_heart_attack_round", firstHeartAttackRound);
            m.put("team0_first_death_round", team0FirstDeathRound);
            m.put("team1_first_death_round", team1FirstDeathRound);
            m.put("team0_first_deaths_door_round", team0FirstDeathsDoorRound);
            m.put("team1_first_deaths_door_round", team1FirstDeathsDoorRound);
            m.put("team0_first_affliction_round", team0FirstAfflictionRound);
            m.put("team1_first_affliction_round", team1FirstAfflictionRound);
            m.put("team0_first_heart_attack_round", team0FirstHeartAttackRound);
            m.put("team1_first_heart_attack_round", team1FirstHeartAttackRound);
            m.put("deathblow_kills", deathblowKills);
            m.put("heart_attack_kills", heartAttackKills);
            m.put("team0_survivors", team0Survivors);
            m.put("team1_survivors", team1Survivors);
            m.put("elapsed_ms", Math.rint(elapsedMs * 100.0) / 100.0);
            return m;
        }
    }

    public record TournamentReport(
            String tournamentId,
            String createdAt,
            int totalMatches,
            double elapsedSec,
            double matchesPerSec,
            Map<String, Double> eloRatings,
            Map<String, Map<String, Object>> winrates,
            Map<String, Map<String, Integer>> headToHead,
            double avgRounds,
            int deathblowKills,
            int heartAttackKills,
            List<String> sampleReplays,
            List<Map<String, Object>> matches,
            String reportFilePath
    ) {
        public String reportFilename() {
            return (reportFilePath != null && !reportFilePath.isEmpty())
                    ? Path.of(reportFilePath).getFileName().toString() : "";
        }

        public Map<String, Object> toFullReportMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tournament_id", tournamentId);
            m.put("created_at", createdAt);
            m.put("total_matches", totalMatches);
            m.put("elapsed_sec", elapsedSec);
            m.put("matches_per_sec", matchesPerSec);
            m.put("avg_rounds", avgRounds);
            m.put("deathblow_kills", deathblowKills);
            m.put("heart_attack_kills", heartAttackKills);
            m.put("winrates", winrates);
            m.put("head_to_head", headToHead);
            List<String> replayNames = new ArrayList<>(sampleReplays.size());
            for (String p : sampleReplays) replayNames.add(Path.of(p).getFileName().toString());
            m.put("sample_replays", replayNames);
            m.put("matches", matches);
            return m;
        }

        public Map<String, Object> toApiMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tournament_id", tournamentId);
            m.put("total_matches", totalMatches);
            m.put("elapsed_sec", elapsedSec);
            m.put("matches_per_sec", matchesPerSec);
            m.put("avg_rounds", avgRounds);
            m.put("deathblow_kills", deathblowKills);
            m.put("heart_attack_kills", heartAttackKills);
            m.put("winrates", winrates);
            List<String> names = new ArrayList<>(sampleReplays.size());
            for (String p : sampleReplays) names.add(Path.of(p).getFileName().toString());
            m.put("sample_replays", names);
            String rf = reportFilename();
            m.put("report_file", rf);
            m.put("report_url", rf.isEmpty() ? "" : ("/static/tournament_report.html?file=" + rf));
            return m;
        }
    }

    public static MatchResult runMatch(
            Bot bot0, Bot bot1,
            TeamConfig team0, TeamConfig team1,
            long seed, int maxRounds, boolean recordReplay
    ) {
        return runMatch(bot0, bot1, null, null, team0, team1, seed, maxRounds, recordReplay);
    }

    public static MatchResult runMatch(
            Bot bot0, Bot bot1,
            String team0Id, String team1Id,
            TeamConfig team0, TeamConfig team1,
            long seed, int maxRounds, boolean recordReplay
    ) {
        long tStart = System.nanoTime();
        TeamConfig cfg0 = (team0 != null) ? team0 : bot0.chooseTeam((int) seed);
        TeamConfig cfg1 = (team1 != null) ? team1 : bot1.chooseTeam((int) (seed + 10007));

        List<String> heroes0 = cfg0.heroes.stream().map(h -> h.heroId).toList();
        List<String> heroes1 = cfg1.heroes.stream().map(h -> h.heroId).toList();

        ArenaEngine engine = new ArenaEngine(cfg0, cfg1, seed, maxRounds);
        bot0.onMatchStart(0, seed);
        bot1.onMatchStart(1, seed);

        List<Map<String, Object>> replayFrames = recordReplay ? new ArrayList<>(64) : null;
        if (recordReplay) {
            List<Map<String, Object>> initEvs = new ArrayList<>();
            for (CombatEvent e : engine.consumeInitEvents()) initEvs.add(e.toMap());
            Map<String, Object> f0 = new LinkedHashMap<>();
            f0.put("step", 0);
            f0.put("action", null);
            f0.put("events", initEvs);
            f0.put("obs0", engine.getObservation(0).toMap());
            replayFrames.add(f0);
        }

        int stepIdx = 0;
        int dbKills = 0;
        int haKills = 0;
        int[] totalDmg = new int[2];
        int[] totalStress = new int[2];
        Integer firstDeath = null;
        Integer firstDd = null;
        Integer firstAff = null;
        Integer firstHa = null;
        Integer[] teamFirstDeath = new Integer[2];
        Integer[] teamFirstDd = new Integer[2];
        Integer[] teamFirstAff = new Integer[2];
        Integer[] teamFirstHa = new Integer[2];

        while (!engine.state.finished) {
            int active = engine.state.activeTeam;
            int roundBefore = engine.state.roundNum;
            Bot bot = (active == 0) ? bot0 : bot1;
            Observation obs = bot.requiresUnitObservations()
                    ? engine.getObservation(active) : engine.getActionObservation(active);
            Action action = bot.chooseAction(obs, engine);
            List<CombatEvent> events = engine.step(action);
            stepIdx++;

            for (CombatEvent ev : events) {
                Map<String, Object> d = ev.data;
                switch (ev.type) {
                    case "HIT" -> {
                        int dmg = toInt(d.get("dmg"));
                        if (dmg > 0) {
                            int atkTeam = toInt(d.get("actor_team"));
                            if (atkTeam == 0 || atkTeam == 1) totalDmg[atkTeam] += dmg;
                        }
                    }
                    case "DOT_TICK" -> {
                        int amt = toInt(d.get("amount"));
                        if (amt > 0) {
                            int vTeam = teamFromUid(d.get("unit_uid"));
                            if (vTeam == 0 || vTeam == 1) totalDmg[1 - vTeam] += amt;
                        }
                    }
                    case "STRESS" -> {
                        int amt = toInt(d.get("amount"));
                        String src = String.valueOf(d.getOrDefault("source", ""));
                        int vTeam = teamFromUid(d.get("unit_uid"));
                        if (amt > 0 && (vTeam == 0 || vTeam == 1)
                                && !"pass".equals(src)
                                && !"taste_of_grandeur".equals(src)) {
                            boolean inflictedByEnemy = (vTeam != active)
                                    || "horror".equals(src)
                                    || "ally_death".equals(src)
                                    || "ally_heart_attack".equals(src);
                            if (inflictedByEnemy) {
                                totalStress[1 - vTeam] += amt;
                            }
                        }
                    }
                    case "DEATHS_DOOR" -> {
                        if (firstDd == null) firstDd = roundBefore;
                        int vTeam = teamFromUid(d.get("unit_uid"));
                        if ((vTeam == 0 || vTeam == 1) && teamFirstDd[vTeam] == null) {
                            teamFirstDd[vTeam] = roundBefore;
                        }
                    }
                    case "RESOLVE_CHECK" -> {
                        boolean isVirtue = Boolean.TRUE.equals(d.get("is_virtue"));
                        if (!isVirtue) {
                            if (firstAff == null) firstAff = roundBefore;
                            int vTeam = teamFromUid(d.get("unit_uid"));
                            if ((vTeam == 0 || vTeam == 1) && teamFirstAff[vTeam] == null) {
                                teamFirstAff[vTeam] = roundBefore;
                            }
                        }
                    }
                    case "HEART_ATTACK" -> {
                        if (firstHa == null) firstHa = roundBefore;
                        int vTeam = teamFromUid(d.get("unit_uid"));
                        if ((vTeam == 0 || vTeam == 1) && teamFirstHa[vTeam] == null) {
                            teamFirstHa[vTeam] = roundBefore;
                        }
                    }
                    case "DEATHBLOW" -> {
                        if ("heart_attack".equals(d.get("cause"))) haKills++;
                        else dbKills++;
                        if (firstDeath == null) firstDeath = roundBefore;
                        int vTeam = toInt(d.get("team"));
                        if ((vTeam == 0 || vTeam == 1) && teamFirstDeath[vTeam] == null) {
                            teamFirstDeath[vTeam] = roundBefore;
                        }
                    }
                    default -> {}
                }
            }

            if (recordReplay) {
                List<Map<String, Object>> evList = new ArrayList<>(events.size());
                for (CombatEvent e : events) evList.add(e.toMap());
                Map<String, Object> fr = new LinkedHashMap<>();
                fr.put("step", stepIdx);
                fr.put("action", action.toMap());
                fr.put("events", evList);
                fr.put("obs0", engine.getObservation(0).toMap());
                replayFrames.add(fr);
            }
        }

        bot0.onMatchEnd(engine.state.winner, engine.state.winReason);
        bot1.onMatchEnd(engine.state.winner, engine.state.winReason);
        double elapsedMs = (System.nanoTime() - tStart) / 1_000_000.0;

        Map<String, Object> replayData = null;
        if (recordReplay) {
            replayData = new LinkedHashMap<>();
            replayData.put("seed", seed);
            replayData.put("bot0", bot0.getName());
            replayData.put("bot1", bot1.getName());
            replayData.put("team0", cfg0.toMap());
            replayData.put("team1", cfg1.toMap());
            replayData.put("winner", engine.state.winner);
            replayData.put("win_reason", engine.state.winReason);
            replayData.put("rounds", engine.state.roundNum);
            replayData.put("frames", replayFrames);
        }

        return new MatchResult(
                seed,
                bot0.getName(),
                bot1.getName(),
                team0Id != null ? team0Id : bot0.getName(),
                team1Id != null ? team1Id : bot1.getName(),
                cfg0.name,
                cfg1.name,
                bot0.getClass().getName(),
                bot1.getClass().getName(),
                heroes0,
                heroes1,
                engine.state.winner != null ? engine.state.winner : 0,
                engine.state.winReason,
                engine.state.roundNum,
                engine.state.turnCount,
                dbKills,
                haKills,
                totalDmg[0],
                totalDmg[1],
                totalStress[0],
                totalStress[1],
                firstDeath,
                firstDd,
                firstAff,
                firstHa,
                teamFirstDeath[0],
                teamFirstDeath[1],
                teamFirstDd[0],
                teamFirstDd[1],
                teamFirstAff[0],
                teamFirstAff[1],
                teamFirstHa[0],
                teamFirstHa[1],
                engine.countLivingHeroes(0),
                engine.countLivingHeroes(1),
                elapsedMs,
                replayData
        );
    }

    public static TournamentReport runTournament(
            List<BotSpec> botSpecs,
            int matchesPerPair,
            int workers,
            long baseSeed,
            int maxRounds,
            int saveReplays,
            Path replayDir,
            Double timeBudgetSec
    ) {
        return runTournament(botSpecs, matchesPerPair, workers, baseSeed, maxRounds,
                saveReplays, replayDir, Config.TOURNAMENT_DIR, timeBudgetSec, null);
    }

    public static TournamentReport runTournament(
            List<BotSpec> botSpecs,
            int matchesPerPair,
            int workers,
            long baseSeed,
            int maxRounds,
            int saveReplays,
            Path replayDir,
            Path tournamentDir,
            Double timeBudgetSec
    ) {
        return runTournament(botSpecs, matchesPerPair, workers, baseSeed, maxRounds,
                saveReplays, replayDir, tournamentDir, timeBudgetSec, null);
    }

    public static TournamentReport runTournament(
            List<BotSpec> botSpecs,
            int matchesPerPair,
            int workers,
            long baseSeed,
            int maxRounds,
            int saveReplays,
            Path replayDir,
            Path tournamentDir,
            Double timeBudgetSec,
            ProgressListener progressListener
    ) {
        // Reload team/*.json so any freshly edited team files take effect immediately
        TeamConfigStore.reload();

        // Single-team specification automatically runs a mirror tournament
        List<BotSpec> effectiveSpecs = (botSpecs.size() == 1)
                ? List.of(botSpecs.get(0), botSpecs.get(0))
                : botSpecs;

        long t0 = System.nanoTime();
        Instant now = Instant.now();
        String tourId = "tournament_" + FILE_TS.format(now) + "_" + baseSeed;

        record Job(BotSpec a, BotSpec b, String labelA, String labelB, long seed, boolean rec) {}
        Map<String, Integer> totalLabelFreq = new HashMap<>();
        for (BotSpec sp : effectiveSpecs) {
            totalLabelFreq.merge(sp.displayLabel(), 1, Integer::sum);
        }
        List<String> labels = new ArrayList<>(effectiveSpecs.size());
        Map<String, Integer> labelSeen = new HashMap<>();
        for (BotSpec sp : effectiveSpecs) {
            String base = sp.displayLabel();
            int idx = labelSeen.merge(base, 1, Integer::sum);
            labels.add(totalLabelFreq.get(base) > 1 ? (base + "#" + idx) : base);
        }

        List<Job> jobs = new ArrayList<>();
        long seedCursor = baseSeed;
        int replayBudget = saveReplays;

        for (int i = 0; i < effectiveSpecs.size(); i++) {
            for (int j = i + 1; j < effectiveSpecs.size(); j++) {
                BotSpec s0 = effectiveSpecs.get(i);
                BotSpec s1 = effectiveSpecs.get(j);
                String l0 = labels.get(i);
                String l1 = labels.get(j);
                for (int m = 0; m < matchesPerPair; m++) {
                    BotSpec a = (m % 2 == 0) ? s0 : s1;
                    BotSpec b = (m % 2 == 0) ? s1 : s0;
                    String la = (m % 2 == 0) ? l0 : l1;
                    String lb = (m % 2 == 0) ? l1 : l0;
                    boolean rec = replayBudget > 0;
                    if (rec) replayBudget--;
                    jobs.add(new Job(a, b, la, lb, seedCursor++, rec));
                }
            }
        }

        int totalJobs = jobs.size();
        AtomicInteger completedCount = new AtomicInteger(0);
        if (progressListener != null) {
            progressListener.onProgress(0, totalJobs, 0.0);
        }

        List<MatchResult> results = new ArrayList<>(totalJobs);
        int effWorkers = Math.max(1, Math.min(workers, Math.max(1, totalJobs)));
        if (effWorkers > 1 && totalJobs > 1) {
            ExecutorService pool = Executors.newFixedThreadPool(effWorkers);
            try {
                List<Future<MatchResult>> futures = new ArrayList<>(totalJobs);
                for (Job job : jobs) {
                    futures.add(pool.submit(() -> {
                        MatchResult mr = runJob(job.a, job.b, job.labelA, job.labelB, job.seed, maxRounds, job.rec, timeBudgetSec);
                        int done = completedCount.incrementAndGet();
                        if (progressListener != null) {
                            progressListener.onProgress(done, totalJobs, (System.nanoTime() - t0) / 1_000_000_000.0);
                        }
                        return mr;
                    }));
                }
                for (Future<MatchResult> f : futures) {
                    results.add(f.get());
                }
            } catch (Exception e) {
                throw new RuntimeException("Tournament execution failed", e);
            } finally {
                pool.shutdownNow();
            }
        } else {
            for (Job job : jobs) {
                MatchResult mr = runJob(job.a, job.b, job.labelA, job.labelB, job.seed, maxRounds, job.rec, timeBudgetSec);
                results.add(mr);
                int done = completedCount.incrementAndGet();
                if (progressListener != null) {
                    progressListener.onProgress(done, totalJobs, (System.nanoTime() - t0) / 1_000_000_000.0);
                }
            }
        }

        double elapsed = Math.max(1e-6, (System.nanoTime() - t0) / 1_000_000_000.0);

        final class TeamAgg {
            int wins, losses, games;
            long dmgDealt, dmgTaken, stressDealt, stressTaken, roundsSum, survivorsSum, oppSurvivorsSum;
            long firstDeathSum, firstDeathCnt;
            long firstDdSum, firstDdCnt;
            long firstAffSum, firstAffCnt;
            long firstHaSum, firstHaCnt;
            String botClass = "";
            List<String> heroes = Collections.emptyList();
        }

        Map<String, Double> elo = new LinkedHashMap<>();
        Map<String, TeamAgg> aggMap = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> h2h = new LinkedHashMap<>();
        int totalRounds = 0, totalDb = 0, totalHa = 0;
        List<String> savedPaths = new ArrayList<>();
        List<Map<String, Object>> matchMaps = new ArrayList<>(results.size());

        for (int idx = 0; idx < results.size(); idx++) {
            MatchResult res = results.get(idx);
            matchMaps.add(res.toMatchMap(idx));

            for (String n : new String[]{res.bot0Name, res.bot1Name}) {
                elo.putIfAbsent(n, 1500.0);
                aggMap.putIfAbsent(n, new TeamAgg());
                h2h.putIfAbsent(n, new LinkedHashMap<>());
            }

            TeamAgg a0 = aggMap.get(res.bot0Name);
            TeamAgg a1 = aggMap.get(res.bot1Name);
            a0.botClass = res.team0BotClass;
            a0.heroes = res.team0Heroes;
            a1.botClass = res.team1BotClass;
            a1.heroes = res.team1Heroes;

            a0.dmgDealt += res.team0TotalDamage;
            a0.dmgTaken += res.team1TotalDamage;
            a0.stressDealt += res.team0TotalStress;
            a0.stressTaken += res.team1TotalStress;
            a0.roundsSum += res.rounds;
            a0.survivorsSum += res.team0Survivors;
            a0.oppSurvivorsSum += res.team1Survivors;

            a1.dmgDealt += res.team1TotalDamage;
            a1.dmgTaken += res.team0TotalDamage;
            a1.stressDealt += res.team1TotalStress;
            a1.stressTaken += res.team0TotalStress;
            a1.roundsSum += res.rounds;
            a1.survivorsSum += res.team1Survivors;
            a1.oppSurvivorsSum += res.team0Survivors;

            // Milestone stats for when this team inflicted first Death / DD / Affliction / HA on opponent
            if (res.team1FirstDeathRound != null) { a0.firstDeathSum += res.team1FirstDeathRound; a0.firstDeathCnt++; }
            if (res.team0FirstDeathRound != null) { a1.firstDeathSum += res.team0FirstDeathRound; a1.firstDeathCnt++; }
            if (res.team1FirstDeathsDoorRound != null) { a0.firstDdSum += res.team1FirstDeathsDoorRound; a0.firstDdCnt++; }
            if (res.team0FirstDeathsDoorRound != null) { a1.firstDdSum += res.team0FirstDeathsDoorRound; a1.firstDdCnt++; }
            if (res.team1FirstAfflictionRound != null) { a0.firstAffSum += res.team1FirstAfflictionRound; a0.firstAffCnt++; }
            if (res.team0FirstAfflictionRound != null) { a1.firstAffSum += res.team0FirstAfflictionRound; a1.firstAffCnt++; }
            if (res.team1FirstHeartAttackRound != null) { a0.firstHaSum += res.team1FirstHeartAttackRound; a0.firstHaCnt++; }
            if (res.team0FirstHeartAttackRound != null) { a1.firstHaSum += res.team0FirstHeartAttackRound; a1.firstHaCnt++; }

            String wName = (res.winner == 0) ? res.bot0Name : res.bot1Name;
            String lName = (res.winner == 0) ? res.bot1Name : res.bot0Name;
            aggMap.get(wName).wins++;
            aggMap.get(wName).games++;
            aggMap.get(lName).losses++;
            aggMap.get(lName).games++;
            h2h.get(wName).merge(lName, 1, Integer::sum);

            double ea = 1.0 / (1.0 + Math.pow(10.0, (elo.get(lName) - elo.get(wName)) / 400.0));
            double delta = 24.0 * (1.0 - ea);
            elo.put(wName, elo.get(wName) + delta);
            elo.put(lName, elo.get(lName) - delta);

            totalRounds += res.rounds;
            totalDb += res.deathblowKills;
            totalHa += res.heartAttackKills;

            if (res.replay != null && replayDir != null) {
                try {
                    Files.createDirectories(replayDir);
                    String fname = "replay_" + res.seed + "_" + res.bot0Name + "_vs_" + res.bot1Name + ".json";
                    fname = fname.replaceAll("[^a-zA-Z0-9_.-]", "_");
                    Path outPath = replayDir.resolve(fname);
                    Files.writeString(outPath, Json.stringify(res.replay), StandardCharsets.UTF_8);
                    savedPaths.add(outPath.toString());
                } catch (IOException ignored) {}
            }
        }

        Map<String, Double> btElo = computeBradleyTerryElo(new ArrayList<>(elo.keySet()), h2h);
        Map<String, Double> powerScores = new LinkedHashMap<>();
        Map<String, String> powerTiers = new LinkedHashMap<>();
        for (String name : elo.keySet()) {
            TeamAgg st = aggMap.get(name);
            int g = Math.max(1, st.games);
            double bt = btElo.getOrDefault(name, 1500.0);
            double btWinProb = 1.0 / (1.0 + Math.pow(10.0, (1500.0 - bt) / 400.0));
            double netSurv = (double) (st.survivorsSum - st.oppSurvivorsSum) / g;
            double netSurvNorm = Math.max(0.0, Math.min(1.0, (netSurv + 4.0) / 8.0));
            double dmgRatio = (st.dmgDealt + st.dmgTaken) > 0
                    ? (double) st.dmgDealt / (st.dmgDealt + st.dmgTaken) : 0.5;
            double stressRatio = (st.stressDealt + st.stressTaken) > 0
                    ? (double) st.stressDealt / (st.stressDealt + st.stressTaken) : 0.5;
            double combatImpact = 0.65 * Math.max(dmgRatio, stressRatio) + 0.35 * 0.5 * (dmgRatio + stressRatio);
            double ps = round1(100.0 * (0.65 * btWinProb + 0.20 * netSurvNorm + 0.15 * combatImpact));
            powerScores.put(name, ps);
            powerTiers.put(name, powerTierOf(ps));
        }

        List<String> sortedNames = new ArrayList<>(elo.keySet());
        sortedNames.sort((a, b) -> {
            int cmp = Double.compare(powerScores.get(b), powerScores.get(a));
            if (cmp != 0) return cmp;
            cmp = Double.compare(btElo.getOrDefault(b, 1500.0), btElo.getOrDefault(a, 1500.0));
            if (cmp != 0) return cmp;
            return Double.compare(elo.get(b), elo.get(a));
        });

        Map<String, Double> sortedElo = new LinkedHashMap<>();
        Map<String, Map<String, Object>> winrates = new LinkedHashMap<>();
        for (String name : sortedNames) {
            double r = round1(elo.get(name));
            sortedElo.put(name, r);
            TeamAgg st = aggMap.get(name);
            int g = Math.max(1, st.games);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("wins", st.wins);
            m.put("losses", st.losses);
            m.put("games", st.games);
            m.put("winrate_pct", round2(100.0 * st.wins / g));
            m.put("power_score", powerScores.get(name));
            m.put("power_tier", powerTiers.get(name));
            m.put("bt_elo", round1(btElo.getOrDefault(name, 1500.0)));
            m.put("elo", r);
            m.put("bot_class", st.botClass);
            m.put("heroes", st.heroes);
            m.put("avg_damage_dealt", round1((double) st.dmgDealt / g));
            m.put("avg_damage_taken", round1((double) st.dmgTaken / g));
            m.put("avg_stress_dealt", round1((double) st.stressDealt / g));
            m.put("avg_stress_taken", round1((double) st.stressTaken / g));
            m.put("avg_rounds", round2((double) st.roundsSum / g));
            m.put("avg_survivors", round2((double) st.survivorsSum / g));
            m.put("net_survivors", round2((double) (st.survivorsSum - st.oppSurvivorsSum) / g));
            m.put("avg_inflict_first_death_round", st.firstDeathCnt > 0 ? round2((double) st.firstDeathSum / st.firstDeathCnt) : null);
            m.put("avg_inflict_first_dd_round", st.firstDdCnt > 0 ? round2((double) st.firstDdSum / st.firstDdCnt) : null);
            m.put("avg_inflict_first_affliction_round", st.firstAffCnt > 0 ? round2((double) st.firstAffSum / st.firstAffCnt) : null);
            m.put("avg_inflict_first_heart_attack_round", st.firstHaCnt > 0 ? round2((double) st.firstHaSum / st.firstHaCnt) : null);
            winrates.put(name, m);
        }

        String reportFilePath = "";
        TournamentReport tempReport = new TournamentReport(
                tourId,
                now.toString(),
                results.size(),
                round3(elapsed),
                round2(results.size() / elapsed),
                sortedElo,
                winrates,
                h2h,
                round2((double) totalRounds / Math.max(1, results.size())),
                totalDb,
                totalHa,
                savedPaths,
                matchMaps,
                ""
        );

        if (tournamentDir != null) {
            try {
                Files.createDirectories(tournamentDir);
                Path outFile = tournamentDir.resolve(tourId + ".json");
                Files.writeString(outFile, Json.stringify(tempReport.toFullReportMap()), StandardCharsets.UTF_8);
                reportFilePath = outFile.toString();
            } catch (IOException ignored) {}
        }

        return new TournamentReport(
                tourId,
                now.toString(),
                results.size(),
                round3(elapsed),
                round2(results.size() / elapsed),
                sortedElo,
                winrates,
                h2h,
                round2((double) totalRounds / Math.max(1, results.size())),
                totalDb,
                totalHa,
                savedPaths,
                matchMaps,
                reportFilePath
        );
    }

    private static MatchResult runJob(
            BotSpec a, BotSpec b, String n0, String n1,
            long seed, int maxRounds, boolean rec, Double timeBudgetSec
    ) {
        try (Bot b0 = BotRegistry.create(a.type, n0, a.preset, seed, timeBudgetSec, a.botClass);
             Bot b1 = BotRegistry.create(b.type, n1, b.preset, seed + 99991L, timeBudgetSec, b.botClass)) {
            return runMatch(b0, b1, a.type, b.type, null, null, seed, maxRounds, rec);
        }
    }

    private static int toInt(Object o) {
        return (o instanceof Number n) ? n.intValue() : 0;
    }

    private static int teamFromUid(Object uidObj) {
        if (uidObj instanceof String uid && uid.length() >= 2 && uid.charAt(0) == 't') {
            int t = uid.charAt(1) - '0';
            if (t == 0 || t == 1) return t;
        }
        return -1;
    }

    private static Map<String, Double> computeBradleyTerryElo(
            List<String> names, Map<String, Map<String, Integer>> h2h
    ) {
        int n = names.size();
        Map<String, Double> out = new LinkedHashMap<>();
        if (n == 0) return out;
        if (n == 1) {
            out.put(names.get(0), 1500.0);
            return out;
        }
        double[][] wins = new double[n][n];
        double[] totalWins = new double[n];
        for (int i = 0; i < n; i++) {
            Map<String, Integer> row = h2h.getOrDefault(names.get(i), Collections.emptyMap());
            for (int j = 0; j < n; j++) {
                if (i == j) continue;
                int w = row.getOrDefault(names.get(j), 0);
                wins[i][j] = w;
            }
        }
        // Symmetric Laplace prior (0.5 pseudo-draw per played pair) keeps ratings finite even on 100%/0% sweeps
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                double played = wins[i][j] + wins[j][i];
                if (played > 0) {
                    wins[i][j] += 0.5;
                    wins[j][i] += 0.5;
                }
            }
        }
        for (int i = 0; i < n; i++) {
            double s = 0.0;
            for (int j = 0; j < n; j++) s += wins[i][j];
            totalWins[i] = s;
        }
        double[] p = new double[n];
        Arrays.fill(p, 1.0);
        double[] next = new double[n];
        for (int iter = 0; iter < 60; iter++) {
            for (int i = 0; i < n; i++) {
                double denom = 0.0;
                for (int j = 0; j < n; j++) {
                    if (i == j) continue;
                    double nij = wins[i][j] + wins[j][i];
                    if (nij > 0) denom += nij / (p[i] + p[j]);
                }
                next[i] = (denom > 0 && totalWins[i] > 0) ? (totalWins[i] / denom) : 1.0;
            }
            // Normalize geometric mean to 1.0
            double logSum = 0.0;
            for (int i = 0; i < n; i++) logSum += Math.log(Math.max(1e-9, next[i]));
            double scale = Math.exp(-logSum / n);
            for (int i = 0; i < n; i++) p[i] = next[i] * scale;
        }
        double ln10 = Math.log(10.0);
        for (int i = 0; i < n; i++) {
            double rating = 1500.0 + 400.0 * (Math.log(Math.max(1e-9, p[i])) / ln10);
            out.put(names.get(i), rating);
        }
        return out;
    }

    private static String powerTierOf(double powerScore) {
        if (powerScore >= 78.0) return "S+";
        if (powerScore >= 66.0) return "S";
        if (powerScore >= 55.0) return "A";
        if (powerScore >= 45.0) return "B";
        if (powerScore >= 35.0) return "C";
        return "D";
    }

    private static double round1(double v) { return Math.rint(v * 10.0) / 10.0; }
    private static double round2(double v) { return Math.rint(v * 100.0) / 100.0; }
    private static double round3(double v) { return Math.rint(v * 1000.0) / 1000.0; }
}
