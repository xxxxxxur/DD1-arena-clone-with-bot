package com.circus.bot;

import com.circus.engine.*;
import com.circus.engine.Models.*;
import java.util.*;

/** Stochastic lookahead with paired combat rolls and successive root elimination. */
public class NeuralSearchBot extends NeuralValueBot {
    private final long budget;
    private final int horizon;
    private final boolean extended, controlVariate;
    protected final ArenaEngine.SearchScratch scratch = new ArenaEngine.SearchScratch();

    public NeuralSearchBot(BotContext ctx) {
        super(ctx);
        budget = (long)(Math.clamp(ctx.effectiveTimeBudget(.8), .005, .8) * 1e9);
        controlVariate = ctx.optionInt("control_variate", budget >= 300_000_000L ? 1 : 0) != 0;
        extended = ctx.optionInt("extended", 1) != 0;
        horizon = Math.clamp(ctx.optionInt("rollout_plies", 5), 1, 16);
    }

    private static final class Candidate {
        final ScoredAction move;
        double prior, sum;
        int visits;
        Candidate(ScoredAction move) { this.move = move; }

        double mean() { return (sum + prior * 2) / (visits + 2); }
    }

    // Reply policy looks one step ahead with an independent roll, so the policy
    // cannot condition its decision on the future RNG of the rollout it plays.
    protected Action reply(ArenaEngine e, long seed, long end) {
        int team = e.state.activeTeam;
        var actions = orderedCandidates(e, team, 3, null, -1, e.getLegalActions());
        Action best = actions.getFirst().action();
        if (actions.size() == 1) return best;
        double high = -Double.MAX_VALUE;
        for (ScoredAction a : actions) {
            if (System.nanoTime() >= end) break;
            ArenaEngine trial = e.cloneForSearch(scratch);
            trial.rng.reseed(seed);
            trial.step(a.action());
            double v = evaluateState(trial, team) + .2 * a.score();
            if (v > high) { high = v; best = a.action(); }
        }
        return best;
    }

    private double rollout(ArenaEngine root, Candidate c, long seed, long end) {
        int team = root.state.activeTeam;
        ArenaEngine e = root.cloneForSearch(scratch);
        e.rng.reseed(seed);
        e.step(c.move.action());
        double correction = 0;
        if (controlVariate) {
            // Estimate and subtract the part of this rollout explained by its first roll.
            double after = Math.tanh((evaluateState(e, team) + .25 * c.move.score()) / (2 * ArenaValue.SCALE));
            correction = .6 * (c.prior - after);
        }
        for (int ply = 1; ply < horizon && !e.state.finished; ply++) {
            if (System.nanoTime() >= end) return Double.NaN;
            e.step(reply(e, seed ^ (0x9e3779b97f4a7c15L * ply), end));
        }
        if (extended && !e.state.finished) {
            double near = evaluateState(e, team);
            int plies = e.countLivingHeroes(0) + e.countLivingHeroes(1) + 3;
            for (int i = 0; i < plies && !e.state.finished; i++) {
                if (System.nanoTime() >= end) return Double.NaN;
                var moves = orderedCandidates(e, e.state.activeTeam, 1, null, -1, e.getLegalActions());
                e.step(moves.getFirst().action());
            }
            double deep = Math.tanh(evaluateState(e, team) / (2 * ArenaValue.SCALE));
            return correction + .4 * Math.tanh((near + .12 * c.move.score()) / (2 * ArenaValue.SCALE)) + .6 * deep;
        }
        // A bounded utility averages win prospects across lucky hits and misses.
        return correction + Math.tanh((evaluateState(e, team) + .12 * c.move.score()) / (2 * ArenaValue.SCALE));
    }

    @Override public Action chooseAction(Observation obs, ArenaEngine engine) {
        long start = System.nanoTime();
        var legal = obs.legalActions;
        if (legal.size() == 1 || engine == null) return legal.getFirst();
        // Leave headroom for GC and scheduling on concurrent tournament workers.
        long usable = budget - Math.min(52_000_000L, budget / 12);
        long end = start + usable;
        var pool = new ArrayList<Candidate>();
        for (var a : orderedCandidates(engine, obs.viewerTeam, legal.size(), null, -1, legal))
            pool.add(new Candidate(a));
        Action incumbent = pool.getFirst().move.action();
        var rng = new SplittableRandom(engine.computeStateHash());
        int samples = budget >= 100_000_000L ? 6 : 2;
        if (controlVariate) samples = budget >= 300_000_000L ? 36 : budget >= 60_000_000L ? 12 : 3;
        // Price each action before pruning, with identical random draws for competitors.
        // Reserve heuristic leaders so delayed setup and rescues survive the shallow screen.
        var reserved = new ArrayList<>(pool.subList(0, Math.min(Math.min(rootWidth, 6), pool.size())));
        for (int pass = 0; pass < samples; pass++) {
            long seed = rng.nextLong();
            double highest = -Double.MAX_VALUE;
            Action shallowBest = incumbent;
            for (var c : pool) {
                if (System.nanoTime() >= end) return incumbent;
                var sim = engine.cloneForSearch(scratch);
                sim.rng.reseed(seed);
                sim.step(c.move.action());
                c.prior += Math.tanh((evaluateState(sim, obs.viewerTeam) + .25 * c.move.score())
                        / (2 * ArenaValue.SCALE)) / samples;
                if (c.prior > highest) {
                    highest = c.prior;
                    shallowBest = c.move.action();
                }
            }
            incumbent = shallowBest;
        }
        pool.sort(Comparator.comparingDouble((Candidate c) -> c.prior).reversed());
        for (var c : pool) {
            if (reserved.size() >= rootWidth) break;
            if (!reserved.contains(c)) reserved.add(c);
        }
        pool = reserved;
        int stages = Math.max(1, 32 - Integer.numberOfLeadingZeros(pool.size() - 1));
        long raceStart = System.nanoTime();
        for (int stage = 1; stage <= stages; stage++) {
            long boundary = raceStart + (end - raceStart) * stage / stages;
            do {
                long seed = rng.nextLong();
                for (Candidate c : pool) {
                    if (System.nanoTime() >= end) return incumbent;
                    double v = rollout(engine, c, seed, end);
                    if (Double.isNaN(v)) return incumbent;
                    c.sum += v;
                    c.visits++;
                }
                pool.sort(Comparator.comparingDouble(Candidate::mean).reversed());
                incumbent = pool.getFirst().move.action();
            } while (System.nanoTime() < boundary);
            if (pool.size() <= 2) break;
            pool.subList((pool.size() + 1) / 2, pool.size()).clear();
        }
        return incumbent;
    }
}
