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
    private static final Map<String, BotFactory> RESOLVED_FACTORIES = new ConcurrentHashMap<>();

    static {
        registerBuiltin("unit_neural_search", UnitNeuralSearchBot.class, UnitNeuralSearchBot::new);
        registerBuiltin("neural_search", NeuralSearchBot.class, NeuralSearchBot::new);
        register("lookahead", CircusSearchBot::new);
        registerBuiltin("search", CircusSearchBot.class, CircusSearchBot::new);
        registerBuiltin("fortress", FortressBot.class, FortressBot::new);
        registerBuiltin("tactical", TacticalBot.class, TacticalBot::new);
        registerBuiltin("stress", StressSpecialistBot.class, StressSpecialistBot::new);
        registerBuiltin("random", RandomBot.class, RandomBot::new);
    }

    private static void registerBuiltin(String alias, Class<? extends Bot> cls, BotFactory factory) {
        register(alias, factory);
        RESOLVED_FACTORIES.put(cls.getName(), factory);
        RESOLVED_FACTORIES.put(cls.getSimpleName(), factory);
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
     * Return selectable Bot implementations (built-in aliases + any custom classes
     * referenced by team configs).
     */
    public static List<Map<String, String>> availableBotClasses() {
        List<Map<String, String>> out = new ArrayList<>();
        Set<String> seenClasses = new LinkedHashSet<>();

        addBotOption(out, seenClasses, "unit_neural_search", "UnitNeuralSearchBot (单位级神经搜索)", UnitNeuralSearchBot.class.getName());
        addBotOption(out, seenClasses, "neural_search", "NeuralSearchBot (轻量神经搜索)", NeuralSearchBot.class.getName());
        addBotOption(out, seenClasses, "search", "CircusSearchBot (时间展开搜索)", CircusSearchBot.class.getName());
        addBotOption(out, seenClasses, "fortress", "FortressBot (铁壁防守搜索)", FortressBot.class.getName());
        addBotOption(out, seenClasses, "tactical", "TacticalBot (战术启发)", TacticalBot.class.getName());
        addBotOption(out, seenClasses, "stress", "StressSpecialistBot (压力专精)", StressSpecialistBot.class.getName());
        addBotOption(out, seenClasses, "random", "RandomBot (随机)", RandomBot.class.getName());

        for (TeamEntry te : TeamConfigStore.list()) {
            String cls = te.botClass();
            if (cls != null && !cls.isBlank() && !seenClasses.contains(cls)) {
                Class<?> resolved = resolveClass(cls);
                String fqcn = resolved != null ? resolved.getName() : cls;
                if (!seenClasses.contains(fqcn)) {
                    String simple = fqcn.contains(".") ? fqcn.substring(fqcn.lastIndexOf('.') + 1) : fqcn;
                    addBotOption(out, seenClasses, fqcn, simple + " (自定义)", fqcn);
                }
            }
        }
        return out;
    }

    private static void addBotOption(
            List<Map<String, String>> out,
            Set<String> seenClasses,
            String id,
            String label,
            String className
    ) {
        seenClasses.add(className);
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("label", label);
        m.put("class_name", className);
        out.add(m);
    }

    /**
     * Instantiate a Bot from a {@link TeamEntry} loaded from {@code team/*.json}.
     */
    public static Bot createFromTeam(TeamEntry entry, String nameOverride, Long seed, Double timeBudgetOverrideSec) {
        return createFromTeam(entry, null, nameOverride, seed, timeBudgetOverrideSec);
    }

    public static Bot createFromTeam(
            TeamEntry entry,
            String botClassOverride,
            String nameOverride,
            Long seed,
            Double timeBudgetOverrideSec
    ) {
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
        String targetClass = (botClassOverride != null && !botClassOverride.isBlank())
                ? botClassOverride.trim()
                : entry.botClass();
        return instantiateBotClass(targetClass, ctx);
    }

    /**
     * Create a Bot by team ID (from {@code team/*.json}), registered alias, or Java class name.
     */
    public static Bot create(String botOrTeamId, String nameOverride, String presetIdOverride, Long seed, Double timeBudgetSec) {
        return create(botOrTeamId, nameOverride, presetIdOverride, seed, timeBudgetSec, null);
    }

    /**
     * Create a Bot by team ID (from {@code team/*.json}), registered alias, or Java class name,
     * with an optional {@code botClassOverride} to run a team using a different Bot implementation.
     */
    public static Bot create(
            String botOrTeamId,
            String nameOverride,
            String presetIdOverride,
            Long seed,
            Double timeBudgetSec,
            String botClassOverride
    ) {
        if (botOrTeamId == null || botOrTeamId.isBlank()) {
            throw new IllegalArgumentException("Bot/Team id cannot be empty");
        }
        String key = botOrTeamId.trim();
        String clsOverride = (botClassOverride != null && !botClassOverride.isBlank()) ? botClassOverride.trim() : null;

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
            return instantiateBotClass(clsOverride != null ? clsOverride : teamEntry.botClass(), ctx);
        }

        String targetBot = clsOverride != null ? clsOverride : key;

        // 2. Check registered short aliases
        BotFactory alias = CLASS_ALIASES.get(targetBot.toLowerCase(Locale.ROOT));
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
        return instantiateBotClass(targetBot, ctx);
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

        BotFactory cached = RESOLVED_FACTORIES.get(raw);
        if (cached != null) {
            return cached.create(ctx);
        }

        Class<?> cls = resolveClass(raw);
        if (cls == null) {
            throw new IllegalArgumentException(
                    "Cannot load Bot class '" + raw + "'. Ensure it is compiled (run build.bat) and implements com.circus.bot.Bot.");
        }
        if (!Bot.class.isAssignableFrom(cls)) {
            throw new IllegalArgumentException("Class '" + cls.getName() + "' does not implement com.circus.bot.Bot");
        }

        BotFactory factory = buildReflectiveFactory(cls);
        RESOLVED_FACTORIES.put(raw, factory);
        RESOLVED_FACTORIES.put(cls.getName(), factory);
        return factory.create(ctx);
    }

    private static BotFactory buildReflectiveFactory(Class<?> cls) {
        Constructor<?> ctxCtor = null;
        Constructor<?> nameCtor = null;
        Constructor<?> noArgCtor = null;
        try { ctxCtor = cls.getConstructor(BotContext.class); } catch (NoSuchMethodException ignored) {}
        if (ctxCtor == null) {
            try { nameCtor = cls.getConstructor(String.class); } catch (NoSuchMethodException ignored) {}
        }
        if (ctxCtor == null && nameCtor == null) {
            try { noArgCtor = cls.getConstructor(); } catch (NoSuchMethodException e) {
                throw new RuntimeException("Failed to instantiate Bot class " + cls.getName(), e);
            }
        }
        final Constructor<?> cCtx = ctxCtor;
        final Constructor<?> cName = nameCtor;
        final Constructor<?> cNone = noArgCtor;
        return ctx -> {
            try {
                Bot instance;
                if (cCtx != null) instance = (Bot) cCtx.newInstance(ctx);
                else if (cName != null) instance = (Bot) cName.newInstance(ctx.name());
                else instance = (Bot) cNone.newInstance();

                if (instance instanceof AbstractBot ab) {
                    if (ctx.name() != null && !ctx.name().isEmpty()) ab.setName(ctx.name());
                    if (ctx.teamConfig() != null) ab.setFixedTeam(ctx.teamConfig());
                }
                return instance;
            } catch (Exception e) {
                throw new RuntimeException("Failed to instantiate Bot class " + cls.getName(), e);
            }
        };
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
