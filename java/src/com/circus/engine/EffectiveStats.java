package com.circus.engine;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reusable primitive-array container for computed unit effective stats.
 * Avoids HashMap allocation during combat resolution and tree search.
 */
public final class EffectiveStats {
    public final double[] values = new double[StatKey.STAT_COUNT];
    public long presentMask = 0L;
    public boolean isCorpse = false;

    public void reset() {
        Arrays.fill(values, 0.0);
        presentMask = 0L;
        isCorpse = false;
    }

    public double get(int statId) {
        return values[statId];
    }

    public void set(int statId, double val) {
        values[statId] = val;
        presentMask |= (1L << statId);
    }

    public void add(int statId, double delta) {
        values[statId] += delta;
        presentMask |= (1L << statId);
    }

    public Map<String, Object> toRoundedMap() {
        Map<String, Object> out = new LinkedHashMap<>(32);
        if (isCorpse) {
            int[] corpseKeys = {
                    StatKey.DODGE, StatKey.PROT, StatKey.ACC, StatKey.CRIT, StatKey.DMG_PCT,
                    StatKey.DEATHBLOW_RES, StatKey.DEATHBLOW_CHANCE, StatKey.STUN_RES,
                    StatKey.BLEED_RES, StatKey.BLIGHT_RES, StatKey.DEBUFF_RES,
                    StatKey.MOVE_RES, StatKey.STRESS_RES
            };
            for (int k : corpseKeys) {
                out.put(StatKey.NAMES[k], round3(values[k]));
            }
            return out;
        }
        for (int i = 0; i < StatKey.STANDARD_COUNT; i++) {
            out.put(StatKey.NAMES[i], round3(values[i]));
        }
        for (int i = StatKey.STANDARD_COUNT; i < StatKey.STAT_COUNT; i++) {
            if ((presentMask & (1L << i)) != 0L) {
                out.put(StatKey.NAMES[i], round3(values[i]));
            }
        }
        return out;
    }

    private static double round3(double v) {
        return Math.rint(v * 1000.0) / 1000.0;
    }
}
