package com.circus.bot;

import com.circus.engine.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Paired promotion games. Pair members use identical lineups and seeds with the candidate on opposite sides. */
public final class DeepSetsTrial {
    record Result(double points, long[] nodes, long[] turns, long[] depths, long[] nanos) {}
    static Result play(Path challenger, String opponent, int id, double budget, boolean custom) {
        int pair = id / 2, candidateSide = id % 2;
        long seed = (custom ? 728001 : 342906) + pair + Long.getLong("circus.trial.seedOffset", 0L);
        var rng = new Random(seed);
        var player = DeepSetsGames.player(rng, pair % DeepSetsGames.teams.size(), custom, false, budget);
        var engine = new ArenaEngine(player.team(), player.team(), seed).cloneForSearch();
        var bots = new CircusSearchBot[2];
        bots[candidateSide] = new DeepSetsBot(player.context(), challenger);
        bots[1-candidateSide] = switch (opponent) {
            case "search" -> new CircusSearchBot(player.context());
            case "unit_neural_search" -> new UnitNeuralSearchBot(player.context());
            default -> new DeepSetsBot(player.context(), Path.of(opponent));
        };
        long[] nodes = new long[2], depths = new long[2], turns = new long[2], nanos = new long[2];
        try (var leftBot = bots[0]; var rightBot = bots[1]) {
            while (!engine.state.finished) {
                int side = engine.state.activeTeam;
                var bot = bots[side];
                var obs = engine.getActionObservation(side);
                long start = System.nanoTime();
                var action = bot.chooseAction(obs, engine);
                long elapsed = System.nanoTime() - start;
                if (Double.isFinite(bot.lastSearchScore()) && bot.lastSearchScore() > -1e10) {
                    int k = side == candidateSide ? 0 : 1;
                    nodes[k] += bot.getLastNodesVisited();
                    depths[k] += bot.getLastDepthReached();
                    turns[k]++;
                    nanos[k] += elapsed;
                }
                engine.step(action);
            }
        }
        double points = engine.state.winner == null ? .5 : engine.state.winner == candidateSide ? 1 : 0;
        return new Result(points, nodes, turns, depths, nanos);
    }

    public static void main(String[] args) throws Exception {
        Path challenger = Path.of(args[0]);
        int games = Integer.parseInt(args[2]), workers = Integer.parseInt(args[3]);
        double budget = Double.parseDouble(args[4]);
        boolean custom = args.length > 5 && args[5].equals("custom");
        long[] nodes = new long[2], depths = new long[2], turns = new long[2], nanos = new long[2];
        int wins = 0, draws = 0;
        try (var pool = Executors.newFixedThreadPool(workers)) {
            var results = new ArrayList<Future<Result>>();
            for (int i = 0; i < games; i++) {
                int id = i;
                results.add(pool.submit(() -> play(challenger, args[1], id, budget, custom)));
            }
            for (var task : results) {
                var r = task.get();
                if (r.points == 1) wins++;
                if (r.points == .5) draws++;
                for (int k = 0; k < 2; k++) {
                    turns[k] += r.turns[k];
                    depths[k] += r.depths[k];
                    nodes[k] += r.nodes[k];
                    nanos[k] += r.nanos[k];
                }
            }
        }
        System.out.printf("wins=%d losses=%d draws=%d%n", wins, games-wins-draws, draws);
        for (int k = 0; k < 2; k++)
            System.out.printf("%s depth=%.3f nodes=%.1f nodes/s=%.1f turns=%d%n", k == 0 ? "candidate" : "opponent",
                    depths[k]/(double)turns[k], nodes[k]/(double)turns[k], nodes[k]*1e9/nanos[k], turns[k]);
        if (wins <= games-wins-draws) System.exit(2);
    }
}
