package com.circus.bot;

import com.circus.engine.*;
import com.circus.engine.Models.*;

/** Attrition specialist: prices future mitigation, setup and sustainable pressure at search leaves. */
public final class FortressBot extends CircusSearchBot {
    private final EffectiveStats strategicStats = new EffectiveStats();
    private final double mitigationWeight;
    private final double pressureWeight;
    private final double setupWeight;
    private final double bulwarkPriority;
    private final double guardBias;
    private final double controlBonus;

    public FortressBot(BotContext ctx) {
        super(ctx.name(), ctx.effectiveTimeBudget(.25), ctx.optionString("profile", "iron_wall"),
                ctx.seed(), ctx.presetId(), ctx.optionInt("max_depth", 10),
                ctx.optionInt("root_width", 10), ctx.optionInt("branch_width", 4));
        if (ctx.teamConfig() != null) setFixedTeam(ctx.teamConfig());
        mitigationWeight = ctx.optionDouble("mitigation_weight", 1);
        pressureWeight = ctx.optionDouble("pressure_weight", 1);
        setupWeight = ctx.optionDouble("setup_weight", 1);
        bulwarkPriority = ctx.optionDouble("bulwark_priority", 120);
        guardBias = ctx.optionDouble("guard_bias", -100);
        controlBonus = ctx.optionDouble("control_bonus", 0);
    }

    @Override public boolean requiresUnitObservations() { return false; }

    private double strategicValue(ArenaEngine engine, int team) {
        double value = 0;
        for (UnitState u : engine.state.teams[team]) {
            if (!u.alive || u.isCorpse) continue;
            double[] s = engine.computeEffectiveStats(u, null, "", strategicStats).values;
            // Medium-term durability makes party setup visible even at shallow leaves.
            value += mitigationWeight * ((s[StatKey.DODGE] - u.heroDef.dodge) * 6.0
                    + (s[StatKey.PROT] - u.heroDef.prot) * 500.0
                    + (s[StatKey.STRESS_RES] - u.heroDef.stressRes) * 2.0);
            value += s[StatKey.ACC] * 2.4 + s[StatKey.DMG_PCT] * 220.0;
            value += s[StatKey.STUN_CHANCE] * .8;
            int incomingDots = 0, regen = 0;
            for (ActiveDot dot : u.dots) {
                int total = dot.amount * Math.min(3, dot.roundsLeft);
                if (dot.kind == Models.DOT_REGEN) regen += total;
                else if (dot.kind != Models.DOT_HORROR) incomingDots += total;
            }
            // Do not count unlimited overhealing as an asset.
            value -= Math.max(0, regen - (u.maxHp - u.hp + incomingDots + 8)) * 8.5;
            // Stress is a win condition for attrition teams, well before the final heart attack.
            value -= pressureWeight * (u.stress * 1.1 + (u.isAfflicted ? 100 : 0)
                    + Math.max(0, u.stress - 150) * 2.2);
        }
        return value;
    }

    @Override public double evaluateState(ArenaEngine engine, int myTeam) {
        double score = super.evaluateState(engine, myTeam);
        if (engine.state.finished) return score;
        return score + strategicValue(engine, myTeam) - strategicValue(engine, 1 - myTeam);
    }

    @Override protected double scoreActionEv(ArenaEngine engine, Action action, int team,
            int threats, double maxThreat, boolean allyDd, boolean enemyCorpse) {
        double score = super.scoreActionEv(engine, action, team, threats, maxThreat, allyDd, enemyCorpse);
        if (!"SKILL".equals(action.kind)) return score;
        UnitState actor = engine.findUnit(action.actorUid);
        SkillDef skill = actor.heroDef.getSkill(action.skillId);
        int living = engine.countLivingHeroes(team);
        if ("bolster".equals(skill.id)) score += living * 145.0 * setupWeight;
        if ("bulwark_of_faith".equals(skill.id)) score += living * bulwarkPriority * setupWeight;
        if ("toxin_trickery".equals(skill.id)) score += engine.state.roundNum <= 3 ? 170 : 30;
        if ("bellow".equals(skill.id)) {
            for (UnitState enemy : engine.state.teams[1 - team]) {
                if (!enemy.alive || enemy.isCorpse) continue;
                double damagePenalty = engine.buffSum(enemy, StatKey.DMG_PCT);
                if (damagePenalty > -.6) score += enemy.actedThisRound ? 30 : 65;
            }
        }
        if (skill.regen != null) {
            for (String uid : action.targetUids) {
                UnitState target = engine.findUnit(uid);
                int existing = 0;
                for (ActiveDot dot : target.dots) if (dot.kind == Models.DOT_REGEN)
                    existing += dot.amount * Math.min(3, dot.roundsLeft);
                if (target.hp > target.maxHp * .75 && existing >= 12) score -= 320;
            }
        }
        // Guard is insurance, not an opening move when nobody needs it.
        if (skill.guardRounds > 0 && !allyDd && maxThreat < .22) score += guardBias;
        if (skill.stunChance > 0 && controlBonus != 0) {
            for (String uid : action.targetUids) {
                UnitState target = engine.findUnit(uid);
                if (target.alive && !target.isCorpse && !target.stunned && !target.actedThisRound) score += controlBonus;
            }
        }
        return score;
    }

    @Override protected long searchStateHash(ArenaEngine engine) {
        long h = engine.computeStateHash() ^ (engine.state.turnCount * 0x9E3779B97F4A7C15L);
        for (var team : engine.state.teams) for (UnitState u : team) {
            h = Long.rotateLeft(h, 11) ^ u.uid.hashCode();
            h = h * 31 + u.skillUses.hashCode();
            h = h * 31 + u.skillCooldowns.hashCode();
            h = h * 31 + (u.guardingUid == null ? 0 : u.guardingUid.hashCode());
            h = h * 31 + (u.guardedByUid == null ? 0 : u.guardedByUid.hashCode());
            h = h * 31 + (u.traitId == null ? 0 : u.traitId.hashCode());
            h = h * 31 + u.corpseRounds + u.freshStatusMask * 17L;
            for (ActiveBuff b : u.buffs) {
                h = Long.rotateLeft(h, 9) ^ Double.doubleToLongBits(b.value);
                h = h * 31 + b.statId + b.condBit * 17L + b.roundsLeft * 127L + (b.freshThisTurn ? 1 : 0);
            }
            for (ActiveDot d : u.dots) h = Long.rotateLeft(h, 7) ^ (d.kind + d.amount * 31L + d.roundsLeft * 997L);
        }
        return h;
    }
}
