package com.circus;

import com.circus.engine.Simulator;
import com.circus.server.ArenaServer;

import java.nio.file.Path;
import java.util.*;

/**
 * Main entry point for the Java Butcher's Circus Backend & Headless Tournament CLI.
 *
 * Server mode (default):
 *   java --enable-native-access=ALL-UNNAMED -cp java/out com.circus.Main [--host 127.0.0.1] [--port 8765] [--game-dir <path>] [--select-game-dir]
 *
 * Tournament mode:
 *   java --enable-native-access=ALL-UNNAMED -cp java/out com.circus.Main --tournament \
 *        --bots direct_kill,stun_kill,iron_wall --matches 20 --workers 8 --budget 0.03
 */
public final class Main {
    public static void main(String[] args) throws Exception {
        boolean tournament = false;
        String host = Config.DEFAULT_HOST;
        int port = Config.DEFAULT_PORT;
        String botsArg = "random,tactical,stress";
        int matches = 30;
        int workers = Math.max(1, Runtime.getRuntime().availableProcessors());
        long seed = 42L;
        int maxRounds = 40;
        int replays = 2;
        Double budget = null;
        boolean openReport = false;
        String gameDirArg = null;
        boolean selectGameDir = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--tournament" -> tournament = true;
                case "--host" -> { if (i + 1 < args.length) host = args[++i]; }
                case "--port" -> { if (i + 1 < args.length) port = Integer.parseInt(args[++i]); }
                case "--bots" -> { if (i + 1 < args.length) botsArg = args[++i]; }
                case "--matches" -> { if (i + 1 < args.length) matches = Integer.parseInt(args[++i]); }
                case "--workers" -> { if (i + 1 < args.length) workers = Integer.parseInt(args[++i]); }
                case "--seed" -> { if (i + 1 < args.length) seed = Long.parseLong(args[++i]); }
                case "--max-rounds" -> { if (i + 1 < args.length) maxRounds = Integer.parseInt(args[++i]); }
                case "--replays" -> { if (i + 1 < args.length) replays = Integer.parseInt(args[++i]); }
                case "--budget" -> { if (i + 1 < args.length) budget = Double.parseDouble(args[++i]); }
                case "--open" -> openReport = true;
                case "--game-dir" -> { if (i + 1 < args.length) gameDirArg = args[++i]; }
                case "--select-game-dir" -> selectGameDir = true;
                default -> {}
            }
        }

        if (gameDirArg != null && !gameDirArg.isBlank()) {
            Path p = Config.normalizeGameDir(Path.of(gameDirArg.trim()));
            Config.applyGameDirs(p, Config.defaultDlcDir(p));
            Config.saveLocalProperties(Config.DD_GAME_DIR, null);
        }

        if (tournament) {
            runTournamentCli(botsArg, matches, workers, seed, maxRounds, replays, budget, host, port, openReport);
            return;
        }

        boolean assetsReady = Config.ensureGameAssetsConfigured(true, selectGameDir);
        if (!assetsReady) {
            System.err.println("[Warning] Darkest Dungeon / Butcher's Circus DLC assets not found at: " + Config.DD_GAME_DIR);
            System.err.println("[Warning] Re-run with --select-game-dir or set DD_GAME_DIR / local.properties to configure.");
        }

        long t0 = System.nanoTime();
        ArenaServer server = new ArenaServer(host, port);
        int actualPort = server.start();
        double bootMs = (System.nanoTime() - t0) / 1_000_000.0;
        System.out.printf("The Butcher's Circus Java 26 Backend ready in %.1f ms -> http://%s:%d%n",
                bootMs, host, actualPort);
        System.out.println("Game Dir: " + Config.DD_GAME_DIR);
        System.out.println("DLC Dir : " + Config.DD_DLC_DIR);
        System.out.println("FMOD Lib: " + Config.FMOD_DLL_PATH + " (FFM native + FMOD_OPENMEMORY + LRU bank pool)");
        System.out.println("Press Ctrl+C to stop.");

        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "server-shutdown"));
        Thread.currentThread().join();
    }

    private static void runTournamentCli(
            String botsArg, int matches, int workers, long seed, int maxRounds,
            int replays, Double budget, String host, int port, boolean openReport
    ) {
        List<Simulator.BotSpec> specs = new ArrayList<>();
        for (String raw : botsArg.split(",")) {
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) continue;
            specs.add(Simulator.BotSpec.parse(trimmed));
        }
        if (specs.isEmpty()) {
            System.err.println("Please provide at least 1 bot/team via --bots");
            System.exit(1);
        }

        boolean mirror = specs.size() == 1;
        System.out.println("=== Butcher's Circus Java Headless Tournament ===");
        System.out.printf("Bots: %s%s%nMatches/pair: %d | Workers: %d | Seed: %d | Budget: %s%n",
                specs, mirror ? " (Mirror Match)" : "",
                matches, workers, seed, budget != null ? (budget + "s") : "default");

        Object progressLock = new Object();
        final int[] lastPrinted = {-1};
        Simulator.ProgressListener cliProgress = (completed, total, elapsedSec) -> {
            synchronized (progressLock) {
                if (completed != total && completed == lastPrinted[0]) return;
                lastPrinted[0] = completed;
                printCliProgressBar(completed, total, elapsedSec);
            }
        };

        Simulator.TournamentReport report = Simulator.runTournament(
                specs, matches, workers, seed, maxRounds, replays, Config.REPLAY_DIR, Config.TOURNAMENT_DIR, budget, cliProgress
        );
        System.out.println();

        System.out.printf("%nCompleted %d matches in %.2fs (%.1f matches/sec)%n",
                report.totalMatches(), report.elapsedSec(), report.matchesPerSec());
        System.out.printf("Avg Rounds: %.2f | Deathblow Kills: %d | Heart Attack Kills: %d%n%n",
                report.avgRounds(), report.deathblowKills(), report.heartAttackKills());
        System.out.printf("%-24s %7s %4s %7s %7s %6s %6s %8s%n",
                "Bot", "Power", "Tier", "BT-ELO", "ELO", "Wins", "Losses", "WinRate");
        System.out.println("-".repeat(78));
        for (Map.Entry<String, Map<String, Object>> e : report.winrates().entrySet()) {
            Map<String, Object> st = e.getValue();
            System.out.printf("%-24s %7.1f %4s %7.1f %7.1f %6d %6d %7.2f%%%n",
                    e.getKey(),
                    ((Number) st.getOrDefault("power_score", 50.0)).doubleValue(),
                    String.valueOf(st.getOrDefault("power_tier", "-")),
                    ((Number) st.getOrDefault("bt_elo", st.get("elo"))).doubleValue(),
                    ((Number) st.get("elo")).doubleValue(),
                    ((Number) st.get("wins")).intValue(),
                    ((Number) st.get("losses")).intValue(),
                    ((Number) st.get("winrate_pct")).doubleValue());
        }
        if (!report.sampleReplays().isEmpty()) {
            System.out.println("\nSaved sample replays:");
            for (String rp : report.sampleReplays()) {
                System.out.println("  " + rp);
            }
        }
        if (report.reportFilePath() != null && !report.reportFilePath().isEmpty()) {
            String url = "http://" + host + ":" + port + "/static/tournament_report.html?file=" + report.reportFilename();
            System.out.println("\nSaved complete tournament report JSON:");
            System.out.println("  " + report.reportFilePath());
            System.out.println("Visualization URL (when server is running):");
            System.out.println("  " + url);
            if (openReport) {
                try {
                    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
                    if (os.contains("win")) {
                        new ProcessBuilder("cmd", "/c", "start", "", url).start();
                    } else if (os.contains("mac") || os.contains("darwin")) {
                        new ProcessBuilder("open", url).start();
                    } else {
                        new ProcessBuilder("xdg-open", url).start();
                    }
                } catch (Exception ignored) {}
            }
        }
    }

    private static void printCliProgressBar(int completed, int total, double elapsedSec) {
        int safeTotal = Math.max(1, total);
        double ratio = Math.min(1.0, Math.max(0.0, (double) completed / safeTotal));
        int width = 30;
        int filled = (int) Math.round(ratio * width);
        StringBuilder bar = new StringBuilder(width);
        for (int i = 0; i < width; i++) {
            bar.append(i < filled ? '#' : '-');
        }
        String etaStr;
        if (completed <= 0) {
            etaStr = "--";
        } else if (completed >= total) {
            etaStr = "0.0s";
        } else {
            double eta = elapsedSec * (total - completed) / completed;
            etaStr = String.format("%.1fs", eta);
        }
        System.out.printf("\rProgress [%s] %d/%d (%5.1f%%) | Elapsed: %.1fs | ETA: %s   ",
                bar, completed, total, ratio * 100.0, elapsedSec, etaStr);
        System.out.flush();
    }
}
