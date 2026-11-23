package com.circus.bot;

import com.circus.Config;
import com.circus.engine.*;
import com.circus.engine.Models.*;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** A small odd MLP on team differences. Its logit corrects the tactical evaluator. */
final class ArenaValue {
    static final int INPUT = 32, HIDDEN = 24;
    static final double SCALE = 2000;
    static final Path FILE = Config.BOT_DIR.resolve("neural-value-v1.bin");
    private record CachedModel(long mtimeNanos, long size, ArenaValue model) {}
    private static final ConcurrentHashMap<Path, CachedModel> CACHE = new ConcurrentHashMap<>();
    final double[][] weights = new double[HIDDEN][INPUT];
    final double[] output = new double[HIDDEN];

    static void encode(ArenaEngine e, int viewer, double[] x, EffectiveStats scratch) {
        encode(e, viewer, x, scratch, null);
    }

    static void encode(ArenaEngine e, int viewer, double[] x, EffectiveStats scratch, EffectiveStats[][] precomputed) {
        Arrays.fill(x, 0);
        for (int sideIdx = 0; sideIdx < 2; sideIdx++) {
            var side = e.state.teams[sideIdx];
            for (int ui = 0, un = side.size(); ui < un; ui++) {
                UnitState u = side.get(ui);
                if (!u.alive || u.isCorpse) continue;
                double sign = u.team == viewer ? 1 : -1;
                double hp = (double) u.hp / u.maxHp;
                x[0] += sign;
                x[1] += sign * hp;
                x[2] += sign * u.hp / 45;
                if (hp < .30) x[3] += sign;
                if (u.atDeathsDoor) x[4] += sign;
                x[5] += sign * u.stress / 150;
                if (u.stress >= 150) x[6] += sign;
                if (u.isAfflicted) x[7] += sign;
                if (!u.actedThisRound) x[8] += sign;
                if (u.stunned) x[9] += sign;
                if (u.dazed) x[10] += sign;
                if (u.guardedByUid != null) x[11] += sign;
                x[12] += sign * Math.min(2, u.aegisBlocks);
                if (u.stealthRounds > 0) x[13] += sign;
                if (u.markedRounds > 0) x[14] += sign;
                if (u.riposteRounds > 0) x[15] += sign;
                for (ActiveDot d : u.dots) {
                    int i = d.kind == Models.DOT_REGEN ? 16 : d.kind == Models.DOT_HORROR ? 17 : 18;
                    x[i] += sign * d.amount * Math.min(3, d.roundsLeft) / 20;
                }
                EffectiveStats st = (precomputed != null && ui < precomputed[sideIdx].length)
                        ? e.computeEffectiveStats(u, null, null, precomputed[sideIdx][ui])
                        : e.computeEffectiveStats(u, null, null, scratch);
                double[] v = st.values;
                x[19] += sign * v[StatKey.PROT];
                x[20] += sign * v[StatKey.DODGE] / 50;
                x[21] += sign * v[StatKey.ACC] / 30;
                x[22] += sign * v[StatKey.DEATHBLOW_RES] / 100;
                double damage = 0, heal = 0, stress = 0;
                int reach = 0;
                for (SkillDef s : u.equippedSkillDefs) {
                    if (!e.canUseSkill(u, s)) continue;
                    x[23] += sign * .25;
                    if (s.isAttack && "enemy".equals(s.targetTeam)) {
                        reach |= s.targetRankMask;
                        damage = Math.max(damage, (1 + s.dmgMod) * u.heroDef.dmgMax * (1 + v[StatKey.DMG_PCT]));
                    }
                    heal = Math.max(heal, s.healMax + s.healPct * 40);
                    stress = Math.max(stress, s.stressDmg * (s.isAoe ? 2 : 1));
                    if (s.stunChance > 0) x[24] += sign;
                    if (s.bleed != null || s.blight != null) x[25] += sign;
                }
                x[26] += sign * damage / 20;
                x[27] += sign * heal / 12;
                x[28] += sign * stress / 25;
                x[29] += sign * Integer.bitCount(reach) / 4;
                x[30] += sign * v[StatKey.STRESS_RES] / 50;
            }
        }
        x[31] = e.state.activeTeam == viewer ? 1 : -1;
    }

    double residual(double[] x, double[] hidden) {
        double sum = 0;
        for (int h = 0; h < HIDDEN; h++) {
            double dot = 0;
            for (int i = 0; i < INPUT; i++) dot += x[i] * weights[h][i];
            hidden[h] = Math.tanh(dot);
            sum += hidden[h] * output[h];
        }
        return sum;
    }

    static ArenaValue readCached(Path file) {
        Path key = file.toAbsolutePath().normalize();
        try {
            BasicFileAttributes attrs = Files.readAttributes(key, BasicFileAttributes.class);
            long mtime = attrs.lastModifiedTime().toInstant().toEpochMilli() * 1_000_000L
                    + (attrs.lastModifiedTime().toInstant().getNano() % 1_000_000L);
            long size = attrs.size();
            CachedModel cached = CACHE.get(key);
            if (cached != null && cached.mtimeNanos == mtime && cached.size == size) {
                return cached.model;
            }
            ArenaValue loaded = read(key);
            CACHE.put(key, new CachedModel(mtime, size, loaded));
            return loaded;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    static ArenaValue read(Path file) {
        ArenaValue model = new ArenaValue();
        try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file), 8192))) {
            if (in.readInt() != 0x41564e31) throw new IOException("Unknown network format");
            for (int h = 0; h < HIDDEN; h++) {
                model.output[h] = finite(in);
                for (int i = 0; i < INPUT; i++) model.weights[h][i] = finite(in);
            }
            if (in.read() != -1) throw new IOException("Unexpected trailing weights");
        } catch (IOException ex) { throw new UncheckedIOException(ex); }
        return model;
    }

    private static double finite(DataInputStream in) throws IOException {
        double w = in.readDouble();
        if (!Double.isFinite(w)) throw new IOException("Invalid weight");
        return w;
    }

    void save(Path file) throws IOException {
        Path tmp = Files.createTempFile(file.toAbsolutePath().getParent(), "arena-value-", ".tmp");
        try {
            try (var out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp), 8192))) {
                out.writeInt(0x41564e31);
                for (int h = 0; h < HIDDEN; h++) {
                    out.writeDouble(output[h]);
                    for (double w : weights[h]) out.writeDouble(w);
                }
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            CACHE.remove(file.toAbsolutePath().normalize());
        } finally { Files.deleteIfExists(tmp); }
    }
}
