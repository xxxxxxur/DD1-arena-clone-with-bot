package com.circus.bot;

import com.circus.engine.*;
import com.circus.engine.Models.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** A shared sparse MLP evaluated once per occupied slot. Rows use the same vocabulary on both sides. */
final class UnitValue {
    static final int HIDDEN = 32;
    static final Path FILE = ArenaValue.FILE.resolveSibling("neural-units-v1.bin");
    private static final int RANK = 23, GUARD_FROM = 27, GUARD_TO = 31, DOT = 35;
    private static final int BUFF = DOT + 8, FIRST_ID = BUFF + 2 * StatKey.STAT_COUNT;
    static final class Catalog {
        final IdentityHashMap<HeroDef,Integer> heroes = new IdentityHashMap<>();
        final IdentityHashMap<SkillDef,Integer> skills = new IdentityHashMap<>();
        final IdentityHashMap<TrinketDef,Integer> items = new IdentityHashMap<>();
        final Map<String,Integer> traits = new HashMap<>();
        final List<String> schema = new ArrayList<>();
        int size = FIRST_ID;
        int allocate(String name, int count) {
            schema.add(name);
            int first = size;
            size += count;
            return first;
        }
        Catalog() {
            var d = GameData.get();
            for (String id : new TreeSet<>(d.heroes.keySet())) {
                var hero = d.heroes.get(id);
                heroes.put(hero, allocate("hero/" + id, 1));
                for (var s : hero.skills) skills.put(s, allocate(id + "/" + s.id, 4));
            }
            for (String id : new TreeSet<>(d.trinkets.keySet()))
                items.put(d.trinkets.get(id), allocate("item/" + id, 1));
            for (String id : new TreeSet<>(d.traits.keySet())) traits.put(id, allocate("trait/" + id, 1));
        }
    }
    static final Catalog C = new Catalog();

    static final class Row {
        int[] id = new int[64];
        float[] v = new float[64];
        int n;
        void add(int k, double value) {
            if (value == 0) return;
            if (n == id.length) {
                id = Arrays.copyOf(id, n * 2);
                v = Arrays.copyOf(v, n * 2);
            }
            id[n] = k;
            v[n++] = (float)value;
        }
    }

    static void encode(ArenaEngine e, UnitState u, Row r) {
        r.n = 0;
        if (!u.alive) return;
        r.add(RANK + u.rank - 1, 1);
        if (u.isCorpse) {
            r.add(0, 1);
            r.add(1, u.corpseRounds / 3.);
            r.add(2, u.hp / 40.);
            return;
        }
        r.add(C.heroes.get(u.heroDef), 1);
        for (var item : u.trinketDefs) r.add(C.items.get(item), 1);
        for (var s : u.equippedSkillDefs) {
            int k = C.skills.get(s);
            r.add(k, 1);
            r.add(k+1, e.canUseSkill(u,s) ? 1 : 0);
            r.add(k+2, u.skillCooldowns.getOrDefault(s.id,0) / 3.);
            if (s.maxUses > 0) r.add(k+3, (s.maxUses-u.skillUses.getOrDefault(s.id,0))/(double)s.maxUses);
        }
        if (u.traitId != null && C.traits.containsKey(u.traitId)) r.add(C.traits.get(u.traitId),1);
        r.add(2,u.hp / 40.);
        r.add(3,(double)u.hp / u.maxHp);
        r.add(4,u.stress / 150.);
        r.add(5,u.atDeathsDoor ? 1 : 0);
        r.add(6,u.actedThisRound ? 1 : 0);
        r.add(7,u.stunned ? 1 : 0);
        r.add(8,u.dazed ? 1 : 0);
        r.add(9,u.markedRounds / 3.);
        r.add(10,u.stealthRounds / 3.);
        r.add(11,u.riposteRounds / 3.);
        r.add(12,u.guardRounds / 3.);
        r.add(13,u.aegisBlocks / 2.);
        r.add(14,u.isBeast ? 1 : 0);
        r.add(15,u.finaleBonusPct);
        r.add(16,(double)e.state.roundNum / Math.max(1,e.state.maxRounds));
        for (int bit=0; bit<6; bit++) r.add(17+bit,(u.freshStatusMask >>> bit)&1);
        if (u.guardedByUid != null) {
            var g = e.findUnit(u.guardedByUid);
            if (g != null && g.rank >= 1 && g.rank <= 4) r.add(GUARD_FROM + g.rank - 1,1);
        }
        if (u.guardingUid != null) {
            var g = e.findUnit(u.guardingUid);
            if (g != null && g.rank >= 1 && g.rank <= 4) r.add(GUARD_TO + g.rank - 1,1);
        }
        for (var d : u.dots) {
            r.add(DOT+2*d.kind,d.amount/10.);
            r.add(DOT+2*d.kind+1,d.amount*d.roundsLeft/20.);
        }
        for (var b : u.buffs) {
            if (b.statId < 0) continue;
            double amount = Math.copySign(Math.log1p(Math.abs(b.value)),b.value);
            r.add(BUFF+2*b.statId,amount);
            r.add(BUFF+2*b.statId+1,amount * (b.roundsLeft < 0 ? 1 : Math.min(b.roundsLeft,8)/10.));
        }
    }

    final float[][] w = new float[C.size][HIDDEN];
    final float[] out = new float[HIDDEN];
    double score(Row r, float[] h) {
        Arrays.fill(h,0);
        for (int j=0; j<r.n; j++) {
            float[] weights = w[r.id[j]];
            for (int k=0; k<HIDDEN; k++) h[k] += weights[k]*r.v[j];
        }
        double score=0;
        for (int k=0; k<HIDDEN; k++) {
            h[k]=(float)Math.tanh(h[k]);
            score+=h[k]*out[k];
        }
        return score;
    }
    void write() throws IOException {
        Path tmp=Files.createTempFile(FILE.getParent(),"unit-value-",".tmp");
        try {
            try (var stream=new DataOutputStream(Files.newOutputStream(tmp))) {
                stream.writeInt(0x554e5631);
                stream.writeUTF(String.join("|",C.schema));
                for (float[] a:w) for(float v:a) stream.writeFloat(v);
                for (float v:out) stream.writeFloat(v);
            }
            Files.move(tmp,FILE,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally {Files.deleteIfExists(tmp);}
    }
    static UnitValue read() {
        var net=new UnitValue();
        try(var in=new DataInputStream(Files.newInputStream(FILE))) {
            if(in.readInt()!=0x554e5631 || !in.readUTF().equals(String.join("|",C.schema)))
                throw new IOException("Incompatible unit vocabulary");
            for(float[] a:net.w) for(int k=0;k<a.length;k++) a[k]=finite(in);
            for(int k=0;k<HIDDEN;k++) net.out[k]=finite(in);
            if(in.read()!=-1) throw new IOException("Unexpected trailing weights");
        } catch(IOException ex){throw new UncheckedIOException(ex);}
        return net;
    }
    private static float finite(DataInputStream in) throws IOException {
        float value=in.readFloat();
        if (!Float.isFinite(value)) throw new IOException("Invalid unit weight");
        return value;
    }
}
