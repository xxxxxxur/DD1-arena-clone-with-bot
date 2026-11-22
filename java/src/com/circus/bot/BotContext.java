package com.circus.bot;

import com.circus.engine.Models.TeamConfig;
import com.circus.util.Json;

import java.util.Collections;
import java.util.Map;

/**
 * Construction context passed to Bot classes instantiated from {@code team/*.json}
 * or {@link BotRegistry}. Allows multiple team configs to share the same Bot class
 * with different lineups and {@code bot_options}.
 */
public record BotContext(
        String teamId,
        String name,
        String presetId,
        TeamConfig teamConfig,
        Map<String, Object> options,
        Long seed,
        Double timeBudgetOverrideSec
) {
    public BotContext {
        options = (options != null) ? options : Collections.emptyMap();
    }

    public String optionString(String key, String def) {
        return Json.str(options, key, def);
    }

    public double optionDouble(String key, double def) {
        return Json.dblVal(options, key, def);
    }

    public int optionInt(String key, int def) {
        return Json.intVal(options, key, def);
    }

    public double effectiveTimeBudget(double fallback) {
        if (timeBudgetOverrideSec != null) return timeBudgetOverrideSec;
        return optionDouble("time_budget", fallback);
    }
}
