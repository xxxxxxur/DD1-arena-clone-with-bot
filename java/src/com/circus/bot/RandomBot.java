package com.circus.bot;

import com.circus.engine.ArenaEngine;
import com.circus.engine.Models.Action;
import com.circus.engine.Models.Observation;

import java.util.List;
import java.util.SplittableRandom;

/**
 * Fast baseline RandomBot (prefers SKILL > MOVE > PASS).
 */
public final class RandomBot extends AbstractBot {
    private final SplittableRandom rng;

    public RandomBot(String name, Long seed, String presetId) {
        super(name != null ? name : "RandomBot", presetId);
        this.rng = (seed != null) ? new SplittableRandom(seed) : new SplittableRandom();
    }

    public RandomBot(BotContext ctx) {
        super(ctx);
        this.rng = (ctx != null && ctx.seed() != null) ? new SplittableRandom(ctx.seed()) : new SplittableRandom();
    }

    public RandomBot() {
        this("RandomBot", null, null);
    }

    @Override
    public boolean requiresUnitObservations() { return false; }

    @Override
    public Action chooseAction(Observation obs, ArenaEngine engine) {
        List<Action> legal = obs.legalActions;
        int skills = 0, moves = 0;
        for (Action a : legal) {
            if ("SKILL".equals(a.kind)) skills++;
            else if ("MOVE".equals(a.kind)) moves++;
        }
        String kind = skills > 0 ? "SKILL" : "MOVE";
        int count = skills > 0 ? skills : moves;
        if (count > 0) {
            int index = rng.nextInt(count);
            for (Action a : legal) if (kind.equals(a.kind) && index-- == 0) return a;
        }
        return legal.get(rng.nextInt(legal.size()));
    }
}
