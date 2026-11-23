package com.circus.bot;

import com.circus.engine.*;
import com.circus.engine.Models.*;
import java.nio.file.Path;
import java.util.Arrays;

/** Additional win-logit features describing what a team's current loadout can do. */
final class TacticalFeatures {
    static final Path FILE = ArenaValue.FILE.resolveSibling("neural-tactics-v1.bin");

    static final Path BALANCED = FILE.resolveSibling("neural-tactics-balanced-v1.bin");

    static void encode(ArenaEngine e, int viewer, double[] x, EffectiveStats stats) {
        encode(e, viewer, x, stats, null);
    }

    static void encode(ArenaEngine e, int viewer, double[] x, EffectiveStats stats, EffectiveStats[][] precomputed) {
        Arrays.fill(x, 0);
        for (int side = 0; side < 2; side++) {
            int occupied = 0, vulnerable = 0;
            var enemies = e.state.teams[1 - side];
            for (int ei = 0, en = enemies.size(); ei < en; ei++) {
                var enemy = enemies.get(ei);
                if (enemy.alive && !enemy.isCorpse) {
                    occupied |= 1 << enemy.rank;
                    if (enemy.atDeathsDoor || enemy.hp < enemy.maxHp * .35) vulnerable |= 1 << enemy.rank;
                }
            }
            double sign = side == viewer ? 1 : -1;
            double bestHeal = 0, bestHit = 0, lowestHp = 1;
            var teamUnits = e.state.teams[side];
            for (int ui = 0, un = teamUnits.size(); ui < un; ui++) {
                var u = teamUnits.get(ui);
                if (!u.alive || u.isCorpse) continue;
                double[] v = (precomputed != null && ui < precomputed[side].length)
                        ? precomputed[side][ui].values
                        : e.computeEffectiveStats(u, null, "", stats).values;
                double heal = 0, hit = 0, pressure = 0, dot = 0, finish = 0, stun = 0;
                int ranks = 0;
                for (var s : u.equippedSkillDefs) {
                    if (!e.canUseSkill(u, s)) continue;
                    if ("ally".equals(s.targetTeam) || "ally_other".equals(s.targetTeam))
                        heal = Math.max(heal, ((s.healMax + s.healMin) * .5 + s.healPct * 40
                                + (s.regen == null ? 0 : 2 * s.regen[0])) * (1 + v[StatKey.HEAL_DONE_PCT]));
                    if ((s.targetRankMask & occupied) == 0 || !"enemy".equals(s.targetTeam)) continue;
                    int targets = s.isAoe ? Integer.bitCount(s.targetRankMask & occupied) : 1;
                    ranks |= s.targetRankMask;
                    hit = Math.max(hit, Math.max(0, 1 + s.dmgMod + v[StatKey.DMG_PCT]) *
                            (u.isBeast ? u.heroDef.beastDmgMax : u.heroDef.dmgMax) * Math.sqrt(targets));
                    pressure = Math.max(pressure, targets * (s.stressDmg + (s.horror == null ? 0 : 2*s.horror.amount))
                            * (1 + v[StatKey.STRESS_DMG_PCT]));
                    dot = Math.max(dot, targets * ((s.bleed == null ? 0 : s.bleed.amount)
                            + (s.blight == null ? 0 : s.blight.amount)));
                    if (s.dmgMod > -1 && (s.targetRankMask & vulnerable) != 0)
                        finish = Math.max(finish, 1 + (s.deathblowBonus + v[StatKey.DEATHBLOW_CHANCE])/50);
                    stun = Math.max(stun, s.stunChance + (s.stunChance > 0 ? v[StatKey.STUN_CHANCE] : 0));
                    if (s.isFinale) x[0] += sign;
                }
                x[1] += sign * heal / 12;
                x[2] += sign * hit / 18;
                x[3] += sign * pressure / 30;
                x[4] += sign * dot / 8;
                x[5] += sign * finish;
                x[6] += sign * stun / 120;
                x[7] += sign * Integer.bitCount(ranks & occupied) / 4;
                if (hit < 2 && heal < 1 && pressure < 1) x[8] += sign;
                if (heal > 0 && !u.actedThisRound && !u.stunned) x[9] += sign;
                bestHit = Math.max(bestHit, hit);
                bestHeal = Math.max(bestHeal, heal);
                double hp = (double)u.hp/u.maxHp;
                lowestHp = Math.min(lowestHp, hp);
                x[10] += sign * (hp * hit / 18);
                x[11] += sign * (hp * heal / 12);
                x[12] += sign * Math.max(0, u.stress - 100) / 80;
                x[13] += sign * (u.atDeathsDoor ? v[StatKey.DEATHBLOW_RES]/100 : 0);
                x[14] += sign * v[StatKey.CRIT]/30;
                x[15] += sign * v[StatKey.DMG_PCT];
                x[16] += sign * v[StatKey.STUN_RES]/100;
                x[17] += sign * v[StatKey.MOVE_RES]/100;
                x[18] += sign * v[StatKey.HEAL_RECV_PCT];
                x[19] += sign * v[StatKey.DEATHBLOW_CHANCE]/40;
                int damage = 0, regen = 0;
                for (var d : u.dots) {
                    if (d.kind == Models.DOT_REGEN) regen += d.amount;
                    else if (d.kind != Models.DOT_HORROR) damage += d.amount;
                }
                x[20] += sign * Math.min(u.hp, damage) / 10;
                x[21] += sign * Math.min(u.maxHp-u.hp, regen) / 10;
                if (u.guardedByUid != null && hp < .35) x[22] += sign;
                if (u.isVirtuous) x[23] += sign;
                if (u.isAfflicted && !u.actedThisRound) x[24] += sign;
                x[25] += sign * hp * (v[StatKey.PROT] + v[StatKey.DODGE]/100);
                x[26] += sign * (hp < .20 ? 1 : 0);
                x[27] += sign * Math.min(1, e.state.roundNum / 40.) * hp;
            }
            x[28] += sign * bestHit / 18;
            x[29] += sign * bestHeal / 12;
            x[30] += sign * lowestHp;
            x[31] += sign * bestHeal * (1-lowestHp) / 12;
        }
    }
}
