package com.circus.assets;

import com.circus.Config;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Fast StAX XML localization loader for Darkest Dungeon & The Butcher's Circus (EN & ZH).
 */
public final class LocalizationStore {
    private static volatile LocalizationStore INSTANCE;
    private static final Pattern TAG_RE = Pattern.compile("\\{[^}]+\\}");
    private static final String[] UI_PREFIXES = {
            "hero_class_name_",
            "combat_skill_name_",
            "str_inventory_title_trinket",
            "arenacombat_",
            "circus_",
            "ability_",
            "str_afflicted_",
            "str_virtued_",
            "str_heart_attack",
            "str_deaths_door",
            "str_deathblow"
    };

    private final Map<String, String> enStrings = new HashMap<>(4096);
    private final Map<String, String> zhStrings = new HashMap<>(4096);

    public LocalizationStore(Path gameDir, Path dlcDir) {
        loadAll(gameDir, dlcDir);
    }

    public static LocalizationStore get() {
        LocalizationStore inst = INSTANCE;
        if (inst == null) {
            synchronized (LocalizationStore.class) {
                inst = INSTANCE;
                if (inst == null) {
                    inst = new LocalizationStore(Config.DD_GAME_DIR, Config.DD_DLC_DIR);
                    INSTANCE = inst;
                }
            }
        }
        return inst;
    }

    public static String stripDdTags(String text) {
        if (text == null || text.isEmpty()) return "";
        return TAG_RE.matcher(text).replaceAll("").trim();
    }

    private void loadAll(Path gameDir, Path dlcDir) {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        try {
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        } catch (Exception ignored) {}

        Path baseLoc = gameDir.resolve("localization");
        for (String fname : new String[]{
                "heroes.string_table.xml",
                "misc.string_table.xml",
                "miscellaneous.string_table.xml",
                "dialogue.string_table.xml",
                "barks.string_table.xml"
        }) {
            loadXml(factory, baseLoc.resolve(fname));
        }

        Path[] dlcDirs = {
                gameDir.resolve("dlc").resolve("580100_crimson_court").resolve("features").resolve("flagellant").resolve("localization"),
                gameDir.resolve("dlc").resolve("702540_shieldbreaker").resolve("localization"),
                gameDir.resolve("dlc").resolve("445700_musketeer").resolve("localization"),
                dlcDir.resolve("localization")
        };
        for (Path d : dlcDirs) {
            if (!Files.isDirectory(d)) continue;
            try (Stream<Path> s = Files.list(d)) {
                s.filter(p -> p.getFileName().toString().endsWith(".string_table.xml"))
                        .sorted()
                        .forEach(p -> loadXml(factory, p));
            } catch (Exception ignored) {}
        }
    }

    private void loadXml(XMLInputFactory factory, Path path) {
        if (!Files.isRegularFile(path)) return;
        try (InputStream is = new BufferedInputStream(Files.newInputStream(path), 65536)) {
            XMLStreamReader r = factory.createXMLStreamReader(is, "UTF-8");
            Map<String, String> bucket = null;
            String currentEntryId = null;
            StringBuilder textBuf = null;

            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    String name = r.getLocalName();
                    if ("language".equals(name)) {
                        String id = r.getAttributeValue(null, "id");
                        if ("english".equals(id)) bucket = enStrings;
                        else if ("schinese".equals(id)) bucket = zhStrings;
                        else bucket = null;
                    } else if ("entry".equals(name) && bucket != null) {
                        currentEntryId = r.getAttributeValue(null, "id");
                        textBuf = new StringBuilder(64);
                    }
                } else if ((ev == XMLStreamConstants.CHARACTERS || ev == XMLStreamConstants.CDATA) && textBuf != null) {
                    textBuf.append(r.getText());
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    String name = r.getLocalName();
                    if ("entry".equals(name) && currentEntryId != null && bucket != null && textBuf != null) {
                        if (!textBuf.isEmpty()) {
                            bucket.put(currentEntryId, textBuf.toString());
                        }
                        currentEntryId = null;
                        textBuf = null;
                    } else if ("language".equals(name)) {
                        bucket = null;
                    }
                }
            }
            r.close();
        } catch (Exception ignored) {}
    }

    public String get(String key, String lang, String def, boolean clean) {
        Map<String, String> primary = "zh".equals(lang) ? zhStrings : enStrings;
        String val = primary.get(key);
        if (val == null || val.isEmpty()) {
            val = enStrings.get(key);
        }
        if (val == null || val.isEmpty()) return def;
        return clean ? stripDdTags(val) : val;
    }

    public String heroName(String heroId, String lang) {
        return get("hero_class_name_" + heroId, lang, titleCase(heroId), true);
    }

    public String skillName(String skillId, String lang, String def) {
        return get("combat_skill_name_" + skillId, lang, (def != null && !def.isEmpty()) ? def : titleCase(skillId), true);
    }

    public Map<String, String> exportUiBundle(String lang) {
        Map<String, String> out = new LinkedHashMap<>();
        Set<String> keys = new HashSet<>(enStrings.keySet());
        if ("zh".equals(lang)) keys.addAll(zhStrings.keySet());
        for (String k : keys) {
            for (String p : UI_PREFIXES) {
                if (k.startsWith(p)) {
                    out.put(k, get(k, lang, "", true));
                    break;
                }
            }
        }
        return out;
    }

    private static String titleCase(String s) {
        String[] parts = s.split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
    }
}
