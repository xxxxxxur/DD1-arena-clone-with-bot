package com.circus;

import com.circus.bot.*;
import com.circus.engine.*;
import com.circus.engine.Models.*;
import com.circus.util.Json;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Paired, seat-balanced evaluation against an unchanged opponent pool. */
public final class DefenseEvaluation {
    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "current";
        int matches = args.length > 1 ? Integer.parseInt(args[1]) : 4;
        double budget = args.length > 2 ? Double.parseDouble(args[2]) : .005;
        long seed = args.length > 3 ? Long.parseLong(args[3]) : 1000;
        int workers = args.length > 4 ? Integer.parseInt(args[4]) : 4;
        int depth = args.length > 5 ? Integer.parseInt(args[5]) : 2;
        Path candidateDir = Path.of(args.length > 7 ? args[7] : "team");
        String[] opponents = (args.length > 6 ? args[6] : "direct_kill,stun_kill,shuffle_kill,mark_execution,bleed_blight_dot,dance_riposte,stress_circus,dodge_fortress").split(",");
        String[] subjects = {"iron_wall", "iron_hound"};
        Path baseline = Path.of("java/test/resources/defense-baseline");
        Map<String, Map<String, Object>> original = new HashMap<>();
        for (String id : opponents) original.put(id, Json.parseObject(Files.readString(Path.of("team", id + ".json"))));
        for (String id : subjects) original.put(id, Json.parseObject(Files.readString(baseline.resolve(id + ".json"))));
        Map<String, Map<String, Object>> candidate = new HashMap<>();
        for (String id : subjects) candidate.put(id, Json.parseObject(Files.readString(candidateDir.resolve(id + ".json"))));
        record Job(String subject, String opponent, long seed, boolean swap) {}
        record Result(Job job, Simulator.MatchResult match) {}
        List<Job> jobs = new ArrayList<>();
        for (String subject : subjects) for (int o = 0; o < opponents.length; o++)
            for (int m = 0; m < matches; m++) jobs.add(new Job(subject, opponents[o], seed + o * 10000L + m / 2, m % 2 != 0));
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        List<Future<Result>> futures = new ArrayList<>();
        long start = System.nanoTime();
        for (Job job : jobs) futures.add(pool.submit(() -> {
            Map<String, Object> subjectConfig = mode.equals("baseline") ? original.get(job.subject)
                    : candidate.get(job.subject);
            try (Bot subject = create(subjectConfig, job.seed, budget, depth,
                    mode.equals("baseline") || mode.equals("loadout-only"));
                 Bot opponent = create(original.get(job.opponent), job.seed + 991, budget, depth, true)) {
                var result = Simulator.runMatch(job.swap ? opponent : subject, job.swap ? subject : opponent,
                        null, null, job.seed, 40, false);
                return new Result(job, result);
            }
        }));
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Double> elo = new LinkedHashMap<>();
        Map<String, int[]> records = new LinkedHashMap<>();
        for (String s : subjects) { elo.put(s, 1500.0); records.put(s, new int[2]); }
        for (String o : opponents) elo.put(o, 1500.0);
        try {
            for (Future<Result> future : futures) {
                Result r = future.get();
                Job j = r.job;
                boolean win = r.match.winner() == (j.swap ? 1 : 0);
                int[] record = records.get(j.subject); record[win ? 0 : 1]++;
                double a = elo.get(j.subject), b = elo.get(j.opponent);
                double delta = 24 * ((win ? 1 : 0) - 1 / (1 + Math.pow(10, (b - a) / 400)));
                elo.put(j.subject, a + delta); elo.put(j.opponent, b - delta);
                var row = new LinkedHashMap<String, Object>();
                row.put("subject", j.subject); row.put("opponent", j.opponent);
                row.put("seed", j.seed); row.put("swapped", j.swap); row.put("win", win);
                row.put("rounds", r.match.rounds()); row.put("turns", r.match.turns());
                row.put("reason", r.match.winReason());
                row.put("damage", j.swap ? r.match.team1TotalDamage() : r.match.team0TotalDamage());
                row.put("stress", j.swap ? r.match.team1TotalStress() : r.match.team0TotalStress());
                rows.add(row);
                if (rows.size() % matches == 0) System.out.printf("%s %s vs %s: %d/%d cumulative | %.1fs%n",
                        mode, j.subject, j.opponent, record[0], record[0] + record[1], (System.nanoTime() - start) / 1e9);
            }
        } finally { pool.shutdownNow(); }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("mode", mode); report.put("budget", budget); report.put("depth", depth);
        report.put("base_seed", seed); report.put("elo", elo); report.put("matches", rows);
        report.put("workers", workers); report.put("matches_per_opponent", matches);
        report.put("subject_configs", mode.equals("baseline") ? Map.of("iron_wall", original.get("iron_wall"),
                "iron_hound", original.get("iron_hound")) : candidate);
        report.put("opponent_configs", original);
        String name = "defense-" + mode + "-" + seed + "-" + budget + "-d" + depth + ".json";
        Files.createDirectories(Path.of("benchmarks"));
        Files.writeString(Path.of("benchmarks", name), Json.stringify(report));
        for (var e : records.entrySet()) System.out.printf("FINAL %s %s: %dW %dL ELO %.1f%n", mode,
                e.getKey(), e.getValue()[0], e.getValue()[1], elo.get(e.getKey()));
    }

    @SuppressWarnings("unchecked")
    private static Bot create(Map<String, Object> config, long seed, double budget, int depth, boolean frozen) throws Exception {
        Map<String, Object> options = new LinkedHashMap<>((Map<String, Object>) config.get("bot_options"));
        if (depth > 0) options.put("max_depth", depth);
        var ctx = new BotContext((String) config.get("id"), (String) config.get("id"), null,
                TeamConfig.fromMap((Map<String, Object>) config.get("team")), options, seed, budget);
        // The general search policy is unchanged; only FortressBot specializes its evaluation.
        if (frozen) return new CircusSearchBot(ctx);
        return BotRegistry.instantiateBotClass((String) config.get("bot_class"), ctx);
    }
}
