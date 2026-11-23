package com.circus.bot;

import com.circus.engine.ArenaEngine;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Legacy alias / preset wrapper over {@link CircusSearchBot} with attrition defaults.
 * All teams can now configure the exact same parameters directly in {@code team/*.json}
 * using {@code com.circus.bot.CircusSearchBot}.
 */
public final class FortressBot extends CircusSearchBot {
    private static BotContext withFortressDefaults(BotContext ctx) {
        Map<String, Object> merged = new LinkedHashMap<>();
        merged.put("profile", "iron_wall");
        merged.put("time_budget", 0.25);
        merged.put("max_depth", 10);
        merged.put("root_width", 10);
        merged.put("branch_width", 4);
        merged.put("detailed_tt_hash", true);
        merged.put("strategic_eval", true);
        merged.put("mitigation_weight", 1.0);
        merged.put("pressure_weight", 1.0);
        merged.put("setup_weight", 1.0);
        merged.put("bulwark_priority", 120.0);
        merged.put("guard_bias", -100.0);
        merged.put("control_bonus", 0.0);
        if (ctx.options() != null) merged.putAll(ctx.options());
        return new BotContext(
                ctx.teamId(), ctx.name(), ctx.presetId(), ctx.teamConfig(),
                merged, ctx.seed(), ctx.timeBudgetOverrideSec()
        );
    }

    public FortressBot(BotContext ctx) {
        super(withFortressDefaults(ctx));
    }

    @Override public boolean requiresUnitObservations() { return false; }

    @Override protected long searchStateHash(ArenaEngine engine) {
        return super.searchStateHash(engine);
    }
}
