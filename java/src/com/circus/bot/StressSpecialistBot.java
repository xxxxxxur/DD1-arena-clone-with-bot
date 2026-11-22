package com.circus.bot;

/**
 * Stress-comp specialist bot using high-speed Java Expectimax search.
 */
public final class StressSpecialistBot extends TacticalBot {
    public StressSpecialistBot(String name, Long seed, String presetId, double timeBudgetSec) {
        super(
                name != null ? name : "StressBot",
                seed,
                (presetId != null && !presetId.isEmpty()) ? presetId : "stress_circus",
                2.1,
                timeBudgetSec,
                "stress"
        );
    }

    public StressSpecialistBot(BotContext ctx) {
        super(
                ctx.name(),
                ctx.seed(),
                (ctx.presetId() != null && !ctx.presetId().isEmpty()) ? ctx.presetId() : "stress_circus",
                ctx.optionDouble("stress_weight", 2.1),
                ctx.effectiveTimeBudget(0.25),
                ctx.optionString("profile", "stress")
        );
        if (ctx.teamConfig() != null) setFixedTeam(ctx.teamConfig());
    }
}
