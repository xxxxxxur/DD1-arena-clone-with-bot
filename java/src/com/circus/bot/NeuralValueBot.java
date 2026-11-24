package com.circus.bot;

import com.circus.engine.*;
import java.nio.file.*;

/** Shared leaf evaluation for the current search and the frozen arena opponents. */
abstract class NeuralValueBot extends CircusSearchBot {
    private final ArenaValue model, tactics;
    private final boolean alignBase;
    private final double[] features = new double[ArenaValue.INPUT], hidden = new double[ArenaValue.HIDDEN];
    private final EffectiveStats stats = new EffectiveStats();
    private final EffectiveStats[][] unitStats = {
            {new EffectiveStats(), new EffectiveStats(), new EffectiveStats(), new EffectiveStats()},
            {new EffectiveStats(), new EffectiveStats(), new EffectiveStats(), new EffectiveStats()}
    };

    NeuralValueBot(BotContext ctx) {
        super(ctx);
        alignBase = ctx.optionInt("align_base", 0) != 0;
        Path path = Path.of(ctx.optionString("model", ArenaValue.FILE.toString()));
        model = Files.exists(path) || ctx.options().containsKey("model") ? ArenaValue.readCached(path) : null;
        Path defaultTactics = ctx.effectiveTimeBudget(.8) >= .3 && Files.isRegularFile(TacticalFeatures.BALANCED)
                ? TacticalFeatures.BALANCED : TacticalFeatures.FILE;
        Path tacticsFile = Path.of(ctx.optionString("tactics_model", defaultTactics.toString()));
        tactics = ctx.optionInt("use_tactics", 1) == 1 && (Files.exists(tacticsFile) || ctx.options().containsKey("tactics_model")) ? ArenaValue.readCached(tacticsFile) : null;
    }

    @Override public boolean requiresUnitObservations() { return false; }

    @Override public double evaluateState(ArenaEngine e, int team) {
        double base = alignBase ? evaluateBalancedBase(e, team) : super.evaluateState(e, team);
        if (model == null || e.state.finished) return base;
        ArenaValue.encode(e, team, features, stats, tactics != null ? unitStats : null);
        double logit = model.residual(features, hidden);
        if (tactics != null) {
            TacticalFeatures.encode(e, team, features, stats, unitStats);
            logit += tactics.residual(features, hidden);
        }
        return base + ArenaValue.SCALE * logit;
    }
}
