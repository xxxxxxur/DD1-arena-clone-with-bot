package com.circus.bot;

import com.circus.engine.ArenaEngine;
import com.circus.engine.GameData;
import com.circus.engine.Models.Action;
import com.circus.engine.Models.HeroDef;
import com.circus.engine.Models.Observation;
import com.circus.engine.Models.SkillDef;
import com.circus.util.Json;

import java.util.*;

/**
 * Tactical hybrid bot: uses high-speed {@link CircusSearchBot} when {@code engine != null},
 * and falls back to an Observation-only tactical heuristic when {@code engine == null}.
 */
public class TacticalBot extends AbstractBot {
    private final SplittableRandom rng;
    private final double stressWeight;
    private final CircusSearchBot searchBot;

    public TacticalBot(String name, Long seed, String presetId, double stressWeight, double timeBudgetSec, String profile) {
        super(name != null ? name : "TacticalBot", presetId);
        this.rng = (seed != null) ? new SplittableRandom(seed) : new SplittableRandom();
        this.stressWeight = stressWeight;
        this.searchBot = new CircusSearchBot(this.name, timeBudgetSec, profile, seed, presetId);
    }

    public TacticalBot(String name, Long seed, String presetId, double timeBudgetSec) {
        this(name, seed, presetId, 1.0, timeBudgetSec, "balanced");
    }

    public TacticalBot(BotContext ctx) {
        this(
                ctx.name(),
                ctx.seed(),
                ctx.presetId(),
                ctx.optionDouble("stress_weight", 1.0),
                ctx.effectiveTimeBudget(0.25),
                ctx.optionString("profile", "balanced")
        );
        if (ctx.teamConfig() != null) setFixedTeam(ctx.teamConfig());
    }

    public CircusSearchBot getSearchBot() {
        return searchBot;
    }

    @Override
    public boolean requiresUnitObservations() {
        // Existing custom subclasses may read unit snapshots in chooseAction.
        return getClass() != TacticalBot.class && getClass() != StressSpecialistBot.class;
    }

    @Override
    public Action chooseAction(Observation obs, ArenaEngine engine) {
        if (obs.legalActions.size() == 1) return obs.legalActions.get(0);
        if (engine != null) return searchBot.chooseAction(obs, engine);

        Map<String, Map<String, Object>> myMap = new HashMap<>(8);
        for (Map<String, Object> u : obs.myUnits) myMap.put(Json.str(u, "uid", ""), u);
        Map<String, Map<String, Object>> enMap = new HashMap<>(8);
        for (Map<String, Object> u : obs.enemyUnits) enMap.put(Json.str(u, "uid", ""), u);

        double bestScore = -1e9;
        List<Action> bestActions = new ArrayList<>(4);
        for (Action act : obs.legalActions) {
            double sc = scoreActionObsOnly(act, obs, myMap, enMap);
            if (sc > bestScore + 1e-6) {
                bestScore = sc;
                bestActions.clear();
                bestActions.add(act);
            } else if (Math.abs(sc - bestScore) <= 1e-6) {
                bestActions.add(act);
            }
        }
        return bestActions.get(rng.nextInt(bestActions.size()));
    }

    @SuppressWarnings("unchecked")
    private double scoreActionObsOnly(
            Action act, Observation obs,
            Map<String, Map<String, Object>> myMap,
            Map<String, Map<String, Object>> enMap
    ) {
        Map<String, Object> actor = myMap.get(act.actorUid);
        if (actor == null) return -1000.0;
        if ("PASS".equals(act.kind)) return Json.boolVal(actor, "stunned", false) ? -20.0 : -60.0;

        HeroDef hdef = GameData.get().heroes.get(Json.str(actor, "hero_id", ""));
        if (hdef == null) return -100.0;

        if ("MOVE".equals(act.kind)) {
            int rank = Json.intVal(actor, "rank", 1);
            int newRank = rank - act.moveDelta;
            int usableNow = 0, usableAfter = 0;
            for (Object o : Json.listVal(actor, "equipped_skills")) {
                SkillDef s = hdef.getSkill(String.valueOf(o));
                if (s != null && s.isAttack) {
                    if (s.canLaunchFrom(rank)) usableNow++;
                    if (s.canLaunchFrom(newRank)) usableAfter++;
                }
            }
            return (usableAfter - usableNow) * 18.0 - 15.0;
        }

        SkillDef sdef = hdef.getSkill(act.skillId);
        if (sdef == null) return -100.0;

        double score = 0.0;
        if (sdef.isTransform) {
            boolean isBeast = Json.boolVal(actor, "is_beast", false);
            int stress = Json.intVal(actor, "stress", 0);
            int rank = Json.intVal(actor, "rank", 1);
            int hp = Json.intVal(actor, "hp", 1);
            int maxHp = Math.max(1, Json.intVal(actor, "max_hp", 1));
            if (!isBeast) return (rank <= 3) ? 75.0 : 15.0;
            return (stress >= 115 || hp < maxHp * 0.42 || rank == 4) ? 65.0 : -20.0;
        }

        if (!sdef.isAttack) {
            for (String tuid : act.targetUids) {
                Map<String, Object> t = myMap.get(tuid);
                if (t == null || !Json.boolVal(t, "alive", false) || Json.boolVal(t, "is_corpse", false)) continue;
                int hp = Json.intVal(t, "hp", 0);
                int maxHp = Math.max(1, Json.intVal(t, "max_hp", 1));
                int missing = maxHp - hp;
                boolean dd = Json.boolVal(t, "at_deaths_door", false);
                if (sdef.healMax > 0) {
                    double avgHeal = (sdef.healMin + sdef.healMax) * 0.5;
                    double eff = Math.min(missing, avgHeal);
                    if (dd) score += 95.0 + eff * 2.0;
                    else if (hp < maxHp * 0.45) score += 35.0 + eff * 2.2;
                    else if (missing > 4) score += eff * 1.4;
                    else score -= 10.0;
                }
                if (sdef.stressHeal > 0) {
                    int stress = Json.intVal(t, "stress", 0);
                    if (stress >= 165) score += 80.0;
                    else if (stress >= 80 && t.get("trait_id") == null) score += 45.0;
                    else if (stress >= 35) score += Math.min(stress, sdef.stressHeal) * 1.5;
                    else score -= 8.0;
                }
                if (sdef.guardRounds > 0) {
                    if (dd || hp < maxHp * 0.45) score += 55.0;
                    else if (t.get("guarded_by_uid") == null) score += 22.0;
                }
                if (sdef.guardMeRounds > 0) score += (actor.get("guarded_by_uid") == null) ? 30.0 : 5.0;
                if (sdef.clearsStun && (Json.boolVal(t, "stunned", false) || Json.boolVal(t, "dazed", false))) score += 35.0;
                if (sdef.curesBleed && hasDot(t, "bleed")) score += 20.0;
                if (sdef.curesBlight && hasDot(t, "blight")) score += 20.0;
                if (sdef.regen != null) score += (hp < maxHp * 0.75) ? 22.0 : 6.0;
            }
            if (sdef.selfHealMax > 0) {
                int miss = Json.intVal(actor, "max_hp", 1) - Json.intVal(actor, "hp", 1);
                if (Json.boolVal(actor, "at_deaths_door", false)) score += 90.0;
                else if (miss > Json.intVal(actor, "max_hp", 1) * 0.4) score += 48.0;
                else score += miss * 1.1;
            }
            if (sdef.aegisBlocks > 0 && Json.intVal(actor, "aegis_blocks", 0) == 0) score += 34.0;
            if (sdef.stealthRounds > 0 && Json.intVal(actor, "stealth_rounds", 0) == 0) {
                score += "grave_robber".equals(Json.str(actor, "hero_id", "")) ? 32.0 : 22.0;
            }
            if (sdef.partyBuffs.length > 0) score += obs.roundNum <= 2 ? 36.0 : 18.0;
            if (sdef.selfBuffs.length > 0 && sdef.maxUses == 1 && obs.roundNum <= 2) score += 40.0;
            return score;
        }

        Map<String, Object> astats = (Map<String, Object>) actor.get("stats");
        double avgBaseDmg = (Json.intVal(actor, "dmg_min", 5) + Json.intVal(actor, "dmg_max", 10)) * 0.5;
        for (String tuid : act.targetUids) {
            Map<String, Object> t = enMap.get(tuid);
            if (t == null || !Json.boolVal(t, "alive", false)) continue;
            if (Json.boolVal(t, "is_corpse", false)) { score += 4.0; continue; }
            Map<String, Object> tstats = (Map<String, Object>) t.get("stats");
            double hitProb = ArenaEngine.clamp((sdef.acc + Json.dblVal(astats, "acc", 0) - Json.dblVal(tstats, "dodge", 0) + 5.0) / 100.0, 0.05, 0.95);
            boolean dd = Json.boolVal(t, "at_deaths_door", false);
            int thp = Json.intVal(t, "hp", 0);
            int tmaxHp = Math.max(1, Json.intVal(t, "max_hp", 1));

            if (sdef.dmgMod > -1.0) {
                double mult = 1.0 + sdef.dmgMod + Json.dblVal(astats, "dmg_pct", 0);
                if (Json.intVal(t, "marked_rounds", 0) > 0) mult += sdef.bonusDmgVsMarked + Json.dblVal(astats, "dmg_vs_marked_pct", 0);
                if (hasDot(t, "bleed")) mult += sdef.bonusDmgVsBleed + Json.dblVal(astats, "dmg_vs_bleed_pct", 0);
                if (hasDot(t, "blight")) mult += sdef.bonusDmgVsBlight + Json.dblVal(astats, "dmg_vs_blight_pct", 0);
                if (Json.boolVal(t, "stunned", false) || Json.boolVal(t, "dazed", false)) mult += sdef.bonusDmgVsStunned + Json.dblVal(astats, "dmg_vs_stunned_pct", 0);
                if (sdef.isFinale) mult += Json.dblVal(actor, "finale_bonus_pct", 0);
                double protMult = sdef.armorPiercing ? 1.0 : (1.0 - Json.dblVal(tstats, "prot", 0));
                if (Json.intVal(t, "aegis_blocks", 0) > 0) protMult = 0.15;
                double expDmg = Math.max(1.0, avgBaseDmg * Math.max(0.1, mult) * protMult) * hitProb;

                if (dd && thp <= 0) {
                    double killCh = ArenaEngine.clamp((100.0 - Json.dblVal(tstats, "deathblow_res", 75) + sdef.deathblowBonus + Json.dblVal(astats, "deathblow_chance", 0)) / 100.0, 0.05, 1.0);
                    score += 130.0 * hitProb * killCh;
                } else {
                    double lowBonus = thp <= expDmg * 1.25 ? 1.6 : (thp < tmaxHp * 0.5 ? 1.25 : 1.0);
                    score += expDmg * 2.4 * lowBonus;
                }
            }
            if (sdef.stressDmg > 0) {
                double effStress = sdef.stressDmg * (1.0 + Json.dblVal(astats, "stress_dmg_pct", 0)) * (1.0 - Json.dblVal(tstats, "stress_res", 0) / 100.0) * hitProb;
                double sm = stressWeight;
                int tStress = Json.intVal(t, "stress", 0);
                if (tStress + effStress >= 200) sm *= dd ? 4.5 : 2.8;
                else if (tStress < 100 && tStress + effStress >= 100) sm *= 2.0;
                score += effStress * 1.45 * sm;
            }
            if (sdef.stunChance > 0 && !Json.boolVal(t, "stunned", false) && !Json.boolVal(t, "acted_this_round", false)) {
                double pStun = ArenaEngine.clamp((sdef.stunChance + Json.dblVal(astats, "stun_chance", 0) - Json.dblVal(tstats, "stun_res", 0)) / 100.0, 0.0, 1.0);
                score += 42.0 * hitProb * pStun;
            }
            if (sdef.dazeChance > 0 && !Json.boolVal(t, "stunned", false) && !Json.boolVal(t, "dazed", false) && !Json.boolVal(t, "acted_this_round", false)) {
                double pDaze = ArenaEngine.clamp((sdef.dazeChance + Json.dblVal(astats, "stun_chance", 0) - Json.dblVal(tstats, "stun_res", 0)) / 100.0, 0.0, 1.0);
                score += 24.0 * hitProb * pDaze;
            }
            if (sdef.bleed != null && !dd) {
                double p = ArenaEngine.clamp((sdef.bleed.chance + Json.dblVal(astats, "bleed_chance", 0) - Json.dblVal(tstats, "bleed_res", 0)) / 100.0, 0.0, 1.0);
                score += sdef.bleed.amount * sdef.bleed.rounds * 1.35 * hitProb * p;
            }
            if (sdef.blight != null && !dd) {
                double p = ArenaEngine.clamp((sdef.blight.chance + Json.dblVal(astats, "blight_chance", 0) - Json.dblVal(tstats, "blight_res", 0)) / 100.0, 0.0, 1.0);
                score += sdef.blight.amount * sdef.blight.rounds * 1.35 * hitProb * p;
            }
            if (sdef.markRounds > 0 && Json.intVal(t, "marked_rounds", 0) == 0) score += 24.0 * hitProb;
            if (sdef.breaksGuard && (t.get("guarded_by_uid") != null || t.get("guarding_uid") != null)) score += 36.0 * hitProb;
            if (sdef.deStealth && Json.intVal(t, "stealth_rounds", 0) > 0) score += 28.0 * hitProb;
        }
        if (sdef.riposteRounds > 0 && Json.intVal(actor, "riposte_rounds", 0) == 0) score += 26.0;
        if (sdef.selfHealMax > 0 && Json.intVal(actor, "hp", 1) < Json.intVal(actor, "max_hp", 1) * 0.7) score += 18.0;
        if (Json.boolVal(actor, "at_deaths_door", false)) score += 8.0;
        return score;
    }

    @SuppressWarnings("unchecked")
    private static boolean hasDot(Map<String, Object> unit, String kind) {
        for (Object o : Json.listVal(unit, "dots")) {
            if (kind.equals(Json.str((Map<String, Object>) o, "kind", ""))) return true;
        }
        return false;
    }
}
