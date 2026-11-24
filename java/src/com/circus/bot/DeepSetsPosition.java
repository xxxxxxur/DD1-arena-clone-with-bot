package com.circus.bot;

import com.circus.engine.*;
import com.circus.engine.Models.UnitState;
import java.util.Arrays;

/** Rows are rank-indexed. The first feature is the live-unit mask. */
final class DeepSetsPosition {
    static final int D = 39, G = 6;
    final float[][] row = new float[8][D];
    final float[] global = new float[G];
    private final EffectiveStats stats = new EffectiveStats();
    private final int[] alive = new int[2], doors = new int[2], ready = new int[2], corpses = new int[2];
    private final int[] aliveMask = new int[2], stealthMask = new int[2];

    void encode(ArenaEngine e) {
        Arrays.fill(alive, 0);
        Arrays.fill(doors, 0);
        Arrays.fill(ready, 0);
        Arrays.fill(corpses, 0);
        aliveMask[0] = aliveMask[1] = 0;
        stealthMask[0] = stealthMask[1] = 0;
        for (var x : row) Arrays.fill(x, 0);
        for (int side = 0; side < 2; side++) {
            var units = e.state.teams[side];
            for (int i = 0, n = units.size(); i < n; i++) {
                var u = units.get(i);
                if (u.isCorpse && u.alive) corpses[side]++;
                if (!u.alive || u.isCorpse) continue;
                int rankBit = 1 << u.rank;
                aliveMask[side] |= rankBit;
                if (u.stealthRounds > 0) stealthMask[side] |= rankBit;
                var x = row[4 * side + u.rank - 1];
                alive[side]++;
                if (u.atDeathsDoor) doors[side]++;
                if (!u.actedThisRound && !u.stunned) ready[side]++;
                var v = e.computeEffectiveStats(u, null, "", stats).values;
                x[0] = 1;
                x[1] = (float)u.hp / u.maxHp;
                x[2] = u.hp / 45f;
                x[3] = flag(u.atDeathsDoor);
                x[4] = (float)v[StatKey.DEATHBLOW_RES] / 100;
                x[5] = u.stress / 200f;
                x[6] = flag(u.stress >= 150);
                x[7] = flag(u.isAfflicted);
                x[8] = flag(u.isVirtuous);
                x[9] = flag(u.actedThisRound);
                x[10] = flag(u.stunned);
                x[11] = flag(u.dazed);
                x[12] = flag(u.guardedByUid != null);
                x[13] = flag(u.guardingUid != null);
                x[14] = u.aegisBlocks;
                x[15] = flag(u.stealthRounds > 0);
                x[16] = flag(u.riposteRounds > 0);
                x[17] = flag(u.markedRounds > 0);
                x[18] = u.rank / 4f;
                x[19] = (float)v[StatKey.PROT];
                x[20] = (float)v[StatKey.DODGE] / 50;
                x[21] = (float)v[StatKey.ACC] / 30;
                x[22] = (float)v[StatKey.DMG_PCT];
                x[23] = (float)v[StatKey.CRIT] / 30;
                x[24] = (float)v[StatKey.STRESS_RES] / 50;
                x[25] = (float)v[StatKey.STUN_RES] / 100;
                x[38] = (float)v[StatKey.DEATHBLOW_CHANCE] / 100;
                for (int j = 0; j < u.dots.size(); j++) {
                    var dot = u.dots.get(j);
                    if (dot.kind == Models.DOT_REGEN) x[35] += dot.amount / 10f;
                    else if (dot.kind != Models.DOT_HORROR) x[35] -= dot.amount / 10f;
                }
                x[37] = (float)v[StatKey.HEAL_DONE_PCT];
            }
        }
        for (int side = 0; side < 2; side++) {
            var units = e.state.teams[side];
            for (int i = 0; i < units.size(); i++) {
                var u = units.get(i);
                if (u.alive && !u.isCorpse) capabilities(e, u);
            }
        }
        global[0] = e.state.activeTeam == 0 ? 1 : -1;
        global[1] = (alive[0] - alive[1]) / 4f;
        global[2] = flag(doors[0] > 0 && ready[1] > 0);
        global[3] = flag(doors[1] > 0 && ready[0] > 0);
        global[4] = (float)e.state.roundNum / e.state.maxRounds;
        global[5] = (corpses[0] - corpses[1]) / 4f;
    }

    private static float flag(boolean b) { return b ? 1 : 0; }

    private void capabilities(ArenaEngine e, UnitState u) {
        var x = row[4 * u.team + u.rank - 1];
        var hero = u.heroDef;
        double damage = u.isBeast ? (hero.beastDmgMin + hero.beastDmgMax) * .5 : (hero.dmgMin + hero.dmgMax) * .5;
        int myRankBit = 1 << u.rank;
        boolean canActNow = x[9] + x[10] == 0;
        double accBase = x[21] * 30;
        double dmgBase = 1 + x[22];
        float healMult = 1 + x[37];
        var skills = u.equippedSkillDefs;
        for (int si = 0, sn = skills.length; si < sn; si++) {
            var s = skills[si];
            if (!e.canUseSkill(u, s)) continue;
            if (s.riposteRounds > 0) x[34] = 1;
            boolean enemy = "enemy".equals(s.targetTeam);
            int side = enemy ? 1 - u.team : u.team;
            int mask = s.targetRankMask & aliveMask[side];
            if (mask == 0) continue;
            if (!enemy) {
                if ("self".equals(s.targetTeam)) mask &= myRankBit;
                else if ("ally_other".equals(s.targetTeam)) mask &= ~myRankBit;
                if (mask == 0) continue;
                float heal = (float)(((s.healMin + s.healMax) * .5 + s.healPct * 40) * healMult);
                if (heal > 0 && canActNow) x[30] = 1;
                if (heal / 12 > x[31]) x[31] = heal / 12;
                if (s.guardRounds > 0 || s.guardMeRounds > 0) x[32] = 1;
                continue;
            }
            if (!s.isAoe && !s.ignoresStealth) {
                mask &= ~stealthMask[side];
                if (mask == 0) continue;
            }
            float stressVal = (s.stressDmg + (s.horror == null ? 0 : s.horror.amount * 2)) / 30f;
            if (stressVal > x[28]) x[28] = stressVal;
            float stunVal = s.stunChance / 100f;
            if (stunVal > x[29]) x[29] = stunVal;
            if (s.breaksGuard) x[33] = 1;
            float dotVal = ((s.bleed == null ? 0 : s.bleed.amount) + (s.blight == null ? 0 : s.blight.amount)) / 8f;
            if (dotVal > x[36]) x[36] = dotVal;
            if (s.dmgMod > -1 && s.isAttack) {
                double skillAcc = s.acc + accBase;
                double skillDmg = dmgBase + s.dmgMod;
                float dbScore = 1 + (float)s.deathblowBonus / 100 + x[38];
                int baseIdx = 4 * side - 1;
                while (mask != 0) {
                    int rank = Integer.numberOfTrailingZeros(mask);
                    mask &= mask - 1;
                    var target = row[baseIdx + rank];
                    double hit = ArenaEngine.computeHitChance(skillAcc, target[20] * 50);
                    double expected = damage * (skillDmg + target[17] * s.bonusDmgVsMarked)
                            * (s.armorPiercing ? 1 : 1 - target[19]) * hit / 100;
                    float expNorm = (float)expected / 18;
                    if (expNorm > x[26]) x[26] = expNorm;
                    if ((target[3] > 0 || target[1] < .35) && dbScore > x[27]) x[27] = dbScore;
                }
            }
        }
    }
}
