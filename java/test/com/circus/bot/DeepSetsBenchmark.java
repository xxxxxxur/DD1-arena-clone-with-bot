package com.circus.bot;

import com.circus.engine.*;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.*;

/** End-to-end leaf cost across varied positions, including feature extraction. */
public final class DeepSetsBenchmark {
    private static volatile double sink;
    public static void main(String[] args) {
        var rng = new Random(621979);
        var player = DeepSetsGames.player(rng, 0, false, false, .10);
        var positions = new ArrayList<ArenaEngine>();
        for (int i = 0; i < DeepSetsGames.teams.size(); i++) {
            var a = DeepSetsGames.teams.get(i).team();
            var b = DeepSetsGames.teams.get(rng.nextInt(DeepSetsGames.teams.size())).team();
            var e = new ArenaEngine(a, b, rng.nextLong()).cloneForSearch();
            for (int t = 0; t < 80 && !e.state.finished; t++) {
                if (t % 8 == 0) positions.add(e.cloneForSearch());
                var legal = e.getActionObservation(e.state.activeTeam).legalActions;
                var act = legal.get(rng.nextInt(legal.size()));
                if (!act.kind.equals("SURRENDER")) e.step(act);
            }
        }
        var neural = new DeepSetsBot(player.context(), Path.of(args[0]));
        var bean = (com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        for (var bot : List.of(new CircusSearchBot(player.context()), new UnitNeuralSearchBot(player.context()), neural)) {
            for (int run = 0; run < 3; run++) {
                long start = System.nanoTime(), bytes = bean.getCurrentThreadAllocatedBytes();
                double total = 0;
                for (int loop = 0; loop < 100; loop++)
                    for (var e : positions) total += bot.evaluateState(e, 0);
                long elapsed = System.nanoTime() - start, used = bean.getCurrentThreadAllocatedBytes() - bytes;
                sink = total;
                if (run == 2) System.out.printf("%s leaf_us=%.3f bytes/leaf=%.2f positions=%d%n",
                        bot.getClass().getSimpleName(), elapsed / (100000. * positions.size()),
                        used / (100. * positions.size()), positions.size());
            }
        }
        for (var e : positions)
            if (neural.evaluateState(e, 0) != -neural.evaluateState(e, 1)) throw new AssertionError("Perspective mismatch");
    }
}
