package com.circus.bot;

import com.circus.engine.ArenaEngine;
import java.util.Arrays;

public final class UnitNeuralSearchBot extends NeuralSearchBot {
    private final UnitValue units = UnitValue.read();
    private final UnitValue.Row row = new UnitValue.Row();
    private final float[] hidden = new float[UnitValue.HIDDEN];

    // Multiple siblings of a search node normally leave most combatants unchanged.
    // Verify the complete encoded row before reusing a cached neural contribution.
    private final Entry[] cache = new Entry[2048];
    private final boolean cacheUnits;

    private static final class Entry {
        int[] indices = new int[64];
        float[] values = new float[64];
        int size;
        double score;
    }

    public UnitNeuralSearchBot(BotContext ctx) {
        super(ctx);
        cacheUnits = ctx.optionInt("cache_units", 1) != 0;
    }

    private double contribution() {
        if (!cacheUnits) return units.score(row, hidden);
        int hash = 1;
        for (int i = 0; i < row.n; i++)
            hash = 31 * (31 * hash + row.id[i]) + Float.floatToIntBits(row.v[i]);
        int slot = (hash ^ (hash >>> 16)) & (cache.length - 1);
        Entry entry = cache[slot];
        if (entry == null) {
            entry = new Entry();
            cache[slot] = entry;
        } else if (entry.size == row.n
                && Arrays.equals(row.id, 0, row.n, entry.indices, 0, row.n)
                && Arrays.equals(row.v, 0, row.n, entry.values, 0, row.n)) {
            return entry.score;
        }
        if (entry.indices.length < row.n) {
            entry.indices = new int[row.n];
            entry.values = new float[row.n];
        }
        System.arraycopy(row.id, 0, entry.indices, 0, row.n);
        System.arraycopy(row.v, 0, entry.values, 0, row.n);
        entry.size = row.n;
        entry.score = units.score(row, hidden);
        return entry.score;
    }

    @Override public double evaluateState(ArenaEngine e, int team) {
        double base = super.evaluateState(e,team);
        if (e.state.finished) return base;
        double residual=0;
        for (var side:e.state.teams) for (var unit:side) {
            UnitValue.encode(e,unit,row);
            if (row.n > 0) residual += (unit.team==team ? 1 : -1)*contribution();
        }
        return base + ArenaValue.SCALE*residual;
    }
}
