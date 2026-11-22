package com.circus.bot;

import com.circus.bot.TeamConfigStore.TeamEntry;
import com.circus.engine.Models.TeamConfig;

import java.lang.reflect.Constructor;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central registry bridging decoupled {@code team/*.json} team files and Bot classes
 * (from {@code com.circus.bot.*}, {@code bot.*}, or any class on the classpath).
 *
 * Multiple teams in {@code team/*.json} can reference the same {@code bot_class}
 * with different lineups and {@code bot_options}.
 */
public final class BotRegistry {
    @FunctionalInterface
    public interface BotFactory {
        Bot create(BotContext ctx);
    }

    private static final Map<String, BotFactory> CLASS_ALIASES = new ConcurrentHashMap<>();

    static {
        register("lookahead", CircusSearchBot::new);
        register("search", CircusSearchBot::new);
        register("tactical", TacticalBot::new);
        register("stress", StressSpecialistBot::new);
        register("random", RandomBot::new);
    }

    private BotRegistry() {}

    public static void register(String type, BotFactory factory) {
        CLASS_ALIASES.put(type.toLowerCase(Locale.ROOT), factory);
    }

    public static List<String> availableTypes() {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (TeamEntry te : TeamConfigStore.list()) {
            keys.add(te.id());
        }
        keys.addAll(CLASS_ALIASES.keySet());
        List<String> out = new ArrayList<>(keys);
        Collections.sort(out);
        return out;
    }

    /**
     * Instantiate a Bot from a {@link TeamEntry} loaded from {@code team/*.json}.
     */
    public static Bot createFromTeam(TeamEntry entry, String nameOverride, Long seed, Double timeBudgetOverrideSec) {
        String name = (nameOverride != null && !nameOverride.isEmpty()) ? nameOverride : entry.name();
        BotContext ctx = new BotContext(
                entry.id(),
                name,
                null,
                entry.team().copy(),
                entry.botOptions(),
                seed,
                timeBudgetOverrideSec
        );
        return instantiateBotClass(entry.botClass(), ctx);
    }

    /**
     * Create a Bot by team ID (from {@code team/*.json}), registered alias, or Java class name.
     */
    public static Bot create(String botOrTeamId, String nameOverride, String presetIdOverride, Long seed, Double timeBudgetSec) {
        if (botOrTeamId == null || botOrTeamId.isBlank()) {
            throw new IllegalArgumentException("Bot/Team id cannot be empty");
        }
        String key = botOrTeamId.trim();

        // 1. Check team/*.json first (unless caller explicitly overrides preset with a different preset?
        //    Still load team entry, and if presetIdOverride is given and non-empty, use preset override!)
        TeamEntry teamEntry = TeamConfigStore.get(key);
        if (teamEntry != null) {
            TeamConfig cfg = (presetIdOverride != null && !presetIdOverride.isEmpty()) ? null : teamEntry.team().copy();
            String name = (nameOverride != null && !nameOverride.isEmpty()) ? nameOverride : teamEntry.name();
            BotContext ctx = new BotContext(
                    teamEntry.id(),
                    name,
                    (presetIdOverride != null && !presetIdOverride.isEmpty()) ? presetIdOverride : null,
                    cfg,
                    teamEntry.botOptions(),
                    seed,
                    timeBudgetSec
            );
            return instantiateBotClass(teamEntry.botClass(), ctx);
        }

        // 2. Check registered short aliases
        BotFactory alias = CLASS_ALIASES.get(key.toLowerCase(Locale.ROOT));
        if (alias != null) {
            BotContext ctx = new BotContext(
                    key,
                    (nameOverride != null && !nameOverride.isEmpty()) ? nameOverride : key,
                    presetIdOverride,
                    null,
                    Collections.emptyMap(),
                    seed,
                    timeBudgetSec
            );
            return alias.create(ctx);
        }

        // 3. Direct Java class name (e.g. "bot.ExampleCustomBot" or "com.circus.bot.CircusSearchBot")
        BotContext ctx = new BotContext(
                key,
                (nameOverride != null && !nameOverride.isEmpty()) ? nameOverride : key,
                presetIdOverride,
                null,
                Collections.emptyMap(),
                seed,
                timeBudgetSec
        );
        return instantiateBotClass(key, ctx);
    }

    public static Bot create(String botOrTeamId, String presetId) {
        return create(botOrTeamId, null, presetId, null, null);
    }

    /**
     * Resolve and instantiate a Bot class by fully-qualified or simple class name.
     * Supports constructors: (BotContext), (String name), or () no-arg.
     */
    public static Bot instantiateBotClass(String className, BotContext ctx) {
        if (className == null || className.isBlank()) {
            return new CircusSearchBot(ctx);
        }
        String raw = className.trim();

        // Support alias names inside team/*.json bot_class too (e.g. "random", "tactical")
        BotFactory alias = CLASS_ALIASES.get(raw.toLowerCase(Locale.ROOT));
        if (alias != null) {
            return alias.create(ctx);
        }

        Class<?> cls = resolveClass(raw);
        if (cls == null) {
            throw new IllegalArgumentException(
                    "Cannot load Bot class '" + raw + "'. Ensure it is compiled (run build.bat) and implements com.circus.bot.Bot.");
        }
        if (!Bot.class.isAssignableFrom(cls)) {
            throw new IllegalArgumentException("Class '" + cls.getName() + "' does not implement com.circus.bot.Bot");
        }

        try {
            Bot instance = null;
            // 1. Try (BotContext)
            try {
                Constructor<?> c = cls.getConstructor(BotContext.class);
                instance = (Bot) c.newInstance(ctx);
            } catch (NoSuchMethodException ignored) {}

            // 2. Try (String)
            if (instance == null) {
                try {
                    Constructor<?> c = cls.getConstructor(String.class);
                    instance = (Bot) c.newInstance(ctx.name());
                } catch (NoSuchMethodException ignored) {}
            }

            // 3. Try ()
            if (instance == null) {
                Constructor<?> c = cls.getConstructor();
                instance = (Bot) c.newInstance();
            }

            if (instance instanceof AbstractBot ab) {
                if (ctx.name() != null && !ctx.name().isEmpty()) ab.setName(ctx.name());
                if (ctx.teamConfig() != null) ab.setFixedTeam(ctx.teamConfig());
            }
            return instance;
        } catch (Exception e) {
            throw new RuntimeException("Failed to instantiate Bot class " + cls.getName(), e);
        }
    }

    private static Class<?> resolveClass(String name) {
        String[] candidates = name.contains(".")
                ? new String[]{name}
                : new String[]{"com.circus.bot." + name, "bot." + name, name};
        ClassLoader cl = BotRegistry.class.getClassLoader();
        for (String cand : candidates) {
            try {
                return Class.forName(cand, true, cl);
            } catch (ClassNotFoundException ignored) {}
        }
        return null;
    }
}
