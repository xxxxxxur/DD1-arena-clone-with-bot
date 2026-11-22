package com.circus;

import com.circus.bot.*;
import com.circus.engine.*;
import com.circus.engine.Models.*;
import java.lang.management.ManagementFactory;
import java.util.List;

/** Fixed-work benchmark: same JVM flags, seeds and depth before/after changes. */
public final class TournamentBenchmark {
    private static volatile long sink;
    private static final com.sun.management.ThreadMXBean ALLOC =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    private static void measure(String name, int count, Runnable task) {
        for (int i = 0; i < count / 2; i++) task.run();
        long bytes = ALLOC.getThreadAllocatedBytes(Thread.currentThread().threadId());
        long start = System.nanoTime();
        for (int i = 0; i < count; i++) task.run();
        double seconds = (System.nanoTime() - start) / 1e9;
        bytes = ALLOC.getThreadAllocatedBytes(Thread.currentThread().threadId()) - bytes;
        System.out.printf("%s: %.1f ops/s; %.0f bytes/op%n", name, count / seconds, (double) bytes / count);
    }

    public static void main(String[] args) {
        var presets = GameData.get().getPresets();
        ArenaEngine engine = new ArenaEngine(presets.get(0).team, presets.get(1).team, 42L);
        Action action = engine.getLegalActions().get(0);
        measure("clone+step", 1_000_000, () -> {
            ArenaEngine copy = engine.cloneForSearch();
            copy.step(action);
            sink += copy.state.turnCount;
        });
        CircusSearchBot search = new CircusSearchBot("bench", 0.46, "balanced", 42L, null, 2, 8, 4);
        Observation obs = engine.getObservation(engine.state.activeTeam);
        measure("search-depth-2", 10_000, () -> {
            sink += search.chooseAction(obs, engine).hashCode();
            if (search.getLastDepthReached() != 2) throw new AssertionError("Incomplete fixed-depth search");
        });
        System.out.printf("search: depth=%d nodes=%d ttHits=%d%n", search.getLastDepthReached(),
                search.getLastNodesVisited(), search.getLastTtHits());
        measure("random-match", 3000, () -> {
            var result = Simulator.runMatch(new RandomBot("a", 42L, null),
                    new RandomBot("b", 43L, null), presets.get(0).team, presets.get(1).team, 42L, 40, false);
            sink += result.turns();
        });
        var specs = List.of(new Simulator.BotSpec("random", "meta_direct_kill"),
                new Simulator.BotSpec("random", "meta_stun_kill"));
        for (int workers : new int[]{1, 4}) {
            Simulator.runTournament(specs, 200, workers, 42L, 40, 0, null, null, null);
            var report = Simulator.runTournament(specs, 2000, workers, 42L, 40, 0, null, null, null);
            System.out.printf("tournament-workers-%d: %.1f matches/s; %d matches; avgRounds=%.2f%n",
                    workers, report.matchesPerSec(), report.totalMatches(), report.avgRounds());
            for (var match : report.matches()) {
                sink += ((Number) match.get("turns")).longValue() * 3 + ((Number) match.get("winner")).longValue();
            }
        }
        System.out.println("checksum=" + sink);
    }
}
