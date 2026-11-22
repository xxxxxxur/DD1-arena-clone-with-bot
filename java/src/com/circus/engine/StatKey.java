package com.circus.engine;

import java.util.HashMap;
import java.util.Map;

/**
 * Compile-time integer IDs for all combat stats and conditional buff bitmasks.
 * Eliminates all HashMap/String allocations in hot combat & search loops.
 */
public final class StatKey {
    // Standard 27 stats always present in serialized get_effective_stats()
    public static final int DODGE = 0;
    public static final int PROT = 1;
    public static final int ACC = 2;
    public static final int CRIT = 3;
    public static final int DMG_PCT = 4;
    public static final int DEATHBLOW_RES = 5;
    public static final int DEATHBLOW_CHANCE = 6;
    public static final int STUN_RES = 7;
    public static final int BLEED_RES = 8;
    public static final int BLIGHT_RES = 9;
    public static final int DEBUFF_RES = 10;
    public static final int MOVE_RES = 11;
    public static final int STRESS_RES = 12;
    public static final int STRESS_DMG_PCT = 13;
    public static final int HEAL_DONE_PCT = 14;
    public static final int HEAL_RECV_PCT = 15;
    public static final int BLEED_CHANCE = 16;
    public static final int BLIGHT_CHANCE = 17;
    public static final int STUN_CHANCE = 18;
    public static final int DEBUFF_CHANCE = 19;
    public static final int MOVE_CHANCE = 20;
    public static final int VIRTUE_CHANCE = 21;
    public static final int DMG_VS_MARKED_PCT = 22;
    public static final int DMG_VS_BLEED_PCT = 23;
    public static final int DMG_VS_BLIGHT_PCT = 24;
    public static final int DMG_VS_STUNNED_PCT = 25;
    public static final int STANDARD_COUNT = 26;

    // Extended / special stats from trinkets & skills
    public static final int ARMOR_PIERCE = 26;
    public static final int BLIGHT_DURATION = 27;
    public static final int CRITS_RECEIVED = 28;
    public static final int DAMAGE_TAKEN_PCT = 29;
    public static final int GUARD_DURATION = 30;
    public static final int HEAL_FLAT = 31;
    public static final int HEART_HEAL_PCT = 32;
    public static final int HORROR_DURATION = 33;
    public static final int IGNORE_GUARD = 34;
    public static final int IGNORE_STEALTH = 35;
    public static final int MAX_HP_FLAT = 36;
    public static final int MAX_HP_PCT = 37;
    public static final int RAKE_DMG_PCT = 38;
    public static final int REGEN_DURATION = 39;
    public static final int STRESS_HEAL_PCT = 40;
    public static final int FINALE_DAMAGE = 41;
    public static final int FINALE_CRIT = 42;
    public static final int DAZE = 43;
    public static final int STAT_COUNT = 44;

    public static final String[] NAMES = new String[STAT_COUNT];
    private static final Map<String, Integer> NAME_TO_ID = new HashMap<>(64);

    // Condition bitmasks (19 conditions -> fits cleanly in 32-bit int!)
    public static final int COND_NONE = 0;
    public static final int COND_MELEE = 1 << 0;
    public static final int COND_RANGED = 1 << 1;
    public static final int COND_RIPOSTE = 1 << 2;
    public static final int COND_STEALTHED = 1 << 3;
    public static final int COND_GUARDING = 1 << 4;
    public static final int COND_GUARDED = 1 << 5;
    public static final int COND_DEATHS_DOOR = 1 << 6;
    public static final int COND_LOW20 = 1 << 7;
    public static final int COND_RANK1 = 1 << 8;
    public static final int COND_AFFLICTED = 1 << 9;
    public static final int COND_BLEEDING = 1 << 10;
    public static final int COND_TARGET_MARKED = 1 << 11;
    public static final int COND_TARGET_AFFLICTED = 1 << 12;
    public static final int COND_TARGET_LOW40 = 1 << 13;
    public static final int COND_TARGET_LOW35 = 1 << 14;
    public static final int COND_TARGET_BLEEDING = 1 << 15;
    public static final int COND_TARGET_BLIGHTED = 1 << 16;
    public static final int COND_FIRST = 1 << 17;
    public static final int COND_LAST = 1 << 18;
    public static final int COND_UNKNOWN = 1 << 30;

    private static final Map<String, Integer> COND_TO_BIT = new HashMap<>(32);

    static {
        reg(DODGE, "dodge");
        reg(PROT, "prot");
        reg(ACC, "acc");
        reg(CRIT, "crit");
        reg(DMG_PCT, "dmg_pct");
        reg(DEATHBLOW_RES, "deathblow_res");
        reg(DEATHBLOW_CHANCE, "deathblow_chance");
        reg(STUN_RES, "stun_res");
        reg(BLEED_RES, "bleed_res");
        reg(BLIGHT_RES, "blight_res");
        reg(DEBUFF_RES, "debuff_res");
        reg(MOVE_RES, "move_res");
        reg(STRESS_RES, "stress_res");
        reg(STRESS_DMG_PCT, "stress_dmg_pct");
        reg(HEAL_DONE_PCT, "heal_done_pct");
        reg(HEAL_RECV_PCT, "heal_recv_pct");
        reg(BLEED_CHANCE, "bleed_chance");
        reg(BLIGHT_CHANCE, "blight_chance");
        reg(STUN_CHANCE, "stun_chance");
        reg(DEBUFF_CHANCE, "debuff_chance");
        reg(MOVE_CHANCE, "move_chance");
        reg(VIRTUE_CHANCE, "virtue_chance");
        reg(DMG_VS_MARKED_PCT, "dmg_vs_marked_pct");
        reg(DMG_VS_BLEED_PCT, "dmg_vs_bleed_pct");
        reg(DMG_VS_BLIGHT_PCT, "dmg_vs_blight_pct");
        reg(DMG_VS_STUNNED_PCT, "dmg_vs_stunned_pct");

        reg(ARMOR_PIERCE, "armor_pierce");
        reg(BLIGHT_DURATION, "blight_duration");
        reg(CRITS_RECEIVED, "crits_received");
        reg(DAMAGE_TAKEN_PCT, "damage_taken_pct");
        reg(GUARD_DURATION, "guard_duration");
        reg(HEAL_FLAT, "heal_flat");
        reg(HEART_HEAL_PCT, "heart_heal_pct");
        reg(HORROR_DURATION, "horror_duration");
        reg(IGNORE_GUARD, "ignore_guard");
        reg(IGNORE_STEALTH, "ignore_stealth");
        reg(MAX_HP_FLAT, "max_hp_flat");
        reg(MAX_HP_PCT, "max_hp_pct");
        reg(RAKE_DMG_PCT, "rake_dmg_pct");
        reg(REGEN_DURATION, "regen_duration");
        reg(STRESS_HEAL_PCT, "stress_heal_pct");
        reg(FINALE_DAMAGE, "finale_damage");
        reg(FINALE_CRIT, "finale_crit");
        reg(DAZE, "daze");

        COND_TO_BIT.put("melee", COND_MELEE);
        COND_TO_BIT.put("ranged", COND_RANGED);
        COND_TO_BIT.put("riposte", COND_RIPOSTE);
        COND_TO_BIT.put("stealthed", COND_STEALTHED);
        COND_TO_BIT.put("guarding", COND_GUARDING);
        COND_TO_BIT.put("guarded", COND_GUARDED);
        COND_TO_BIT.put("deaths_door", COND_DEATHS_DOOR);
        COND_TO_BIT.put("low20", COND_LOW20);
        COND_TO_BIT.put("rank1", COND_RANK1);
        COND_TO_BIT.put("afflicted", COND_AFFLICTED);
        COND_TO_BIT.put("bleeding", COND_BLEEDING);
        COND_TO_BIT.put("target_marked", COND_TARGET_MARKED);
        COND_TO_BIT.put("target_afflicted", COND_TARGET_AFFLICTED);
        COND_TO_BIT.put("target_low40", COND_TARGET_LOW40);
        COND_TO_BIT.put("target_low35", COND_TARGET_LOW35);
        COND_TO_BIT.put("target_bleeding", COND_TARGET_BLEEDING);
        COND_TO_BIT.put("target_blighted", COND_TARGET_BLIGHTED);
        COND_TO_BIT.put("first", COND_FIRST);
        COND_TO_BIT.put("last", COND_LAST);
    }

    private static void reg(int id, String name) {
        NAMES[id] = name;
        NAME_TO_ID.put(name, id);
    }

    public static int parseStatId(String rawStat) {
        if (rawStat == null || rawStat.isEmpty()) return -1;
        int at = rawStat.indexOf('@');
        String base = (at >= 0) ? rawStat.substring(0, at) : rawStat;
        Integer id = NAME_TO_ID.get(base);
        return id != null ? id : -1;
    }

    public static int parseCondBit(String rawStat) {
        if (rawStat == null) return COND_NONE;
        int at = rawStat.indexOf('@');
        if (at < 0) return COND_NONE;
        String cond = rawStat.substring(at + 1);
        if (cond.isEmpty()) return COND_NONE;
        Integer bit = COND_TO_BIT.get(cond);
        return bit != null ? bit : COND_UNKNOWN;
    }

    public static int attackTypeToBit(String attackType) {
        if (attackType == null || attackType.isEmpty()) return COND_NONE;
        return switch (attackType) {
            case "melee" -> COND_MELEE;
            case "ranged" -> COND_RANGED;
            case "riposte" -> COND_RIPOSTE;
            default -> COND_NONE;
        };
    }

    private StatKey() {}
}
