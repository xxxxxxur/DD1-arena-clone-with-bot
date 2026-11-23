package com.circus.bot;

import com.circus.engine.ArenaEngine;
import com.circus.engine.EffectiveStats;
import com.circus.engine.Models;
import com.circus.engine.Models.*;
import com.circus.engine.StatKey;

import java.util.*;

/**
 * High-performance Iterative-Deepening Expectimax / Alpha-Beta Search Bot for The Butcher's Circus.
 *
 * Key Java performance & search optimizations over the Python version:
 * 1. Zero-event-allocation simulation via {@link ArenaEngine#cloneForSearch()}.
 * 2. Zero-HashMap single-pass primitive {@link EffectiveStats} evaluation.
 * 3. Per-node cached ally threat computation (computed once per node instead of once per action).
 * 4. Flat-array Transposition Table (65,536 entries) with Zobrist-like 64-bit state hashes and PV move hints.
 * 5. Principal Variation (PV) + Killer Move ordering for dramatically stronger Alpha-Beta pruning.
 * 6. Amortized deadline polling (every 64 nodes) -> achieves much deeper search plies in shorter time budgets!
 */
public class CircusSearchBot extends AbstractBot {
    public record SearchProfile(
            double killWeight,
            double survivalWeight,
            double damageWeight,
            double stunWeight,
            double stressWeight,
            double disruptionWeight,
            double defenseWeight
    ) {}

    public static final Map<String, SearchProfile> PROFILES = Map.of(
            "balanced", new SearchProfile(1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0),
            "direct_kill", new SearchProfile(1.15, 1.0, 1.22, 0.95, 1.0, 1.0, 0.95),
            "stun_kill", new SearchProfile(1.15, 1.0, 1.08, 1.35, 1.0, 1.25, 1.0),
            "iron_wall", new SearchProfile(1.0, 1.25, 0.95, 1.0, 1.15, 1.0, 1.35),
            "iron_hound", new SearchProfile(1.0, 1.22, 1.05, 1.0, 1.18, 1.0, 1.28),
            "shuffle_kill", new SearchProfile(1.15, 1.0, 1.18, 1.10, 1.0, 1.35, 1.0),
            "stress", new SearchProfile(1.0, 1.05, 1.0, 1.10, 1.55, 1.0, 1.0)
    );

    private static final int TT_SIZE = 1 << 17; // 131,072 entries
    private static final int TT_MASK = TT_SIZE - 1;
    private static final byte TT_EXACT = 1;
    private static final byte TT_LOWER = 2;
    private static final byte TT_UPPER = 3;
    private static final int MAX_KILLER_PLY = 20;

    protected final double timeBudgetSec;
    protected final SearchProfile profile;
    protected final int maxDepth;
    protected final int rootWidth;
    protected final int branchWidth;
    protected final boolean detailedTtHash;
    protected final double formationWeight;
    protected final boolean strategicEval;
    protected final double mitigationWeight;
    protected final double pressureWeight;
    protected final double setupWeight;
    protected final double bulwarkPriority;
    protected final double guardBias;
    protected final double controlBonus;
    private final EffectiveStats strategicStats = new EffectiveStats();

    // Transposition Table (reused across decisions, stamped by generation to avoid clearing arrays!)
    private final long[] ttKey = new long[TT_SIZE];
    private final double[] ttVal = new double[TT_SIZE];
    private final byte[] ttDepth = new byte[TT_SIZE];
    private final byte[] ttFlag = new byte[TT_SIZE];
    private final int[] ttGen = new int[TT_SIZE];
    private final Action[] ttMove = new Action[TT_SIZE];
    private int searchGen = 0;

    // Killer moves per ply
    private final Action[][] killers = new Action[MAX_KILLER_PLY][2];

    // Reusable scratchpad stats (single-threaded per bot instance)
    private final EffectiveStats scratchA = new EffectiveStats();
    private final EffectiveStats scratchB = new EffectiveStats();
    private final EffectiveStats[] enemyStatsCache = {
            new EffectiveStats(), new EffectiveStats(), new EffectiveStats(), new EffectiveStats()
    };
    private final UnitState[] enemyUnitsCache = new UnitState[4];
    private int enemyStatsCacheCount = 0;
    private String bestExecUid = null;
    private double bestExecDbBonus = 0.0;
    private double secondExecDbBonus = 0.0;
    private int unactedAllyCount = 0;
    private final ArenaEngine.SearchScratch simulationScratch = new ArenaEngine.SearchScratch();
    private final double[] threatScratch = new double[4];
    private final String[] threatUidScratch = new String[4];

    // Search telemetry for benchmarking and diagnostics
    private int lastDepthReached = 0;
    private int lastNodesVisited = 0;
    private int lastTtHits = 0;
    private double lastElapsedMs = 0.0;

    // Per-search runtime counters
    private long deadlineNs;
    private boolean timeUp;
    private int nodesVisited;
    private int ttHits;

    public CircusSearchBot(
            String name,
            double timeBudgetSec,
            String profileName,
            Long seed,
            String presetId,
            int maxDepth,
            int rootWidth,
            int branchWidth
    ) {
        this(name, timeBudgetSec,
                PROFILES.getOrDefault(profileName != null ? profileName : "balanced", PROFILES.get("balanced")),
                seed, presetId, maxDepth, rootWidth, branchWidth,
                false, 1.0, false, 0.0, 0.0, 0.0, 120.0, 0.0, 0.0);
    }

    protected CircusSearchBot(
            String name,
            double timeBudgetSec,
            SearchProfile profile,
            Long seed,
            String presetId,
            int maxDepth,
            int rootWidth,
            int branchWidth,
            boolean detailedTtHash,
            double formationWeight,
            boolean strategicEval,
            double mitigationWeight,
            double pressureWeight,
            double setupWeight,
            double bulwarkPriority,
            double guardBias,
            double controlBonus
    ) {
        super(name != null ? name : "CircusSearchBot", presetId);
        this.timeBudgetSec = Math.min(0.46, Math.max(0.005, timeBudgetSec));
        this.profile = (profile != null) ? profile : PROFILES.get("balanced");
        this.maxDepth = Math.max(1, maxDepth);
        this.rootWidth = Math.max(2, rootWidth);
        this.branchWidth = Math.max(2, branchWidth);
        this.detailedTtHash = detailedTtHash;
        this.formationWeight = Math.max(0.0, formationWeight);
        this.strategicEval = strategicEval;
        this.mitigationWeight = mitigationWeight;
        this.pressureWeight = pressureWeight;
        this.setupWeight = setupWeight;
        this.bulwarkPriority = bulwarkPriority;
        this.guardBias = guardBias;
        this.controlBonus = controlBonus;
    }

    public CircusSearchBot(String name, double timeBudgetSec, String profileName, Long seed, String presetId) {
        this(name, timeBudgetSec, profileName, seed, presetId, 14, 14, 6);
    }

    private static SearchProfile resolveProfile(BotContext ctx) {
        String profileName = ctx.optionString("profile", "balanced");
        SearchProfile base = PROFILES.getOrDefault(profileName != null ? profileName : "balanced", PROFILES.get("balanced"));
        return new SearchProfile(
                ctx.optionDouble("kill_weight", base.killWeight),
                ctx.optionDouble("survival_weight", base.survivalWeight),
                ctx.optionDouble("damage_weight", base.damageWeight),
                ctx.optionDouble("stun_weight", base.stunWeight),
                ctx.optionDouble("stress_weight", base.stressWeight),
                ctx.optionDouble("disruption_weight", base.disruptionWeight),
                ctx.optionDouble("defense_weight", base.defenseWeight)
        );
    }

    public CircusSearchBot(BotContext ctx) {
        this(
                ctx.name(),
                ctx.effectiveTimeBudget(0.25),
                resolveProfile(ctx),
                ctx.seed(),
                ctx.presetId(),
                ctx.optionInt("max_depth", 14),
                ctx.optionInt("root_width", 10),
                ctx.optionInt("branch_width", 4),
                ctx.optionBool("detailed_tt_hash", true),
                ctx.optionDouble("formation_weight", 1.0),
                ctx.optionBool("strategic_eval",
                        ctx.optionDouble("mitigation_weight", 0.0) > 0.0
                                || ctx.optionDouble("pressure_weight", 0.0) > 0.0
                                || ctx.optionDouble("setup_weight", 0.0) > 0.0),
                ctx.optionDouble("mitigation_weight", 0.0),
                ctx.optionDouble("pressure_weight", 0.0),
                ctx.optionDouble("setup_weight", 0.0),
                ctx.optionDouble("bulwark_priority", 120.0),
                ctx.optionDouble("guard_bias", 0.0),
                ctx.optionDouble("control_bonus", 0.0)
        );
        if (ctx.teamConfig() != null) setFixedTeam(ctx.teamConfig());
    }

    public CircusSearchBot(double timeBudgetSec) {
        this("CircusSearchBot", timeBudgetSec, "balanced", null, null);
    }

    public int getLastDepthReached() { return lastDepthReached; }
    public int getLastNodesVisited() { return lastNodesVisited; }
    public int getLastTtHits() { return lastTtHits; }
    public double getLastElapsedMs() { return lastElapsedMs; }

    private boolean checkDeadline() {
        if (timeUp) return true;
        if ((nodesVisited & 63) == 0 && System.nanoTime() >= deadlineNs) {
            timeUp = true;
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ Threat & State Evaluation
    private int computeAllyThreats(ArenaEngine engine, int team) {
        int opp = 1 - team;
        boolean oppHasActions = false;
        ArrayList<UnitState> oppList = engine.state.teams[opp];
        for (int i = 0, n = oppList.size(); i < n; i++) {
            UnitState u = oppList.get(i);
            if (u.alive && !u.isCorpse && !u.actedThisRound && !u.stunned) {
                oppHasActions = true;
                break;
            }
        }

        int count = 0;
        ArrayList<UnitState> myList = engine.state.teams[team];
        for (int i = 0, n = myList.size(); i < n; i++) {
            UnitState u = myList.get(i);
            if (!u.alive || u.isCorpse) continue;
            double t = 0.0;
            if (u.atDeathsDoor && u.hp <= 0) {
                t = oppHasActions ? 1.25 : 0.85;
                if (u.guardedByUid != null) t *= 0.45;
                if (u.aegisBlocks > 0) t *= 0.50;
                if (u.stealthRounds > 0) t *= 0.55;
                if (u.stress >= 165) t = Math.max(t, 1.45);
            } else {
                double hpFrac = (double) u.hp / Math.max(1, u.maxHp);
                if (hpFrac <= 0.25) t = oppHasActions ? 0.55 : 0.30;
                else if (hpFrac <= 0.42) t = 0.22;
                if (u.stress >= 175) t = Math.max(t, 0.70);
                if (u.markedRounds > 0) t *= 1.25;
                if (u.guardedByUid != null) t *= 0.55;
            }
            if (count < 4) {
                threatUidScratch[count] = u.uid;
                threatScratch[count] = t;
                count++;
            }
        }
        return count;
    }

    private double lookupThreat(String uid, int threatCount) {
        for (int i = 0; i < threatCount; i++) {
            if (threatUidScratch[i].equals(uid)) return threatScratch[i];
        }
        return 0.0;
    }

    private static double heroValueWeight(UnitState u) {
        double w = 1.0;
        String hid = u.heroId;
        if ("vestal".equals(hid) || "occultist".equals(hid) || "flagellant".equals(hid) || "arbalest".equals(hid)) {
            w += 0.14;
        } else if ("bounty_hunter".equals(hid) || "highwayman".equals(hid) || "shieldbreaker".equals(hid)
                || "jester".equals(hid) || "grave_robber".equals(hid)) {
            w += 0.10;
        }
        if (!u.actedThisRound && !u.stunned) {
            w += 0.12;
        }
        return w;
    }

    private double unitStateScore(UnitState u, int oppUnacted, boolean isMine) {
        SearchProfile p = this.profile;
        double valW = heroValueWeight(u);
        double base = 1850.0 * (isMine ? p.survivalWeight : p.killWeight) * valW;

        double hpFrac = (double) u.hp / Math.max(1, u.maxHp);
        // Fast concave HP curve (hpFrac^0.75 via two sqrts, ~15x faster than Math.pow)
        double hpScore = (hpFrac <= 0.0) ? 0.0 : (hpFrac >= 1.0 ? 320.0 : Math.sqrt(hpFrac * Math.sqrt(hpFrac)) * 320.0);

        double ddPen = 0.0;
        if (u.atDeathsDoor && u.hp <= 0) {
            ddPen = 420.0 + (oppUnacted > 0 ? 160.0 : 40.0);
            if (u.guardedByUid != null) ddPen *= 0.55;
            if (u.aegisBlocks > 0) ddPen *= 0.60;
            if (u.stealthRounds > 0) ddPen *= 0.65;
            if ("flagellant".equals(u.heroId)) ddPen *= 0.72;
        }

        int s = u.stress;
        double stressPen;
        if (s >= 165) {
            stressPen = 135.0 + (s - 165) * 6.5;
            if (u.atDeathsDoor) stressPen += 320.0;
        } else if (s >= 100) {
            stressPen = 75.0 + (s - 100) * 0.92;
        } else {
            stressPen = s * 0.68;
        }
        if (!isMine) stressPen *= p.stressWeight;

        double status = 0.0;
        if (u.stunned) status -= (!u.actedThisRound ? 175.0 : 45.0);
        else if (u.dazed && !u.actedThisRound) status -= 48.0;
        if (!u.actedThisRound && !u.stunned) status += 48.0;
        if (u.isVirtuous) status += 145.0;
        else if (u.isAfflicted) status -= 115.0;
        if (u.riposteRounds > 0) status += 82.0 * p.defenseWeight;
        if (u.aegisBlocks > 0) status += 68.0 * u.aegisBlocks * p.defenseWeight;
        if (u.stealthRounds > 0) status += 58.0;
        if (u.guardingUid != null) status += 42.0 * p.defenseWeight;
        if (u.guardedByUid != null) status += u.atDeathsDoor ? 95.0 : 38.0;
        if (u.markedRounds > 0) status -= (hpFrac < 0.5 ? 65.0 : 35.0);

        ArrayList<ActiveDot> dots = u.dots;
        for (int i = 0, n = dots.size(); i < n; i++) {
            ActiveDot dot = dots.get(i);
            int rem = Math.min(dot.roundsLeft, 3);
            if (dot.kind == Models.DOT_BLEED || dot.kind == Models.DOT_BLIGHT) {
                int effDmg = Math.min(Math.max(0, u.hp), dot.amount * rem);
                status -= effDmg * 6.2;
            } else if (dot.kind == Models.DOT_HORROR) {
                status -= dot.amount * rem * 2.4;
            } else if (dot.kind == Models.DOT_REGEN) {
                status += dot.amount * rem * 8.5;
            }
        }

        double formPen = formationPenaltyAtRank(u, u.rank);
        if (formPen > 0.0) {
            if (isMine) status -= formPen * formationWeight;
            else status -= formPen * Math.max(0.75, formationWeight) * p.disruptionWeight;
        }

        if ("jester".equals(u.heroId) && u.finaleBonusPct > 0.0) {
            status += Math.min(1.5, u.finaleBonusPct) * 70.0;
        }

        return base + hpScore - ddPen - stressPen + status;
    }

    /**
     * Formation integrity penalty for a unit at a given rank.
     * Dance/reposition heroes with an active self-move skill at this rank are NOT falsely penalized.
     */
    private static double formationPenaltyAtRank(UnitState u, int rank) {
        SkillDef[] eq = u.equippedSkillDefs;
        boolean isAbom = "abomination".equals(u.heroId);
        int totalActive = 0;
        int usable = 0;
        boolean hasDanceEscape = false;
        for (int i = 0; i < eq.length; i++) {
            SkillDef sd = eq[i];
            if (sd == null || sd.isTransform) continue;
            if (isAbom) {
                if ("human".equals(sd.abomForm) && u.isBeast) continue;
                if ("beast".equals(sd.abomForm) && !u.isBeast) continue;
            }
            if (sd.maxUses > 0 && u.skillUses.getOrDefault(sd.id, 0) >= sd.maxUses) continue;
            totalActive++;
            if (sd.canLaunchFrom(rank)) {
                usable++;
                if (sd.selfMove != 0) hasDanceEscape = true;
            }
        }
        if (totalActive == 0) return 0.0;
        double pen;
        if (usable == 0) {
            pen = 210.0;
        } else if (usable == 1) {
            pen = hasDanceEscape ? 25.0 : 95.0;
        } else if (usable == 2 && totalActive >= 4) {
            pen = hasDanceEscape ? 0.0 : 28.0;
        } else {
            pen = 0.0;
        }
        if (pen > 0.0 && !u.actedThisRound && !u.stunned) pen *= 1.15;
        return pen;
    }

    /**
     * Computes exact change in total team formation penalty if {@code mover} shifts by {@code moveDelta}
     * (positive = forward toward rank 1, negative = backward toward rank 4).
     * Positive return value means the team's formation got WORSE; negative means it IMPROVED.
     */
    private static double teamFormationShiftDelta(ArenaEngine engine, int team, UnitState mover, int moveDelta) {
        if (moveDelta == 0) return 0.0;
        int slots = engine.countActiveSlots(team);
        if (slots <= 1) return 0.0;
        int oldRank = mover.rank;
        int newRank = Math.max(1, Math.min(slots, oldRank - moveDelta));
        if (newRank == oldRank) return 0.0;

        double before = 0.0;
        double after = 0.0;
        ArrayList<UnitState> units = engine.state.teams[team];
        for (int i = 0, n = units.size(); i < n; i++) {
            UnitState u = units.get(i);
            if (!u.alive || u.isCorpse) continue;
            int r = u.rank;
            int nr = r;
            if (u == mover) {
                nr = newRank;
            } else if (newRank < oldRank && r >= newRank && r < oldRank) {
                nr = r + 1;
            } else if (newRank > oldRank && r > oldRank && r <= newRank) {
                nr = r - 1;
            }
            if (nr != r) {
                before += formationPenaltyAtRank(u, r);
                after += formationPenaltyAtRank(u, nr);
            }
        }
        return after - before;
    }

    private static double unitMaxDeathblowBonus(ArenaEngine engine, UnitState u) {
        double base = engine.buffSum(u, StatKey.DEATHBLOW_CHANCE);
        double bestSkill = 0.0;
        SkillDef[] eq = u.equippedSkillDefs;
        for (int i = 0; i < eq.length; i++) {
            SkillDef sd = eq[i];
            if (sd != null && sd.canLaunchFrom(u.rank) && sd.deathblowBonus > bestSkill) {
                bestSkill = sd.deathblowBonus;
            }
        }
        return base + bestSkill;
    }

    private EffectiveStats cachedEnemyStats(ArenaEngine engine, UnitState t) {
        for (int i = 0; i < enemyStatsCacheCount; i++) {
            if (enemyUnitsCache[i] == t) return enemyStatsCache[i];
        }
        return engine.computeEffectiveStats(t, null, "", scratchB);
    }

    public double evaluateState(ArenaEngine engine, int myTeam) {
        if (engine.state.finished) {
            if (engine.state.winner != null && engine.state.winner == myTeam) {
                return 100000.0 - engine.state.roundNum * 5.0;
            }
            return -100000.0 + engine.state.roundNum * 5.0;
        }

        int oppTeam = 1 - myTeam;
        ArrayList<UnitState> myList = engine.state.teams[myTeam];
        ArrayList<UnitState> enList = engine.state.teams[oppTeam];

        int myUnacted = 0, enUnacted = 0;
        int myCorpses = 0, enCorpses = 0;
        for (int i = 0, n = myList.size(); i < n; i++) {
            UnitState u = myList.get(i);
            if (u.alive) {
                if (u.isCorpse) myCorpses++;
                else if (!u.actedThisRound && !u.stunned) myUnacted++;
            }
        }
        for (int i = 0, n = enList.size(); i < n; i++) {
            UnitState u = enList.get(i);
            if (u.alive) {
                if (u.isCorpse) enCorpses++;
                else if (!u.actedThisRound && !u.stunned) enUnacted++;
            }
        }

        double myScore = 0.0;
        for (int i = 0, n = myList.size(); i < n; i++) {
            UnitState u = myList.get(i);
            if (u.alive && !u.isCorpse) myScore += unitStateScore(u, enUnacted, true);
        }
        double enScore = 0.0;
        for (int i = 0, n = enList.size(); i < n; i++) {
            UnitState u = enList.get(i);
            if (u.alive && !u.isCorpse) enScore += unitStateScore(u, myUnacted, false);
        }

        double corpseTerm = (enCorpses - myCorpses) * 14.0;
        double turnBonus = (engine.state.activeTeam == myTeam) ? 24.0 : -24.0;
        double base = myScore - enScore + corpseTerm + turnBonus;
        if (!strategicEval) return base;
        return base + strategicValue(engine, myTeam) - strategicValue(engine, oppTeam);
    }

    private double strategicValue(ArenaEngine engine, int team) {
        double value = 0.0;
        for (UnitState u : engine.state.teams[team]) {
            if (!u.alive || u.isCorpse) continue;
            double[] s = engine.computeEffectiveStats(u, null, "", strategicStats).values;
            value += mitigationWeight * ((s[StatKey.DODGE] - u.heroDef.dodge) * 6.0
                    + (s[StatKey.PROT] - u.heroDef.prot) * 500.0
                    + (s[StatKey.STRESS_RES] - u.heroDef.stressRes) * 2.0);
            value += s[StatKey.ACC] * 2.4 + s[StatKey.DMG_PCT] * 220.0;
            value += s[StatKey.STUN_CHANCE] * 0.8;
            int incomingDots = 0, regen = 0;
            for (ActiveDot dot : u.dots) {
                int total = dot.amount * Math.min(3, dot.roundsLeft);
                if (dot.kind == Models.DOT_REGEN) regen += total;
                else if (dot.kind != Models.DOT_HORROR) incomingDots += total;
            }
            value -= Math.max(0, regen - (u.maxHp - u.hp + incomingDots + 8)) * 8.5;
            value -= pressureWeight * (u.stress * 1.1 + (u.isAfflicted ? 100.0 : 0.0)
                    + Math.max(0, u.stress - 150) * 2.2);
        }
        return value;
    }

    // ------------------------------------------------------------------ Analytical EV Action Scorer
    protected double scoreActionEv(
            ArenaEngine engine, Action act, int team,
            int threatCount, double maxAllyThreat, boolean anyAllyDd, boolean oppHasCorpse
    ) {
        SearchProfile p = this.profile;
        if ("SURRENDER".equals(act.kind)) return -99999.0;
        UnitState actor = engine.findUnit(act.actorUid);
        if (actor == null || !actor.alive) return -1000.0;

        int oppTeam = 1 - team;

        if ("PASS".equals(act.kind)) {
            return actor.stunned ? 20.0 : -380.0;
        }
        if ("MOVE".equals(act.kind)) {
            int curUsable = 0, newUsable = 0;
            int newRank = (int) ArenaEngine.clamp(actor.rank - act.moveDelta, 1, Math.max(1, engine.countActiveSlots(team)));
            for (SkillDef sd : actor.equippedSkillDefs) {
                if (sd != null && !sd.isTransform) {
                    if (sd.canLaunchFrom(actor.rank)) curUsable++;
                    if (sd.canLaunchFrom(newRank)) newUsable++;
                }
            }
            double teamDelta = teamFormationShiftDelta(engine, team, actor, act.moveDelta);
            return -140.0 + (newUsable - curUsable) * 60.0 - teamDelta * 0.85 * formationWeight;
        }

        HeroDef hdef = actor.heroDef;
        SkillDef sdef = hdef.getSkill(act.skillId);
        if (sdef == null) return -500.0;

        double score = 0.0;

        if ("enemy".equals(sdef.targetTeam)) {
            for (int ti = 0, tn = act.targetUids.size(); ti < tn; ti++) {
                UnitState rawT = engine.findUnit(act.targetUids.get(ti));
                if (rawT == null || !rawT.alive) continue;
                UnitState t = rawT;
                if (!sdef.isAoe && !sdef.ignoresGuard && rawT.guardedByUid != null) {
                    UnitState g = engine.findUnit(rawT.guardedByUid);
                    if (g != null && g.alive && !g.isCorpse) t = g;
                }

                if (t.isCorpse) {
                    score += sdef.clearsCorpses ? 38.0 : 12.0;
                    continue;
                }

                EffectiveStats astats = engine.computeEffectiveStats(actor, t, sdef.isRanged ? "ranged" : "melee", scratchA);
                EffectiveStats tstats = cachedEnemyStats(engine, t);

                double valW = heroValueWeight(t);
                double pHit = ArenaEngine.computeHitChance(sdef.acc + astats.values[StatKey.ACC], tstats.values[StatKey.DODGE]) / 100.0;

                boolean isBleed = t.hasDotKind(Models.DOT_BLEED);
                boolean isBlight = t.hasDotKind(Models.DOT_BLIGHT);
                boolean isMarked = t.markedRounds > 0;

                double critPct = astats.values[StatKey.CRIT] + sdef.critMod + tstats.values[StatKey.CRITS_RECEIVED];
                double dbBonus = sdef.deathblowBonus + astats.values[StatKey.DEATHBLOW_CHANCE];
                if (sdef.isFinale) critPct += astats.values[StatKey.FINALE_CRIT];
                if (isMarked) {
                    critPct += sdef.bonusCritVsMarked;
                    if ("aimed_shot".equals(sdef.id) || "sniper_shot".equals(sdef.id)) dbBonus += 30.0;
                }
                if (t.isAfflicted && "bloodlet".equals(sdef.id)) dbBonus += 40.0;
                if (isBleed) critPct += sdef.bonusCritVsBleed;
                if (isBlight) critPct += sdef.bonusCritVsBlight;
                double pCrit = ArenaEngine.clamp(critPct, 0.0, 100.0) / 100.0;

                double expDmg = 0.0;
                if (sdef.dmgMod > -1.0) {
                    if (t.aegisBlocks > 0) {
                        score += pHit * (t.atDeathsDoor ? 155.0 : 65.0);
                    } else {
                        int lo = ("abomination".equals(hdef.id) && actor.isBeast) ? hdef.beastDmgMin : hdef.dmgMin;
                        int hi = ("abomination".equals(hdef.id) && actor.isBeast) ? hdef.beastDmgMax : hdef.dmgMax;
                        double mult = 1.0 + sdef.dmgMod + astats.values[StatKey.DMG_PCT];
                        if ("sidearm".equals(sdef.id)) {
                            boolean anyActed = false;
                            ArrayList<UnitState> myL = engine.state.teams[team];
                            for (int k = 0; k < myL.size(); k++) {
                                UnitState u = myL.get(k);
                                if (u.alive && !u.isCorpse && u != actor && u.actedThisRound) {
                                    anyActed = true;
                                    break;
                                }
                            }
                            if (!anyActed) mult += 0.30;
                        }
                        if (isMarked) mult += sdef.bonusDmgVsMarked + astats.values[StatKey.DMG_VS_MARKED_PCT];
                        if (isBleed) mult += sdef.bonusDmgVsBleed + astats.values[StatKey.DMG_VS_BLEED_PCT];
                        if (isBlight) mult += sdef.bonusDmgVsBlight + astats.values[StatKey.DMG_VS_BLIGHT_PCT];
                        if (t.stunned) mult += sdef.bonusDmgVsStunned + astats.values[StatKey.DMG_VS_STUNNED_PCT];
                        else if (t.dazed && "finish_him".equals(sdef.id)) mult += 0.30;
                        if (t.guardedByUid != null) mult += sdef.bonusDmgVsGuarded;
                        if (sdef.isFinale) mult += astats.values[StatKey.FINALE_DAMAGE];
                        if ("rake".equals(sdef.id)) mult += astats.values[StatKey.RAKE_DMG_PCT];
                        mult = Math.max(0.10, mult);

                        double protEff = sdef.armorPiercing ? 0.0 : Math.max(0.0, tstats.values[StatKey.PROT] - astats.values[StatKey.ARMOR_PIERCE]);
                        double dmgScale = mult * (1.0 - protEff) * (1.0 + tstats.values[StatKey.DAMAGE_TAKEN_PCT]);
                        double minDmg = Math.max(1.0, lo * dmgScale);
                        double maxDmg = Math.max(minDmg, hi * dmgScale);
                        double avgRaw = ((lo + hi) * 0.5 * (1.0 - pCrit) + hi * 1.5 * pCrit) * dmgScale;
                        expDmg = Math.max(1.0, avgRaw);

                        // PRIORITY #1: ACTUAL KILL PROBABILITY
                        if (t.atDeathsDoor && t.hp <= 0) {
                            double pDb = ArenaEngine.clamp(100.0 - tstats.values[StatKey.DEATHBLOW_RES] + dbBonus, 5.0, 100.0) / 100.0;
                            score += (pHit * pDb) * 2350.0 * p.killWeight * valW;
                        } else if (sdef.isFinale && expDmg >= t.hp) {
                            double pKill = pHit * ArenaEngine.clamp(expDmg / Math.max(1.0, t.hp), 0.5, 1.0);
                            score += pKill * 2300.0 * p.killWeight * valW;
                        } else {
                            // Exact probability of putting target onto Death's Door (no overkill bonus on 1-HP targets!)
                            double pEnterDd;
                            if (t.hp <= minDmg) {
                                pEnterDd = 1.0;
                            } else if (t.hp <= maxDmg) {
                                pEnterDd = ((maxDmg - t.hp + 1.0) / (maxDmg - minDmg + 1.0)) * (1.0 - pCrit) + pCrit;
                            } else if (t.hp <= maxDmg * 1.5) {
                                pEnterDd = pCrit;
                            } else {
                                pEnterDd = 0.0;
                            }
                            double effHpDmg = Math.min(t.hp, expDmg);
                            double woundMult = 1.0 + 0.55 * (1.0 - (double) t.hp / Math.max(1, t.maxHp));
                            score += pHit * effHpDmg * 13.5 * woundMult * p.damageWeight * valW;
                            if (pEnterDd > 0.0) {
                                // Uniform Death's Door entry value regardless of overkill damage
                                score += pHit * pEnterDd * 260.0 * p.damageWeight * valW;
                                // Setup -> Executioner ordering: reward non-finisher cracking target to DD while a high-DB ally is unacted
                                if (unactedAllyCount >= 2 && pEnterDd >= 0.25) {
                                    double actorMaxDb = unitMaxDeathblowBonus(engine, actor);
                                    if (bestExecUid != null && !bestExecUid.equals(actor.uid) && bestExecDbBonus > actorMaxDb + 10.0) {
                                        double dbGap = Math.min(60.0, bestExecDbBonus - actorMaxDb);
                                        score += pHit * pEnterDd * dbGap * 4.5 * p.killWeight * valW;
                                    } else if (bestExecUid != null && bestExecUid.equals(actor.uid)
                                            && bestExecDbBonus > secondExecDbBonus + 10.0 && t.hp <= minDmg) {
                                        double dbGap = Math.min(60.0, bestExecDbBonus - secondExecDbBonus);
                                        score -= pHit * dbGap * 2.8 * p.killWeight * valW;
                                    }
                                }
                            }
                        }
                    }
                }

                // Stress damage & Heart Attack KILL probability (skip overkill crit-stress on guaranteed-DD low-HP hits)
                boolean guaranteedDdOverkill = !t.atDeathsDoor && sdef.dmgMod > -1.0 && t.aegisBlocks == 0 && expDmg >= t.hp * 2.0 && t.hp <= 4;
                double baseStress = sdef.stressDmg + (expDmg > 0 && !guaranteedDdOverkill ? 10.0 * pCrit : 0.0);
                if (baseStress > 0) {
                    double expStress = baseStress * Math.max(0.25, 1.0 + astats.values[StatKey.STRESS_DMG_PCT])
                            * (1.0 - tstats.values[StatKey.STRESS_RES] / 100.0);
                    if (t.stress + expStress >= 200.0 && t.atDeathsDoor && t.hp <= 0) {
                        score += pHit * 2550.0 * p.killWeight * valW;
                    } else if (t.stress + expStress >= 200.0) {
                        score += pHit * 480.0 * p.stressWeight * valW;
                    } else if (t.stress < 100 && t.stress + expStress >= 100.0) {
                        score += pHit * 240.0 * p.stressWeight * valW;
                    } else {
                        double urgency = t.stress >= 140 ? 1.65 : (t.stress >= 80 ? 1.2 : 0.85);
                        score += pHit * expStress * 5.2 * urgency * p.stressWeight;
                    }
                }

                // Stun & Daze
                if (sdef.stunChance > 0 && !t.stunned) {
                    double pStun = pHit * ArenaEngine.clamp((sdef.stunChance + astats.values[StatKey.STUN_CHANCE] + 20.0 * pCrit - tstats.values[StatKey.STUN_RES]) / 100.0, 0.0, 1.0);
                    if (!t.actedThisRound) {
                        double stunVal = 290.0 * valW;
                        if (anyAllyDd) stunVal += 360.0 * p.survivalWeight;
                        if (t.guardingUid != null) stunVal += 180.0;
                        score += pStun * stunVal * p.stunWeight;
                    } else {
                        score += pStun * 55.0 * p.stunWeight;
                    }
                }
                if (sdef.dazeChance > 0 && !t.stunned && !t.dazed && !t.actedThisRound) {
                    double pDaze = pHit * ArenaEngine.clamp((sdef.dazeChance + astats.values[StatKey.STUN_CHANCE] + 20.0 * pCrit - tstats.values[StatKey.STUN_RES]) / 100.0, 0.0, 1.0);
                    score += pDaze * (anyAllyDd ? 145.0 : 95.0) * p.stunWeight * valW;
                }

                // Guard Break
                if (sdef.breaksGuard && (t.guardingUid != null || t.guardedByUid != null)) {
                    UnitState prot = t.guardingUid != null ? engine.findUnit(t.guardingUid) : t;
                    double gbVal = (prot != null && prot.atDeathsDoor) ? 340.0 : 150.0;
                    score += pHit * gbVal * p.disruptionWeight;
                }
                if (sdef.deStealth && t.stealthRounds > 0) {
                    score += pHit * (t.atDeathsDoor ? 175.0 : 85.0);
                }
                if (sdef.markRounds > 0 && t.markedRounds == 0) {
                    score += pHit * 95.0;
                }

                // Bleed / Blight DoTs
                if (sdef.bleed != null && !(t.atDeathsDoor && t.hp <= 0)) {
                    double pDot = pHit * ArenaEngine.clamp((sdef.bleed.chance + astats.values[StatKey.BLEED_CHANCE] + 20.0 * pCrit - tstats.values[StatKey.BLEED_RES]) / 100.0, 0.0, 1.0);
                    score += pDot * Math.min(t.hp, sdef.bleed.amount * Math.min(sdef.bleed.rounds, 3)) * 9.0 * p.damageWeight;
                }
                if (sdef.blight != null && !(t.atDeathsDoor && t.hp <= 0)) {
                    double pDot = pHit * ArenaEngine.clamp((sdef.blight.chance + astats.values[StatKey.BLIGHT_CHANCE] + 20.0 * pCrit - tstats.values[StatKey.BLIGHT_RES]) / 100.0, 0.0, 1.0);
                    score += pDot * Math.min(t.hp, sdef.blight.amount * Math.min(sdef.blight.rounds, 3)) * 9.0 * p.damageWeight;
                }

                // Pull / Push / Shuffle rank disruption (scored by actual enemy skill loss!)
                if ((sdef.pull > 0 || sdef.push > 0 || sdef.shuffleTarget) && !t.isCorpse) {
                    double pMv = pHit * ArenaEngine.clamp((sdef.moveChance + astats.values[StatKey.MOVE_CHANCE] - tstats.values[StatKey.MOVE_RES]) / 100.0, 0.0, 1.0);
                    double disrupt = 0.0;
                    if (sdef.pull > 0 || sdef.push > 0) {
                        int moveDelta = sdef.pull > 0 ? sdef.pull : -sdef.push;
                        double enemyDelta = teamFormationShiftDelta(engine, oppTeam, t, moveDelta);
                        disrupt = Math.max(20.0, 35.0 + enemyDelta * 1.15);
                    } else if (sdef.shuffleTarget) {
                        double curPen = formationPenaltyAtRank(t, t.rank);
                        disrupt = curPen <= 10.0 ? 85.0 : 25.0;
                    }
                    score += pMv * disrupt * Math.max(0.75, formationWeight) * p.disruptionWeight;
                }

                if (sdef.targetDebuffs.length > 0) {
                    score += pHit * 45.0 * sdef.targetDebuffs.length;
                }
                if (sdef.triggersRiposte() && t.riposteRounds > 0 && !t.stunned) {
                    if (actor.atDeathsDoor && actor.hp <= 0) score -= 620.0 * p.survivalWeight;
                    else score -= 70.0;
                }
            }

            if (sdef.riposteRounds > 0 && actor.riposteRounds == 0) score += 135.0 * p.defenseWeight;
            if (sdef.aegisBlocks > 0) score += (actor.atDeathsDoor ? 260.0 : 115.0) * sdef.aegisBlocks * p.survivalWeight;
            if (sdef.selfHealMax > 0) {
                int missing = actor.maxHp - actor.hp;
                if (actor.atDeathsDoor && actor.hp <= 0) score += 1400.0 * p.survivalWeight;
                else score += Math.min(missing, (sdef.selfHealMin + sdef.selfHealMax) * 0.5) * 10.5;
            }
            if (sdef.clearsCorpses && oppHasCorpse) score += 65.0;
        } else {
            // FRIENDLY / SELF / SURVIVAL SKILLS
            if (sdef.isTransform) {
                // Free action (once per round): Abomination immediately acts again in the new form.
                if (!actor.isBeast) {
                    int livingEnemies = 0;
                    boolean enDd = false;
                    for (UnitState u : engine.state.teams[oppTeam]) {
                        if (u.alive && !u.isCorpse) {
                            livingEnemies++;
                            if (u.atDeathsDoor) enDd = true;
                        }
                    }
                    // Butcher's Circus Transform inflicts 8x3 Horror on all living enemies + unlocks immediate Beast attack
                    score += livingEnemies * 85.0 * p.stressWeight;
                    score += (actor.rank <= 3 ? 280.0 * p.damageWeight : 40.0);
                    if (enDd) score += 140.0 * p.killWeight;
                } else {
                    // Reverting to Human is also a free action (costs self-Daze next round) and unlocks Absolution/Manacles
                    boolean urgentRecover = actor.atDeathsDoor || actor.hp < actor.maxHp * 0.42 || actor.stress >= 115;
                    boolean stuckBack = actor.rank == 4;
                    if (urgentRecover) score += 420.0 * p.survivalWeight;
                    else if (stuckBack) score += 260.0 * p.survivalWeight;
                    else score -= 60.0;
                }
            }

            for (int ti = 0, tn = act.targetUids.size(); ti < tn; ti++) {
                UnitState t = engine.findUnit(act.targetUids.get(ti));
                if (t == null || !t.alive) continue;
                double tThreat = lookupThreat(t.uid, threatCount);
                double valW = heroValueWeight(t);

                if (sdef.healMax > 0 || sdef.healPct != 0.0) {
                    double avgHeal = sdef.healPct != 0.0 ? t.maxHp * sdef.healPct : (sdef.healMin + sdef.healMax) * 0.5;
                    int missing = Math.max(0, t.maxHp - t.hp);
                    if (t.atDeathsDoor && t.hp <= 0) {
                        score += (1850.0 + Math.min(missing, avgHeal) * 14.0) * p.survivalWeight * valW;
                    } else if (missing > 0) {
                        double frac = (double) t.hp / Math.max(1, t.maxHp);
                        double urgency = frac < 0.35 ? 2.4 : (frac < 0.65 ? 1.35 : 0.55);
                        score += Math.min(missing, avgHeal) * 14.0 * urgency * p.survivalWeight * valW;
                    } else {
                        score -= 25.0;
                    }
                }

                if (sdef.guardRounds > 0 && t != actor) {
                    if (t.atDeathsDoor && t.hp <= 0 && t.guardedByUid == null) {
                        score += 1480.0 * p.survivalWeight * valW;
                    } else if (tThreat >= 0.45 && t.guardedByUid == null) {
                        score += 480.0 * tThreat * p.survivalWeight * p.defenseWeight;
                    } else if (t.guardedByUid == null) {
                        score += 110.0 * p.defenseWeight;
                    } else {
                        score -= 80.0;
                    }
                }

                if (sdef.guardMeRounds > 0 && t != actor) {
                    double actorThreat = lookupThreat(actor.uid, threatCount);
                    if (actor.atDeathsDoor && actor.guardedByUid == null) score += 1350.0 * p.survivalWeight;
                    else score += 160.0 + actorThreat * 420.0;
                }

                if (sdef.stressHeal > 0 && t.stress > 0) {
                    if (t.stress >= 165) score += (t.atDeathsDoor ? 980.0 : 480.0) * p.survivalWeight;
                    else if (t.stress >= 85) score += Math.min(t.stress, sdef.stressHeal) * 8.5 * p.survivalWeight;
                    else score += Math.min(t.stress, sdef.stressHeal) * 3.8;
                }

                if (sdef.regen != null) {
                    score += sdef.regen[0] * sdef.regen[1] * (t.atDeathsDoor ? 16.0 : 8.5) * p.survivalWeight;
                }
                if (sdef.curesBleed) {
                    int bleedSum = 0;
                    for (ActiveDot d : t.dots) if (d.kind == Models.DOT_BLEED) bleedSum += d.amount * d.roundsLeft;
                    score += bleedSum * 9.0;
                }
                if (sdef.curesBlight) {
                    int blightSum = 0;
                    for (ActiveDot d : t.dots) if (d.kind == Models.DOT_BLIGHT) blightSum += d.amount * d.roundsLeft;
                    score += blightSum * 9.0;
                }
                if (sdef.clearsStun && t.stunned) score += 240.0;
                if (sdef.targetStealthRounds > 0 && t.stealthRounds == 0) {
                    score += (t.atDeathsDoor ? 420.0 : 135.0) * p.survivalWeight;
                }
                if (sdef.targetBuffs.length > 0) score += 48.0 * sdef.targetBuffs.length * p.defenseWeight;
            }

            if (sdef.aegisBlocks > 0) {
                double aThreat = lookupThreat(actor.uid, threatCount);
                score += (actor.atDeathsDoor ? 980.0 : 155.0 + aThreat * 360.0) * sdef.aegisBlocks * p.survivalWeight;
            }
            if (sdef.stealthRounds > 0 && actor.stealthRounds == 0) {
                score += (actor.atDeathsDoor ? 760.0 : 185.0) * p.survivalWeight;
            }
            if (sdef.riposteRounds > 0 && actor.riposteRounds == 0) {
                score += 165.0 * p.defenseWeight;
            }
            if (sdef.partyBuffs.length > 0) {
                score += 44.0 * sdef.partyBuffs.length * engine.countLivingHeroes(team) * p.defenseWeight;
            }
            if (sdef.partyStressHeal > 0) {
                for (UnitState ally : engine.state.teams[team]) {
                    if (!ally.alive || ally.isCorpse) continue;
                    if (ally.stress >= 165) score += (ally.atDeathsDoor ? 650.0 : 310.0) * p.survivalWeight;
                    else if (ally.stress > 15) score += Math.min(ally.stress, sdef.partyStressHeal) * 3.8;
                }
            }
            if (sdef.selfHealMax > 0 || sdef.selfHealPct != 0.0) {
                double avgH = sdef.selfHealPct != 0.0 ? actor.maxHp * sdef.selfHealPct : (sdef.selfHealMin + sdef.selfHealMax) * 0.5;
                if (actor.atDeathsDoor && actor.hp <= 0) score += 1750.0 * p.survivalWeight;
                else score += Math.min(actor.maxHp - actor.hp, avgH) * 12.0 * p.survivalWeight;
            }
            if (sdef.selfBuffs.length > 0 && sdef.partyBuffs.length == 0 && sdef.aegisBlocks == 0 && sdef.stealthRounds == 0) {
                score += (engine.state.roundNum <= 2 ? 120.0 : 65.0) * p.defenseWeight;
            }
            if (maxAllyThreat >= 1.0 && score < 500.0) score -= 90.0;
        }
        if (sdef.selfMove != 0 && formationWeight > 0.0) {
            double selfShiftDelta = teamFormationShiftDelta(engine, team, actor, sdef.selfMove);
            if (selfShiftDelta != 0.0) {
                score -= selfShiftDelta * 0.70 * formationWeight;
            }
        }
        if (strategicEval && "SKILL".equals(act.kind)) {
            score = applyStrategicActionEv(engine, act, actor, sdef, team, maxAllyThreat, anyAllyDd, score);
        }
        return score;
    }

    private double applyStrategicActionEv(
            ArenaEngine engine, Action action, UnitState actor, SkillDef skill,
            int team, double maxThreat, boolean allyDd, double score
    ) {
        int living = engine.countLivingHeroes(team);
        if ("bolster".equals(skill.id)) score += living * 145.0 * setupWeight;
        if ("bulwark_of_faith".equals(skill.id)) score += living * bulwarkPriority * setupWeight;
        if ("toxin_trickery".equals(skill.id)) score += engine.state.roundNum <= 3 ? 170.0 : 30.0;
        if ("bellow".equals(skill.id)) {
            for (UnitState enemy : engine.state.teams[1 - team]) {
                if (!enemy.alive || enemy.isCorpse) continue;
                double damagePenalty = engine.buffSum(enemy, StatKey.DMG_PCT);
                if (damagePenalty > -0.6) score += enemy.actedThisRound ? 30.0 : 65.0;
            }
        }
        if (skill.regen != null) {
            for (String uid : action.targetUids) {
                UnitState target = engine.findUnit(uid);
                int existing = 0;
                for (ActiveDot dot : target.dots) if (dot.kind == Models.DOT_REGEN)
                    existing += dot.amount * Math.min(3, dot.roundsLeft);
                if (target.hp > target.maxHp * 0.75 && existing >= 12) score -= 320.0;
            }
        }
        if (skill.guardRounds > 0 && !allyDd && maxThreat < 0.22) score += guardBias;
        if (skill.stunChance > 0 && controlBonus != 0.0) {
            for (String uid : action.targetUids) {
                UnitState target = engine.findUnit(uid);
                if (target.alive && !target.isCorpse && !target.stunned && !target.actedThisRound) score += controlBonus;
            }
        }
        return score;
    }

    private record ScoredAction(double score, Action action) {}

    private List<ScoredAction> orderedCandidates(ArenaEngine engine, int team, int width, Action ttHint, int ply) {
        return orderedCandidates(engine, team, width, ttHint, ply, engine.getLegalActions(team));
    }

    private List<ScoredAction> orderedCandidates(ArenaEngine engine, int team, int width, Action ttHint, int ply, List<Action> legal) {
        int n = legal.size();
        if (n == 0) return Collections.emptyList();

        int threatCount = computeAllyThreats(engine, team);
        double maxAllyThreat = 0.0;
        for (int i = 0; i < threatCount; i++) {
            if (threatScratch[i] > maxAllyThreat) maxAllyThreat = threatScratch[i];
        }
        boolean anyAllyDd = false;
        String bExecUid = null;
        double bExecDb = -1.0;
        double sExecDb = -1.0;
        int unactedAllies = 0;
        ArrayList<UnitState> myUnits = engine.state.teams[team];
        for (int i = 0, mn = myUnits.size(); i < mn; i++) {
            UnitState u = myUnits.get(i);
            if (!u.alive || u.isCorpse) continue;
            if (u.atDeathsDoor && u.hp <= 0) anyAllyDd = true;
            if (!u.actedThisRound && !u.stunned) {
                unactedAllies++;
                double db = unitMaxDeathblowBonus(engine, u);
                if (db > bExecDb) {
                    sExecDb = bExecDb;
                    bExecDb = db;
                    bExecUid = u.uid;
                } else if (db > sExecDb) {
                    sExecDb = db;
                }
            }
        }
        bestExecUid = bExecUid;
        bestExecDbBonus = Math.max(0.0, bExecDb);
        secondExecDbBonus = Math.max(0.0, sExecDb);
        unactedAllyCount = unactedAllies;
        boolean oppHasCorpse = false;
        int eCache = 0;
        ArrayList<UnitState> oppUnits = engine.state.teams[1 - team];
        for (int i = 0, un = oppUnits.size(); i < un; i++) {
            UnitState u = oppUnits.get(i);
            if (!u.alive) continue;
            if (u.isCorpse) {
                oppHasCorpse = true;
            } else if (eCache < 4) {
                enemyUnitsCache[eCache] = u;
                engine.computeEffectiveStats(u, null, "", enemyStatsCache[eCache]);
                eCache++;
            }
        }
        enemyStatsCacheCount = eCache;

        Action k0 = (ply >= 0 && ply < MAX_KILLER_PLY) ? killers[ply][0] : null;
        Action k1 = (ply >= 0 && ply < MAX_KILLER_PLY) ? killers[ply][1] : null;

        // Stable bounded insertion: preserve legal-action order for equal scores.
        int limit = Math.min(n, width);
        ArrayList<ScoredAction> scored = new ArrayList<>(limit);
        for (int i = 0; i < n; i++) {
            Action a = legal.get(i);
            double ev = scoreActionEv(engine, a, team, threatCount, maxAllyThreat, anyAllyDd, oppHasCorpse);
            if (ttHint != null && ttHint.equals(a)) ev += 10000.0;
            else if (k0 != null && k0.equals(a)) ev += 120.0;
            else if (k1 != null && k1.equals(a)) ev += 60.0;
            int at = scored.size();
            while (at > 0 && Double.compare(ev, scored.get(at - 1).score) > 0) at--;
            if (at < limit) {
                if (scored.size() == limit) scored.remove(limit - 1);
                scored.add(at, new ScoredAction(ev, a));
            }
        }
        return scored;
    }

    private static boolean isDeterministicAction(ArenaEngine engine, Action act) {
        if ("PASS".equals(act.kind) || "MOVE".equals(act.kind)) return true;
        if (!"SKILL".equals(act.kind)) return false;
        UnitState actor = engine.findUnit(act.actorUid);
        if (actor == null) return false;
        SkillDef sdef = actor.heroDef.getSkill(act.skillId);
        if (sdef == null) return false;
        if (sdef.isTransform) return true;
        if (!"enemy".equals(sdef.targetTeam)) {
            if (sdef.randomTarget) return false;
            if (sdef.healMax > sdef.healMin) return false;
            if (sdef.selfHealMax > sdef.selfHealMin) return false;
            if (sdef.stressHeal > 0 && sdef.stressHealChance < 100) return false;
            return true;
        }
        return false;
    }

    private double evalSingleOutcome(
            ArenaEngine engine, Action act, int depth, int ply,
            double alpha, double beta, int myTeam, long seed, int outcomeMode
    ) {
        ArenaEngine sim = engine.cloneForSearch(simulationScratch);
        sim.rng.reseed(seed);
        sim.searchOutcomeMode = outcomeMode;
        sim.step(act);
        // Free action (e.g. Abomination Transform) does not end the turn: keep search depth so follow-up is evaluated!
        int nextDepth = (sim.state.lockedActorUid != null && ply < MAX_KILLER_PLY - 1) ? depth : (depth - 1);
        return alphabeta(sim, nextDepth, ply + 1, alpha, beta, myTeam);
    }

    // ------------------------------------------------------------------ Hybrid Exact-Probability Expectimax / Alpha-Beta
    private double simulateExpectedValue(
            ArenaEngine engine, Action act, int depth, int ply,
            double alpha, double beta, int myTeam, int samples
    ) {
        long baseSeed = (engine.state.turnCount + 1L) * 10007L + (act.hashCode() & 0x7FFFFFFFL);

        // 1. Deterministic actions (MOVE, PASS, Transform, buffs, fixed heals): 1 simulation is 100% exact!
        if (isDeterministicAction(engine, act)) {
            return evalSingleOutcome(engine, act, depth, ply, alpha, beta, myTeam, baseSeed, 0);
        }

        // 2. Single-target attack: exact probability-weighted 2-branch Expectimax!
        if ("SKILL".equals(act.kind) && act.targetUids.size() == 1) {
            UnitState actor = engine.findUnit(act.actorUid);
            UnitState rawT = engine.findUnit(act.targetUids.get(0));
            if (actor != null && rawT != null && rawT.alive && !rawT.isCorpse) {
                SkillDef sdef = actor.heroDef.getSkill(act.skillId);
                if (sdef != null && "enemy".equals(sdef.targetTeam) && !sdef.isAoe && !sdef.randomTarget && sdef.dmgMod > -1.0) {
                    UnitState t = rawT;
                    if (!sdef.ignoresGuard && rawT.guardedByUid != null) {
                        UnitState g = engine.findUnit(rawT.guardedByUid);
                        if (g != null && g.alive && !g.isCorpse) t = g;
                    }
                    if (!t.isCorpse && t.aegisBlocks == 0) {
                        EffectiveStats astats = engine.computeEffectiveStats(actor, t, sdef.isRanged ? "ranged" : "melee", scratchA);
                        EffectiveStats tstats = engine.computeEffectiveStats(t, null, "", scratchB);
                        double pHit = ArenaEngine.computeHitChance(sdef.acc + astats.values[StatKey.ACC], tstats.values[StatKey.DODGE]) / 100.0;

                        // 2a. Death's Door execution (ply <= 3 or multi-sample): exact pKill * V(kill) + (1 - pKill) * V(survive)
                        if (t.atDeathsDoor && t.hp <= 0 && (ply <= 3 || samples > 1)) {
                            double dbBonus = sdef.deathblowBonus + astats.values[StatKey.DEATHBLOW_CHANCE];
                            if (t.markedRounds > 0 && ("aimed_shot".equals(sdef.id) || "sniper_shot".equals(sdef.id))) dbBonus += 30.0;
                            if (t.isAfflicted && "bloodlet".equals(sdef.id)) dbBonus += 40.0;
                            double pDb = ArenaEngine.clamp(100.0 - tstats.values[StatKey.DEATHBLOW_RES] + dbBonus, 5.0, 100.0) / 100.0;
                            double pKill = pHit * pDb;
                            if (pKill >= 0.999) {
                                return evalSingleOutcome(engine, act, depth, ply, alpha, beta, myTeam, baseSeed, 1);
                            }
                            if (pKill <= 0.001) {
                                return evalSingleOutcome(engine, act, depth, ply, alpha, beta, myTeam, baseSeed, 2);
                            }
                            double vKill = evalSingleOutcome(engine, act, depth, ply, -1e12, 1e12, myTeam, baseSeed, 1);
                            if (checkDeadline()) return vKill;
                            double invP = 1.0 - pKill;
                            double a2 = Math.max(-1e12, (alpha - pKill * vKill) / invP);
                            double b2 = Math.min(1e12, (beta - pKill * vKill) / invP);
                            int survMode = (pHit * (1.0 - pDb) >= (1.0 - pHit)) ? 2 : 3;
                            double vSurv = evalSingleOutcome(engine, act, depth, ply, a2, b2, myTeam, baseSeed + 7919L, survMode);
                            return pKill * vKill + invP * vSurv;
                        }

                        // 2b. Normal single-target attack (root shallow depths OR low-HP DD-setup at root):
                        // exact pHit * V(expHit) + (1-pHit) * V(miss) with exact Star1 alpha-beta bounds!
                        if (samples > 1 || (ply == 1 && t.hp <= 15)) {
                            double vHit = evalSingleOutcome(engine, act, depth, ply, -1e12, 1e12, myTeam, baseSeed, 4);
                            if (checkDeadline() || pHit >= 0.999) return vHit;
                            double invP = 1.0 - pHit;
                            double a2 = Math.max(-1e12, (alpha - pHit * vHit) / invP);
                            double b2 = Math.min(1e12, (beta - pHit * vHit) / invP);
                            double vMiss = evalSingleOutcome(engine, act, depth, ply, a2, b2, myTeam, baseSeed + 7919L, 3);
                            return pHit * vHit + invP * vMiss;
                        }
                    }
                }
            }
        }

        // 3. Fallback (AoE / deep plies / random-target skills): fast Monte Carlo sampling
        double total = 0.0;
        int done = 0;
        for (int sIdx = 0; sIdx < samples; sIdx++) {
            if (done > 0 && checkDeadline()) break;
            double val = evalSingleOutcome(engine, act, depth, ply, alpha, beta, myTeam, baseSeed + sIdx * 7919L, 0);
            total += val;
            done++;
        }
        return total / Math.max(1, done);
    }

    private double alphabeta(ArenaEngine engine, int depth, int ply, double alpha, double beta, int myTeam) {
        nodesVisited++;
        if (engine.state.finished || depth <= 0 || checkDeadline()) {
            return evaluateState(engine, myTeam);
        }

        // Transposition Table probe
        long hash = searchStateHash(engine);
        int idx = (int) (hash & TT_MASK);
        Action ttHint = null;
        if (ttGen[idx] == searchGen && ttKey[idx] == hash) {
            ttHint = ttMove[idx];
            if (ttDepth[idx] >= depth) {
                ttHits++;
                byte flag = ttFlag[idx];
                double val = ttVal[idx];
                if (flag == TT_EXACT) return val;
                if (flag == TT_LOWER) {
                    if (val > alpha) alpha = val;
                } else if (flag == TT_UPPER) {
                    if (val < beta) beta = val;
                }
                if (alpha >= beta) return val;
            }
        }

        int curTeam = engine.state.activeTeam;
        boolean maximizing = (curTeam == myTeam);
        int width = (depth >= 3) ? branchWidth : (depth == 2 ? Math.max(3, branchWidth - 1) : Math.max(2, branchWidth - 2));
        List<ScoredAction> cands = orderedCandidates(engine, curTeam, width, ttHint, ply);
        if (cands.isEmpty()) return evaluateState(engine, myTeam);

        double origAlpha = alpha;
        double origBeta = beta;
        Action bestMove = cands.get(0).action;
        double best;

        if (maximizing) {
            best = -1e12;
            for (int i = 0, cn = cands.size(); i < cn; i++) {
                if (i > 0 && checkDeadline()) break;
                Action act = cands.get(i).action;
                double val;
                if (i == 0) {
                    val = simulateExpectedValue(engine, act, depth, ply, alpha, beta, myTeam, 1);
                } else {
                    // Principal Variation Search (PVS) + Late Move Reduction (LMR) for late-ordered moves
                    boolean lmr = depth >= 3 && i >= 2 && (ttHint == null || !ttHint.equals(act));
                    int searchDepth = lmr ? (depth - 1) : depth;
                    val = simulateExpectedValue(engine, act, searchDepth, ply, alpha, alpha + 0.01, myTeam, 1);
                    if (!timeUp && val > alpha && (lmr || val < beta)) {
                        val = simulateExpectedValue(engine, act, depth, ply, alpha, beta, myTeam, 1);
                    }
                }
                if (val > best) {
                    best = val;
                    bestMove = act;
                }
                if (best > alpha) alpha = best;
                if (beta <= alpha) {
                    recordKiller(ply, act);
                    break;
                }
            }
            if (best <= -1e11) return evaluateState(engine, myTeam);
        } else {
            best = 1e12;
            for (int i = 0, cn = cands.size(); i < cn; i++) {
                if (i > 0 && checkDeadline()) break;
                Action act = cands.get(i).action;
                double val;
                if (i == 0) {
                    val = simulateExpectedValue(engine, act, depth, ply, alpha, beta, myTeam, 1);
                } else {
                    boolean lmr = depth >= 3 && i >= 2 && (ttHint == null || !ttHint.equals(act));
                    int searchDepth = lmr ? (depth - 1) : depth;
                    val = simulateExpectedValue(engine, act, searchDepth, ply, beta - 0.01, beta, myTeam, 1);
                    if (!timeUp && val < beta && (lmr || val > alpha)) {
                        val = simulateExpectedValue(engine, act, depth, ply, alpha, beta, myTeam, 1);
                    }
                }
                if (val < best) {
                    best = val;
                    bestMove = act;
                }
                if (best < beta) beta = best;
                if (beta <= alpha) {
                    recordKiller(ply, act);
                    break;
                }
            }
            if (best >= 1e11) return evaluateState(engine, myTeam);
        }

        // Store in Transposition Table if search wasn't aborted mid-node
        if (!timeUp) {
            byte flag;
            if (best <= origAlpha) flag = TT_UPPER;
            else if (best >= origBeta) flag = TT_LOWER;
            else flag = TT_EXACT;
            ttKey[idx] = hash;
            ttVal[idx] = best;
            ttDepth[idx] = (byte) depth;
            ttFlag[idx] = flag;
            ttGen[idx] = searchGen;
            ttMove[idx] = bestMove;
        }
        return best;
    }

    private void recordKiller(int ply, Action act) {
        if (ply < 0 || ply >= MAX_KILLER_PLY) return;
        if (!act.equals(killers[ply][0])) {
            killers[ply][1] = killers[ply][0];
            killers[ply][0] = act;
        }
    }

    /** Allows specialized evaluators to include additional state in their transposition keys. */
    protected long searchStateHash(ArenaEngine engine) {
        if (!detailedTtHash) return engine.computeStateHash();
        long h = engine.computeStateHash() ^ (engine.state.turnCount * 0x9E3779B97F4A7C15L);
        for (int t = 0; t < 2; t++) {
            ArrayList<UnitState> team = engine.state.teams[t];
            for (int ui = 0, un = team.size(); ui < un; ui++) {
                UnitState u = team.get(ui);
                h = Long.rotateLeft(h, 11) ^ u.uid.hashCode();
                if (!u.skillUses.isEmpty()) h = h * 31 + u.skillUses.hashCode();
                if (!u.skillCooldowns.isEmpty()) h = h * 31 + u.skillCooldowns.hashCode();
                if (u.guardingUid != null) h = h * 31 + u.guardingUid.hashCode();
                if (u.guardedByUid != null) h = h * 31 + u.guardedByUid.hashCode();
                if (u.traitId != null) h = h * 31 + u.traitId.hashCode();
                h = h * 31 + u.corpseRounds + u.freshStatusMask * 17L;
                ArrayList<ActiveBuff> buffs = u.buffs;
                for (int bi = 0, bn = buffs.size(); bi < bn; bi++) {
                    ActiveBuff b = buffs.get(bi);
                    h = Long.rotateLeft(h, 9) ^ Double.doubleToLongBits(b.value);
                    h = h * 31 + b.statId + b.condBit * 17L + b.roundsLeft * 127L + (b.freshThisTurn ? 1 : 0);
                }
                ArrayList<ActiveDot> dots = u.dots;
                for (int di = 0, dn = dots.size(); di < dn; di++) {
                    ActiveDot d = dots.get(di);
                    h = Long.rotateLeft(h, 7) ^ (d.kind + d.amount * 31L + d.roundsLeft * 997L);
                }
            }
        }
        return h;
    }

    @Override
    public boolean requiresUnitObservations() { return getClass() != CircusSearchBot.class; }

    @Override
    public Action chooseAction(Observation obs, ArenaEngine engine) {
        List<Action> legal = obs.legalActions;
        if (legal == null || legal.isEmpty()) {
            throw new IllegalStateException("No legal actions available");
        }
        if (legal.size() == 1 || engine == null) {
            return legal.get(0);
        }

        long tStart = System.nanoTime();
        this.deadlineNs = tStart + (long) (timeBudgetSec * 1_000_000_000.0);
        this.timeUp = false;
        this.nodesVisited = 0;
        this.ttHits = 0;
        this.searchGen++;
        for (int i = 0; i < MAX_KILLER_PLY; i++) {
            killers[i][0] = null;
            killers[i][1] = null;
        }

        int myTeam = obs.viewerTeam;
        List<ScoredAction> initialRanked = orderedCandidates(engine, myTeam, rootWidth, null, 0, legal);
        if (initialRanked.isEmpty()) return legal.get(0);
        if (initialRanked.size() == 1) return initialRanked.get(0).action;

        // Keep mutable root order + analytical EV prior map
        ArrayList<ScoredAction> rootMoves = new ArrayList<>(initialRanked);
        Map<Action, Double> priorMap = new HashMap<>(rootMoves.size() * 2);
        Map<Action, Double> lastIterScore = new HashMap<>(rootMoves.size() * 2);
        for (ScoredAction sa : rootMoves) {
            priorMap.put(sa.action, sa.score);
            lastIterScore.put(sa.action, sa.score);
        }

        Action bestAction = rootMoves.get(0).action;
        double bestOverallScore = -1e12;
        int completedDepth = 0;

        for (int depth = 1; depth <= maxDepth; depth++) {
            if (System.nanoTime() >= deadlineNs) break;
            Action iterBestAct = bestAction;
            double iterBestVal = -1e12;
            double alpha = -1e12;
            boolean completedAll = true;
            int rootSamples = (depth == 1) ? 3 : (depth == 2 ? 2 : 1);
            double priorW = (depth == 1) ? 0.25 : (depth == 2 ? 0.12 : 0.04);

            for (int idx = 0, rn = rootMoves.size(); idx < rn; idx++) {
                if (System.nanoTime() >= deadlineNs) {
                    completedAll = false;
                    timeUp = true;
                    break;
                }
                Action act = rootMoves.get(idx).action;
                double priorSc = priorMap.get(act);
                double adjAlpha = alpha - priorSc * priorW;
                double treeVal;
                if (idx == 0 || depth <= 2) {
                    treeVal = simulateExpectedValue(engine, act, depth, 1, adjAlpha, 1e12, myTeam, rootSamples);
                } else {
                    // Root PVS: verify non-PV moves with null window first
                    treeVal = simulateExpectedValue(engine, act, depth, 1, adjAlpha, adjAlpha + 0.01, myTeam, rootSamples);
                    if (!timeUp && treeVal > adjAlpha) {
                        treeVal = simulateExpectedValue(engine, act, depth, 1, adjAlpha, 1e12, myTeam, rootSamples);
                    }
                }
                double combined = treeVal + priorSc * priorW;
                lastIterScore.put(act, combined);
                if (combined > iterBestVal) {
                    iterBestVal = combined;
                    iterBestAct = act;
                }
                if (combined > alpha) {
                    alpha = combined;
                }
            }

            if (completedAll || iterBestVal > bestOverallScore) {
                bestAction = iterBestAct;
                bestOverallScore = iterBestVal;
                if (completedAll) completedDepth = depth;
            }

            final Action pvAct = bestAction;
            rootMoves.sort((a, b) -> {
                if (a.action.equals(pvAct)) return -1;
                if (b.action.equals(pvAct)) return 1;
                return Double.compare(lastIterScore.getOrDefault(b.action, -1e12), lastIterScore.getOrDefault(a.action, -1e12));
            });
        }

        this.lastDepthReached = completedDepth;
        this.lastNodesVisited = nodesVisited;
        this.lastTtHits = ttHits;
        this.lastElapsedMs = (System.nanoTime() - tStart) / 1_000_000.0;
        return bestAction;
    }
}
