package com.circus.engine;

import com.circus.Config;
import com.circus.bot.TeamConfigStore;
import com.circus.engine.Models.*;
import com.circus.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Loads all immutable Butcher's Circus static game definitions:
 * 18 heroes, 126 skills, 103 trinkets, 13 traits, and all competitive + official practice presets.
 */
public final class GameData {
    private static volatile GameData INSTANCE;

    public final List<String> heroOrder;
    public final Map<String, HeroDef> heroes;
    public final Map<String, TrinketDef> trinkets;
    public final Map<String, TraitDef> afflictions;
    public final Map<String, TraitDef> virtues;
    public final TraitDef rapturous;
    public final Map<String, TraitDef> traits;
    public final List<TraitDef> afflictionList;
    public final List<TraitDef> virtueList;
    public final List<PresetDef> basePresets;

    private GameData(Path dataPath) {
        try {
            String raw = Files.readString(dataPath, StandardCharsets.UTF_8);
            Map<String, Object> root = Json.parseObject(raw);

            List<String> order = new ArrayList<>();
            for (Object o : Json.listVal(root, "hero_order")) {
                order.add(String.valueOf(o));
            }
            this.heroOrder = Collections.unmodifiableList(order);

            Map<String, HeroDef> heroMap = new LinkedHashMap<>();
            for (Object o : Json.listVal(root, "heroes")) {
                @SuppressWarnings("unchecked")
                HeroDef h = parseHero((Map<String, Object>) o);
                heroMap.put(h.id, h);
            }
            this.heroes = Collections.unmodifiableMap(heroMap);

            Map<String, TrinketDef> trMap = new LinkedHashMap<>();
            for (Object o : Json.listVal(root, "trinkets")) {
                @SuppressWarnings("unchecked")
                TrinketDef t = parseTrinket((Map<String, Object>) o);
                trMap.put(t.id, t);
            }
            this.trinkets = Collections.unmodifiableMap(trMap);

            Map<String, TraitDef> affMap = new LinkedHashMap<>();
            for (Object o : Json.listVal(root, "afflictions")) {
                @SuppressWarnings("unchecked")
                TraitDef td = parseTrait((Map<String, Object>) o);
                affMap.put(td.id, td);
            }
            this.afflictions = Collections.unmodifiableMap(affMap);
            this.afflictionList = List.copyOf(affMap.values());

            Map<String, TraitDef> virMap = new LinkedHashMap<>();
            for (Object o : Json.listVal(root, "virtues")) {
                @SuppressWarnings("unchecked")
                TraitDef td = parseTrait((Map<String, Object>) o);
                virMap.put(td.id, td);
            }
            this.virtues = Collections.unmodifiableMap(virMap);
            this.virtueList = List.copyOf(virMap.values());

            this.rapturous = parseTrait(Json.mapVal(root, "rapturous"));

            Map<String, TraitDef> allTraits = new LinkedHashMap<>();
            allTraits.putAll(affMap);
            allTraits.putAll(virMap);
            allTraits.put("rapturous", this.rapturous);
            this.traits = Collections.unmodifiableMap(allTraits);

            List<PresetDef> pList = new ArrayList<>();
            for (Object o : Json.listVal(root, "presets")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> pm = (Map<String, Object>) o;
                pList.add(new PresetDef(
                        Json.str(pm, "id", ""),
                        Json.str(pm, "name_en", ""),
                        Json.str(pm, "name_zh", ""),
                        TeamConfig.fromMap(Json.mapVal(pm, "team"))
                ));
            }
            this.basePresets = Collections.unmodifiableList(pList);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load game data from " + dataPath, e);
        }
    }

    public static GameData get() {
        GameData inst = INSTANCE;
        if (inst == null) {
            synchronized (GameData.class) {
                inst = INSTANCE;
                if (inst == null) {
                    inst = new GameData(Config.GAME_DATA_PATH);
                    INSTANCE = inst;
                }
            }
        }
        return inst;
    }

    public List<PresetDef> getPresets() {
        Map<String, TeamConfigStore.TeamEntry> teamEntries = TeamConfigStore.loadAll();
        List<PresetDef> out = new ArrayList<>(teamEntries.size() + basePresets.size() + 16);
        Set<String> seen = new HashSet<>();
        for (TeamConfigStore.TeamEntry e : teamEntries.values()) {
            if (seen.add(e.id())) {
                out.add(new PresetDef(e.id(), e.nameEn(), e.nameZh(), e.team().copy()));
            }
        }
        for (PresetDef p : basePresets) {
            if (seen.add(p.id)) {
                out.add(new PresetDef(p.id, p.nameEn, p.nameZh, p.team.copy()));
            }
        }
        out.addAll(loadOfficialPracticePresets());
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<PresetDef> loadOfficialPracticePresets() {
        Path path = Config.DD_DLC_DIR.resolve("raid").resolve("bots").resolve("arena.practice_bots.json");
        if (!Files.exists(path)) return Collections.emptyList();
        try {
            List<Object> raw = Json.parseArray(Files.readString(path, StandardCharsets.UTF_8));
            List<PresetDef> out = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (int idx = 0; idx < raw.size(); idx++) {
                List<Object> teamRaw = (List<Object>) raw.get(idx);
                List<HeroLoadout> hList = new ArrayList<>();
                StringBuilder sig = new StringBuilder();
                for (Object eo : teamRaw) {
                    Map<String, Object> entry = (Map<String, Object>) eo;
                    String hid = Json.str(entry, "h", "");
                    HeroDef hdef = heroes.get(hid);
                    if (hdef == null) continue;
                    List<String> skills = new ArrayList<>();
                    for (Object so : Json.listVal(entry, "s")) {
                        Map<String, Object> sm = (Map<String, Object>) so;
                        String sn = Json.str(sm, "n", "");
                        if (hdef.getSkill(sn) != null) skills.add(sn);
                    }
                    if (skills.isEmpty()) {
                        Collections.addAll(skills, hdef.defaultSkills);
                    }
                    List<String> trs = new ArrayList<>();
                    for (Object to : Json.listVal(entry, "t")) {
                        String tid = String.valueOf(to);
                        if (trinkets.containsKey(tid) && trs.size() < 2) trs.add(tid);
                    }
                    hList.add(new HeroLoadout(hid, skills, trs, Json.str(entry, "v", "A"), hdef.nameEn));
                    sig.append(hid).append(',');
                }
                if (hList.size() == 4 && seen.add(sig.toString())) {
                    int num = out.size() + 1;
                    out.add(new PresetDef(
                            "official_practice_" + (idx + 1),
                            "Official Circus Squad #" + num + " (Mark)",
                            "官方马戏团练习队 #" + num + "（标记队）",
                            new TeamConfig("Official Mark #" + num, hList)
                    ));
                }
            }
            return out;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private static BuffDef parseBuff(Map<String, Object> m) {
        return new BuffDef(
                Json.str(m, "stat", ""),
                Json.dblVal(m, "value", 0.0),
                Json.intVal(m, "duration", 3),
                Json.str(m, "source", ""),
                Json.boolVal(m, "is_debuff", false)
        );
    }

    @SuppressWarnings("unchecked")
    private static BuffDef[] parseBuffArray(Map<String, Object> m, String key) {
        List<Object> list = Json.listVal(m, key);
        BuffDef[] arr = new BuffDef[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = parseBuff((Map<String, Object>) list.get(i));
        }
        return arr;
    }

    private static int[] parseIntArray(Map<String, Object> m, String key) {
        Object raw = m.get(key);
        if (!(raw instanceof List<?> list)) return null;
        int[] arr = new int[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = ((Number) list.get(i)).intValue();
        }
        return arr;
    }

    private static String[] parseStringArray(Map<String, Object> m, String key) {
        List<Object> list = Json.listVal(m, key);
        String[] arr = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = String.valueOf(list.get(i));
        }
        return arr;
    }

    private static DotSpec parseDotSpec(Map<String, Object> m, String key) {
        Object raw = m.get(key);
        if (!(raw instanceof List<?> list) || list.size() < 3) return null;
        return new DotSpec(
                ((Number) list.get(0)).intValue(),
                ((Number) list.get(1)).intValue(),
                ((Number) list.get(2)).intValue()
        );
    }

    @SuppressWarnings("unchecked")
    private static SkillDef parseSkill(Map<String, Object> m) {
        List<Object> tdRaw = Json.listVal(m, "target_debuffs");
        TargetDebuff[] tds = new TargetDebuff[tdRaw.size()];
        for (int i = 0; i < tdRaw.size(); i++) {
            List<Object> pair = (List<Object>) tdRaw.get(i);
            int ch = ((Number) pair.get(0)).intValue();
            BuffDef bd = parseBuff((Map<String, Object>) pair.get(1));
            tds[i] = new TargetDebuff(ch, bd);
        }

        int[] launch = parseIntArray(m, "launch_ranks");
        int[] target = parseIntArray(m, "target_ranks");
        return new SkillDef(
                Json.str(m, "id", ""),
                Json.str(m, "name_en", ""),
                Json.str(m, "name_zh", ""),
                Json.str(m, "icon", ""),
                Json.str(m, "anim", ""),
                Json.str(m, "fx", ""),
                Json.str(m, "targfx", ""),
                Json.str(m, "target_anchor", "feet"),
                Json.str(m, "sfx_hint", ""),
                launch != null ? launch : new int[]{1, 2},
                target != null ? target : new int[]{1, 2},
                Json.str(m, "target_team", "enemy"),
                Json.boolVal(m, "is_aoe", false),
                Json.boolVal(m, "is_attack", true),
                Json.boolVal(m, "is_ranged", false),
                Json.intVal(m, "acc", 95),
                Json.dblVal(m, "dmg_mod", 0.0),
                Json.dblVal(m, "crit_mod", 0.0),
                Json.intVal(m, "stress_dmg", 0),
                Json.intVal(m, "stress_heal", 0),
                Json.intVal(m, "stress_heal_chance", 100),
                Json.dblVal(m, "heal_pct", 0.0),
                Json.dblVal(m, "self_heal_pct", 0.0),
                Json.boolVal(m, "self_daze", false),
                Json.intVal(m, "self_mark_rounds", 0),
                Json.boolVal(m, "transfer_dots", false),
                Json.boolVal(m, "cures_self_dots", false),
                Json.intVal(m, "self_bleed_chance", 100),
                Json.boolVal(m, "random_target", false),
                Json.boolVal(m, "party_cleanse", false),
                Json.intVal(m, "heal_min", 0),
                Json.intVal(m, "heal_max", 0),
                Json.intVal(m, "self_heal_min", 0),
                Json.intVal(m, "self_heal_max", 0),
                Json.intVal(m, "self_stress", 0),
                parseIntArray(m, "self_bleed"),
                Json.dblVal(m, "deathblow_bonus", 0.0),
                Json.boolVal(m, "armor_piercing", false),
                Json.boolVal(m, "ignores_stealth", false),
                Json.boolVal(m, "de_stealth", false),
                Json.boolVal(m, "ignores_guard", false),
                Json.boolVal(m, "breaks_guard", false),
                Json.boolVal(m, "clears_corpses", false),
                Json.boolVal(m, "no_corpse_on_kill", false),
                Json.boolVal(m, "clears_mark", false),
                Json.boolVal(m, "cures_bleed", false),
                Json.boolVal(m, "cures_blight", false),
                Json.boolVal(m, "cures_horror", false),
                Json.boolVal(m, "clears_stun", false),
                Json.boolVal(m, "clears_debuffs", false),
                Json.boolVal(m, "clears_self_debuffs", false),
                Json.intVal(m, "target_stealth_rounds", 0),
                Json.dblVal(m, "bonus_dmg_vs_marked", 0.0),
                Json.dblVal(m, "bonus_dmg_vs_bleed", 0.0),
                Json.dblVal(m, "bonus_dmg_vs_blight", 0.0),
                Json.dblVal(m, "bonus_dmg_vs_stunned", 0.0),
                Json.dblVal(m, "bonus_dmg_vs_guarded", 0.0),
                Json.dblVal(m, "bonus_crit_vs_marked", 0.0),
                Json.dblVal(m, "bonus_crit_vs_bleed", 0.0),
                Json.dblVal(m, "bonus_crit_vs_blight", 0.0),
                parseDotSpec(m, "bleed"),
                parseDotSpec(m, "blight"),
                parseDotSpec(m, "horror"),
                parseIntArray(m, "regen"),
                Json.intVal(m, "stun_chance", 0),
                Json.intVal(m, "daze_chance", 0),
                Json.intVal(m, "mark_rounds", 0),
                Json.intVal(m, "stealth_rounds", 0),
                Json.intVal(m, "guard_rounds", 0),
                Json.intVal(m, "guard_me_rounds", 0),
                Json.intVal(m, "riposte_rounds", 0),
                Json.intVal(m, "aegis_blocks", 0),
                Json.intVal(m, "self_move", 0),
                Json.intVal(m, "push", 0),
                Json.intVal(m, "pull", 0),
                Json.intVal(m, "move_chance", 110),
                Json.boolVal(m, "shuffle_target", false),
                parseBuffArray(m, "target_buffs"),
                tds,
                parseBuffArray(m, "self_buffs"),
                parseBuffArray(m, "party_buffs"),
                Json.intVal(m, "party_stress_heal", 0),
                Json.intVal(m, "max_uses", 0),
                Json.intVal(m, "cooldown", 0),
                Json.dblVal(m, "requires_hp_below_pct", 0.0),
                Json.str(m, "abom_form", "any"),
                Json.boolVal(m, "is_transform", false),
                Json.boolVal(m, "is_finale", false),
                Json.dblVal(m, "finale_buff", 0.0),
                Json.str(m, "description_en", ""),
                Json.str(m, "description_zh", "")
        );
    }

    @SuppressWarnings("unchecked")
    private static HeroDef parseHero(Map<String, Object> m) {
        List<Object> skRaw = Json.listVal(m, "skills");
        SkillDef[] skills = new SkillDef[skRaw.size()];
        for (int i = 0; i < skRaw.size(); i++) {
            skills[i] = parseSkill((Map<String, Object>) skRaw.get(i));
        }
        return new HeroDef(
                Json.str(m, "id", ""),
                Json.str(m, "name_en", ""),
                Json.str(m, "name_zh", ""),
                Json.intVal(m, "max_hp", 30),
                Json.intVal(m, "dodge", 0),
                Json.dblVal(m, "prot", 0.0),
                Json.intVal(m, "dmg_min", 5),
                Json.intVal(m, "dmg_max", 10),
                Json.dblVal(m, "crit", 5.0),
                Json.intVal(m, "move_forward", 1),
                Json.intVal(m, "move_backward", 1),
                Json.intVal(m, "deathblow_res", 75),
                Json.intVal(m, "stun_res", 40),
                Json.intVal(m, "blight_res", 40),
                Json.intVal(m, "bleed_res", 40),
                Json.intVal(m, "debuff_res", 40),
                Json.intVal(m, "move_res", 40),
                Json.intVal(m, "stress_res", 0),
                Json.dblVal(m, "riposte_dmg_mod", -0.25),
                Json.intVal(m, "riposte_acc", 90),
                Json.dblVal(m, "riposte_crit", 5.0),
                Json.intVal(m, "beast_dmg_min", 0),
                Json.intVal(m, "beast_dmg_max", 0),
                Json.dblVal(m, "beast_crit", 0.0),
                skills,
                parseStringArray(m, "default_skills")
        );
    }

    private static TrinketDef parseTrinket(Map<String, Object> m) {
        return new TrinketDef(
                Json.str(m, "id", ""),
                Json.str(m, "name_en", ""),
                Json.str(m, "name_zh", ""),
                Json.str(m, "hero_class", ""),
                Json.str(m, "tier", "arena"),
                parseBuffArray(m, "buffs"),
                Json.intVal(m, "on_hit_stress", 0),
                parseDotSpec(m, "on_hit_bleed"),
                parseDotSpec(m, "on_hit_blight"),
                Json.intVal(m, "start_stealth", 0),
                Json.intVal(m, "start_aegis", 0),
                Json.str(m, "description_en", ""),
                Json.str(m, "description_zh", "")
        );
    }

    private static TraitDef parseTrait(Map<String, Object> m) {
        return new TraitDef(
                Json.str(m, "id", ""),
                Json.str(m, "name_en", ""),
                Json.str(m, "name_zh", ""),
                Json.boolVal(m, "is_virtue", false),
                parseBuffArray(m, "buffs"),
                Json.intVal(m, "turn_stress_self", 0),
                Json.intVal(m, "turn_stress_allies", 0),
                Json.intVal(m, "turn_heal_self", 0),
                parseStringArray(m, "barks_en"),
                parseStringArray(m, "barks_zh")
        );
    }
}
