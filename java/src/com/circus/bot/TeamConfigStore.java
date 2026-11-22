package com.circus.bot;

import com.circus.Config;
import com.circus.engine.Models.TeamConfig;
import com.circus.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Loads decoupled team + bot-class configurations from the {@code team/} folder.
 *
 * Each JSON file in {@code team/} defines:
 * - {@code id}: unique identifier (defaults to filename stem)
 * - {@code name} / {@code name_en} / {@code name_zh}: display names
 * - {@code bot_class}: fully-qualified or simple Bot class name (e.g. {@code com.circus.bot.CircusSearchBot}
 *   or {@code bot.ExampleCustomBot})
 * - {@code bot_options}: optional key-value map passed to the Bot class via {@link BotContext}
 * - {@code team}: full 4-hero {@link TeamConfig} (heroes, skills, trinkets, skin variants)
 */
public final class TeamConfigStore {
    public record TeamEntry(
            String id,
            String name,
            String nameEn,
            String nameZh,
            String botClass,
            Map<String, Object> botOptions,
            TeamConfig team,
            Path sourceFile
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("name", name);
            m.put("name_en", nameEn);
            m.put("name_zh", nameZh);
            m.put("bot_class", botClass);
            m.put("bot_options", botOptions);
            m.put("team", team.toMap());
            m.put("file", sourceFile != null ? sourceFile.getFileName().toString() : "");
            return m;
        }
    }

    private static volatile Map<String, TeamEntry> CACHE;

    private TeamConfigStore() {}

    public static Map<String, TeamEntry> loadAll() {
        Map<String, TeamEntry> c = CACHE;
        if (c == null) {
            c = reload();
        }
        return c;
    }

    public static synchronized Map<String, TeamEntry> reload() {
        Map<String, TeamEntry> map = loadFromDir(Config.TEAM_DIR);
        CACHE = map;
        return map;
    }

    public static Map<String, TeamEntry> loadFromDir(Path dir) {
        Map<String, TeamEntry> out = new LinkedHashMap<>();
        if (dir == null || !Files.isDirectory(dir)) {
            return Collections.unmodifiableMap(out);
        }
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return Collections.unmodifiableMap(out);
        }

        for (Path p : files) {
            try {
                String raw = Files.readString(p, StandardCharsets.UTF_8);
                Map<String, Object> root = Json.parseObject(raw);
                String fname = p.getFileName().toString();
                String stem = fname.substring(0, fname.length() - 5);
                String id = Json.str(root, "id", stem);
                if (id.isEmpty()) id = stem;
                Map<String, Object> teamMap = Json.mapVal(root, "team");
                if (teamMap == null) continue;
                TeamConfig teamCfg = TeamConfig.fromMap(teamMap);
                String name = Json.str(root, "name", teamCfg.name);
                String nameEn = Json.str(root, "name_en", name);
                String nameZh = Json.str(root, "name_zh", name);
                String botClass = Json.str(root, "bot_class", "com.circus.bot.CircusSearchBot");
                Map<String, Object> botOpts = Json.mapVal(root, "bot_options");
                if (botOpts == null) botOpts = Collections.emptyMap();

                TeamEntry entry = new TeamEntry(
                        id, name, nameEn, nameZh, botClass,
                        Collections.unmodifiableMap(new LinkedHashMap<>(botOpts)),
                        teamCfg, p
                );
                out.put(id.toLowerCase(Locale.ROOT), entry);
            } catch (Exception e) {
                System.err.println("[TeamConfigStore] Failed to load " + p.getFileName() + ": " + e.getMessage());
            }
        }
        return Collections.unmodifiableMap(out);
    }

    public static TeamEntry get(String id) {
        if (id == null || id.isEmpty()) return null;
        return loadAll().get(id.toLowerCase(Locale.ROOT));
    }

    public static List<TeamEntry> list() {
        return List.copyOf(loadAll().values());
    }
}
