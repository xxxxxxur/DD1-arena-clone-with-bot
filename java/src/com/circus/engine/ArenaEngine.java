package com.circus.engine;

import com.circus.engine.Models.*;

import java.util.*;

/**
 * Authoritative Pure Java Butcher's Circus Combat Engine.
 *
 * 100% faithful to engine/combat.py game rules and event contracts.
 * Optimized for ultra-high performance:
 * - Primitive bitmask conditional buff evaluation (zero HashMap/String allocations)
 * - Fast cloneable SplitMix64/Xoroshiro128++ PRNG
 * - Zero-event allocation search mode via cloneForSearch()
 */
public final class ArenaEngine {
    public static final int CORPSE_HP = 25;
    public static final int CORPSE_MAX_ROUNDS = 3;
    public static final int MAX_STRESS = 200;
    public static final int RESOLVE_CHECK_STRESS = 100;
    public static final int HEART_ATTACK_RESET_STRESS = 170;
    public static final double BASE_VIRTUE_CHANCE = 0.0;
    public static final String SECOND_TURN_DODGE_SOURCE = "round1_second_dodge";
    public static final double SECOND_TURN_DODGE_BONUS = 15.0;

    public final long seed;
    public final FastRng rng;
    public final GameState state;
    public final String[] teamNames;
    public final List<CombatEvent> eventsHistory;
    private final List<CombatEvent> initEvents;
    public final boolean recordEvents;

    // Reusable scratchpad stats objects to avoid allocating during step()
    private final EffectiveStats scratchStatsA;
    private final EffectiveStats scratchStatsB;
    private final EffectiveStats scratchStatsC;

    /**
     * Search-only one-shot outcome override for exact probability-weighted Expectimax branches.
     * 0 = normal RNG; 1 = force hit + deathblow kill; 2 = force hit + expected dmg + deathblow resist;
     * 3 = force miss; 4 = force hit + expected dmg. Always reset to 0 after step().
     */
    public int searchOutcomeMode = 0;

    /** Reusable calculation buffers. Confine to one search thread; step clones sequentially. */
    public static final class SearchScratch {
        private final EffectiveStats a = new EffectiveStats();
        private final EffectiveStats b = new EffectiveStats();
        private final EffectiveStats c = new EffectiveStats();
    }

    /**
     * Fast, deterministic, cloneable 128-bit PRNG (xoshiro128++ / SplitMix64 seeded).
     */
    public static final class FastRng {
        private long s0;
        private long s1;

        public FastRng(long seed) {
            reseed(seed);
        }

        private FastRng(long s0, long s1) {
            this.s0 = s0;
            this.s1 = s1;
        }

        public void reseed(long seed) {
            long z = seed + 0x9E3779B97F4A7C15L;
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            this.s0 = z ^ (z >>> 31);
            z = seed + 0x3C6EF372FE94F82AL;
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            this.s1 = z ^ (z >>> 31);
            if ((this.s0 | this.s1) == 0L) this.s0 = 1L;
        }

        public FastRng copy() {
            return new FastRng(s0, s1);
        }

        public long nextLong() {
            long a = s0;
            long b = s1;
            long result = Long.rotateLeft(a + b, 17) + a;
            b ^= a;
            s0 = Long.rotateLeft(a, 49) ^ b ^ (b << 21);
            s1 = Long.rotateLeft(b, 28);
            return result;
        }

        public double nextDouble() {
            return (nextLong() >>> 11) * 0x1.0p-53;
        }

        public int nextInt(int bound) {
            return (int) Math.floorMod(nextLong(), bound);
        }

        public int nextIntInclusive(int lo, int hi) {
            if (hi <= lo) return lo;
            return lo + nextInt(hi - lo + 1);
        }

        public double nextUniform(double lo, double hi) {
            return lo + (hi - lo) * nextDouble();
        }
    }

    public static double clamp(double val, double lo, double hi) {
        return val < lo ? lo : (val > hi ? hi : val);
    }

    /**
     * Hit chance (%) = (ACC - DODGE) + 5%.
     * If (ACC - DODGE) < 5%, hit chance is 5%.
     * If (ACC - DODGE) > 95% (or >= 95%), hit chance is 100%.
     */
    public static double computeHitChance(double acc, double dodge) {
        double diff = acc - dodge;
        if (diff < 5.0) return 5.0;
        if (diff >= 95.0) return 100.0;
        return diff + 5.0;
    }

    /** Matches Python 3 built-in round() (round-half-to-even). */
    public static int pyRound(double v) {
        return (int) Math.rint(v);
    }

    public static String validateTeamConfig(TeamConfig cfg) {
        GameData gd = GameData.get();
        int n = Math.min(4, cfg.heroes.size());
        if (n < 1) {
            return "Team must contain at least 1 hero.";
        }
        Set<String> seen = new HashSet<>(8);
        for (int i = 0; i < n; i++) {
            HeroLoadout h = cfg.heroes.get(i);
            HeroDef hdef = gd.heroes.get(h.heroId);
            if (hdef == null) {
                return "Unknown hero class: " + h.heroId;
            }
            if (!seen.add(h.heroId)) {
                return "Duplicate hero class not allowed: " + hdef.nameZh + " (" + hdef.nameEn + ")";
            }
        }
        return null;
    }

    public ArenaEngine(TeamConfig team0, TeamConfig team1, Long seed, int maxRounds) {
        this.scratchStatsA = new EffectiveStats();
        this.scratchStatsB = new EffectiveStats();
        this.scratchStatsC = new EffectiveStats();
        this.seed = (seed != null) ? seed : new Random().nextInt(Integer.MAX_VALUE);
        this.rng = new FastRng(this.seed);
        this.recordEvents = true;
        this.eventsHistory = new ArrayList<>(64);
        this.teamNames = new String[]{team0.name, team1.name};

        ArrayList<UnitState> u0 = buildTeamUnits(team0, 0);
        ArrayList<UnitState> u1 = buildTeamUnits(team1, 1);

        int coinflipWinner = this.rng.nextDouble() < 0.5 ? 0 : 1;
        this.state = new GameState(1, coinflipWinner, coinflipWinner, coinflipWinner, u0, u1, maxRounds, this.seed);

        // Butcher's Circus rule: the team acting second in Round 1 receives +15 DODGE until the end of Round 1.
        int secondTeam = 1 - coinflipWinner;
        for (UnitState u : this.state.teams[secondTeam]) {
            if (u.alive && !u.isCorpse) {
                u.buffs.add(new ActiveBuff(
                        StatKey.DODGE, StatKey.COND_NONE, "dodge",
                        SECOND_TURN_DODGE_BONUS, 1, SECOND_TURN_DODGE_SOURCE, false, false
                ));
            }
        }

        this.initEvents = new ArrayList<>(4);
        this.initEvents.add(new CombatEvent("MATCH_START", mapOf(
                "seed", this.seed,
                "coinflip_winner", coinflipWinner,
                "team_names", List.of(this.teamNames[0], this.teamNames[1])
        )));
        this.initEvents.add(new CombatEvent("COINFLIP", mapOf("winner", coinflipWinner)));
        this.initEvents.add(new CombatEvent("ROUND_START", mapOf("round", 1, "first_team", coinflipWinner)));
        this.eventsHistory.addAll(this.initEvents);
    }

    public ArenaEngine(TeamConfig team0, TeamConfig team1, long seed) {
        this(team0, team1, seed, 40);
    }

    private ArenaEngine(ArenaEngine src, boolean recordEvents) {
        this(src, recordEvents, null);
    }

    private ArenaEngine(ArenaEngine src, boolean recordEvents, SearchScratch scratch) {
        this.scratchStatsA = scratch == null ? new EffectiveStats() : scratch.a;
        this.scratchStatsB = scratch == null ? new EffectiveStats() : scratch.b;
        this.scratchStatsC = scratch == null ? new EffectiveStats() : scratch.c;
        this.seed = src.seed;
        this.rng = src.rng.copy();
        this.state = src.state.copy();
        this.teamNames = src.teamNames;
        this.recordEvents = recordEvents;
        this.eventsHistory = recordEvents ? new ArrayList<>(32) : Collections.emptyList();
        this.initEvents = Collections.emptyList();
    }

    /** Full clone preserving event recording (for parity tests / replay branching). */
    public ArenaEngine cloneEngine() {
        return new ArenaEngine(this, true);
    }

    /** Ultra-fast zero-event-allocation clone for MCTS / Alpha-Beta / Expectimax search bots. */
    public ArenaEngine cloneForSearch() {
        return new ArenaEngine(this, false);
    }

    /** Clone combat state independently while reusing caller-owned step calculation buffers. */
    public ArenaEngine cloneForSearch(SearchScratch scratch) {
        return new ArenaEngine(this, false, Objects.requireNonNull(scratch));
    }

    public List<CombatEvent> consumeInitEvents() {
        List<CombatEvent> ev = new ArrayList<>(initEvents);
        if (!initEvents.isEmpty()) initEvents.clear();
        return ev;
    }

    private static ArrayList<UnitState> buildTeamUnits(TeamConfig cfg, int teamId) {
        String err = validateTeamConfig(cfg);
        if (err != null) {
            throw new IllegalArgumentException(err);
        }
        GameData gd = GameData.get();
        int count = Math.min(4, cfg.heroes.size());
        ArrayList<UnitState> units = new ArrayList<>(count);
        for (int idx = 0; idx < count; idx++) {
            HeroLoadout loadout = cfg.heroes.get(idx);
            HeroDef hdef = gd.heroes.get(loadout.heroId);

            // Dedup & validate trinkets (max 2)
            LinkedHashSet<String> tSet = new LinkedHashSet<>(4);
            for (String tid : loadout.trinkets) {
                TrinketDef td = gd.trinkets.get(tid);
                if (td != null && (td.heroClass.isEmpty() || td.heroClass.equals(hdef.id))) {
                    tSet.add(tid);
                    if (tSet.size() >= 2) break;
                }
            }
            List<String> trinkets = List.copyOf(tSet);
            TrinketDef[] trinketDefs = new TrinketDef[trinkets.size()];
            int tFlags = 0;
            for (int ti = 0; ti < trinkets.size(); ti++) {
                String tid = trinkets.get(ti);
                trinketDefs[ti] = gd.trinkets.get(tid);
                tFlags |= trinketFlagFor(tid);
            }

            // Build equipped skills
            List<String> skills;
            if ("abomination".equals(hdef.id)) {
                List<String> all = new ArrayList<>(hdef.skills.length);
                for (SkillDef s : hdef.skills) all.add(s.id);
                skills = List.copyOf(all);
            } else {
                LinkedHashSet<String> sSet = new LinkedHashSet<>(6);
                for (String sid : loadout.skills) {
                    SkillDef s = hdef.getSkill(sid);
                    if (s != null) {
                        sSet.add(s.id);
                        if (sSet.size() >= 4) break;
                    }
                }
                if (sSet.isEmpty()) {
                    Collections.addAll(sSet, hdef.defaultSkills);
                }
                skills = List.copyOf(sSet);
            }
            SkillDef[] skillDefs = new SkillDef[skills.size()];
            for (int si = 0; si < skills.size(); si++) {
                skillDefs[si] = hdef.getSkill(skills.get(si));
            }

            ArrayList<ActiveBuff> buffs = new ArrayList<>(8);
            double hpMult = 1.0;
            int hpFlat = 0;
            int startStealth = 0;
            int startAegis = 0;
            for (TrinketDef tdef : trinketDefs) {
                for (BuffDef bd : tdef.buffs) {
                    if (bd.statId == StatKey.MAX_HP_PCT && bd.condBit == 0) {
                        hpMult += bd.value;
                    } else if (bd.statId == StatKey.MAX_HP_FLAT && bd.condBit == 0) {
                        hpFlat += (int) bd.value;
                    } else {
                        buffs.add(ActiveBuff.permanentFromDef(bd));
                    }
                }
                startStealth = Math.max(startStealth, tdef.startStealth);
                startAegis += tdef.startAegis;
            }

            int maxHp = Math.max(10, pyRound((hdef.maxHp + hpFlat) * Math.max(0.5, hpMult)));
            String unitName = (loadout.name != null && !loadout.name.isEmpty()) ? loadout.name : hdef.nameEn;
            String variant = (loadout.variant != null && !loadout.variant.isEmpty())
                    ? loadout.variant.toUpperCase(Locale.ROOT) : "A";
            units.add(new UnitState(
                    "t" + teamId + "_" + idx,
                    teamId,
                    hdef,
                    unitName,
                    variant,
                    idx + 1,
                    maxHp,
                    skills,
                    skillDefs,
                    trinkets,
                    trinketDefs,
                    tFlags,
                    startStealth,
                    startAegis,
                    buffs
            ));
        }
        return units;
    }

    private static int trinketFlagFor(String tid) {
        return switch (tid) {
            case "crown_of_thorns" -> UnitState.TF_CROWN_OF_THORNS;
            case "taste_of_grandeur" -> UnitState.TF_TASTE_OF_GRANDEUR;
            case "insignia_of_rank" -> UnitState.TF_INSIGNIA_OF_RANK;
            case "gauntlet_of_absolution" -> UnitState.TF_GAUNTLET_OF_ABSOLUTION;
            case "well_balanced_stiletto" -> UnitState.TF_WELL_BALANCED_STILETTO;
            case "harlequin_masque" -> UnitState.TF_HARLEQUIN_MASQUE;
            case "sacrificial_kris" -> UnitState.TF_SACRIFICIAL_KRIS;
            case "ghoul_claw" -> UnitState.TF_GHOUL_CLAW;
            case "veteran_gauntlet" -> UnitState.TF_VETERAN_GAUNTLET;
            case "shield_spike" -> UnitState.TF_SHIELD_SPIKE;
            case "bleeding_skull" -> UnitState.TF_BLEEDING_SKULL;
            case "tincture_of_iodine" -> UnitState.TF_TINCTURE_OF_IODINE;
            default -> 0;
        };
    }

    // ------------------------------------------------------------------ Helpers & Stat Computation
    public UnitState findUnit(String uid) {
        if (uid == null || uid.isEmpty()) return null;
        // Fast path for standard "t0_X" / "t1_X" uids
        if (uid.length() >= 4 && uid.charAt(0) == 't') {
            int t = uid.charAt(1) - '0';
            if (t == 0 || t == 1) {
                ArrayList<UnitState> list = state.teams[t];
                for (int i = 0, n = list.size(); i < n; i++) {
                    UnitState u = list.get(i);
                    if (u.uid.equals(uid)) return u;
                }
                return null;
            }
        }
        for (int t = 0; t < 2; t++) {
            ArrayList<UnitState> list = state.teams[t];
            for (int i = 0, n = list.size(); i < n; i++) {
                UnitState u = list.get(i);
                if (u.uid.equals(uid)) return u;
            }
        }
        return null;
    }

    public List<UnitState> livingHeroes(int team) {
        ArrayList<UnitState> src = state.teams[team];
        ArrayList<UnitState> out = new ArrayList<>(4);
        for (int i = 0, n = src.size(); i < n; i++) {
            UnitState u = src.get(i);
            if (u.alive && !u.isCorpse) out.add(u);
        }
        return out;
    }

    public int countLivingHeroes(int team) {
        ArrayList<UnitState> src = state.teams[team];
        int c = 0;
        for (int i = 0, n = src.size(); i < n; i++) {
            UnitState u = src.get(i);
            if (u.alive && !u.isCorpse) c++;
        }
        return c;
    }

    public List<UnitState> activeSlots(int team) {
        ArrayList<UnitState> src = state.teams[team];
        ArrayList<UnitState> out = new ArrayList<>(4);
        for (int i = 0, n = src.size(); i < n; i++) {
            UnitState u = src.get(i);
            if (u.alive) out.add(u);
        }
        return out;
    }

    public int countActiveSlots(int team) {
        ArrayList<UnitState> src = state.teams[team];
        int c = 0;
        for (int i = 0, n = src.size(); i < n; i++) {
            if (src.get(i).alive) c++;
        }
        return c;
    }

    public void syncRanks(int team) {
        ArrayList<UnitState> src = state.teams[team];
        ArrayList<UnitState> alive = new ArrayList<>(4);
        ArrayList<UnitState> dead = new ArrayList<>(4);
        for (int i = 0, n = src.size(); i < n; i++) {
            UnitState u = src.get(i);
            if (u.alive) alive.add(u);
            else dead.add(u);
        }
        for (int i = 0, n = alive.size(); i < n; i++) alive.get(i).rank = i + 1;
        for (int i = 0, n = dead.size(); i < n; i++) dead.get(i).rank = 0;
        src.clear();
        src.addAll(alive);
        src.addAll(dead);
    }

    public double buffSum(UnitState unit, int statId) {
        double total = 0.0;
        ArrayList<ActiveBuff> buffs = unit.buffs;
        for (int i = 0, n = buffs.size(); i < n; i++) {
            ActiveBuff b = buffs.get(i);
            if (b.condBit == 0 && b.statId == statId) {
                total += b.value;
            }
        }
        if (unit.atDeathsDoor) {
            if ("flagellant".equals(unit.heroId)) {
                if (statId == StatKey.DMG_PCT) total += 0.20;
                else if (statId == StatKey.CRIT) total += 5.0;
                else if (statId == StatKey.DEATHBLOW_RES) total += 10.0;
            } else {
                if (statId == StatKey.ACC) total -= 10.0;
                else if (statId == StatKey.DMG_PCT) total -= 0.20;
                else if (statId == StatKey.DODGE) total -= 10.0;
            }
        }
        return total;
    }

    public EffectiveStats computeEffectiveStats(UnitState unit, UnitState target, String attackType, EffectiveStats out) {
        out.reset();
        if (unit.isCorpse) {
            out.isCorpse = true;
            out.values[StatKey.STUN_RES] = 200.0;
            out.values[StatKey.BLEED_RES] = 200.0;
            out.values[StatKey.BLIGHT_RES] = 200.0;
            out.values[StatKey.DEBUFF_RES] = 200.0;
            out.values[StatKey.MOVE_RES] = 200.0;
            out.values[StatKey.STRESS_RES] = 200.0;
            return out;
        }

        double[] v = out.values;
        // 1. Single-pass unconditional buff summation
        ArrayList<ActiveBuff> buffs = unit.buffs;
        boolean hasCondBuffs = false;
        for (int i = 0, n = buffs.size(); i < n; i++) {
            ActiveBuff b = buffs.get(i);
            if (b.statId < 0) continue;
            if (b.condBit == 0) {
                v[b.statId] += b.value;
                out.presentMask |= (1L << b.statId);
            } else {
                hasCondBuffs = true;
            }
        }

        // Death's Door adjustments
        if (unit.atDeathsDoor) {
            if ("flagellant".equals(unit.heroId)) {
                v[StatKey.DMG_PCT] += 0.20;
                v[StatKey.CRIT] += 5.0;
                v[StatKey.DEATHBLOW_RES] += 10.0;
            } else {
                v[StatKey.ACC] -= 10.0;
                v[StatKey.DMG_PCT] -= 0.20;
                v[StatKey.DODGE] -= 10.0;
            }
        }

        HeroDef h = unit.heroDef;
        double baseCrit = ("abomination".equals(h.id) && unit.isBeast) ? h.beastCrit : h.crit;

        v[StatKey.DODGE] = h.dodge + v[StatKey.DODGE];
        v[StatKey.PROT] = h.prot + v[StatKey.PROT];
        v[StatKey.CRIT] = baseCrit + v[StatKey.CRIT];
        v[StatKey.DEATHBLOW_RES] = h.deathblowRes + v[StatKey.DEATHBLOW_RES];
        v[StatKey.STUN_RES] = h.stunRes + v[StatKey.STUN_RES];
        v[StatKey.BLEED_RES] = h.bleedRes + v[StatKey.BLEED_RES];
        v[StatKey.BLIGHT_RES] = h.blightRes + v[StatKey.BLIGHT_RES];
        v[StatKey.DEBUFF_RES] = h.debuffRes + v[StatKey.DEBUFF_RES];
        v[StatKey.MOVE_RES] = h.moveRes + v[StatKey.MOVE_RES];
        v[StatKey.STRESS_RES] = h.stressRes + v[StatKey.STRESS_RES];

        if (unit.isBeast) {
            v[StatKey.DMG_PCT] += 0.25;
        }

        // 2. Conditional buffs BEFORE final stat clamping so debuffs & conditional buffs interact accurately!
        if (hasCondBuffs) {
            int condMask = StatKey.attackTypeToBit(attackType);
            if (unit.stealthRounds > 0) condMask |= StatKey.COND_STEALTHED;
            if (unit.guardingUid != null) condMask |= StatKey.COND_GUARDING;
            if (unit.guardedByUid != null) condMask |= StatKey.COND_GUARDED;
            if (unit.atDeathsDoor) condMask |= StatKey.COND_DEATHS_DOOR;
            if (unit.hp < unit.maxHp * 0.2) condMask |= StatKey.COND_LOW20;
            if (unit.rank == 1) condMask |= StatKey.COND_RANK1;
            if (unit.traitId != null && !unit.isVirtuous) condMask |= StatKey.COND_AFFLICTED;
            if (unit.hasDotKind(Models.DOT_BLEED)) condMask |= StatKey.COND_BLEEDING;
            if (target != null) {
                if (target.markedRounds > 0) condMask |= StatKey.COND_TARGET_MARKED;
                if (target.traitId != null && !target.isVirtuous) condMask |= StatKey.COND_TARGET_AFFLICTED;
                if (target.hp < target.maxHp * 0.40) condMask |= StatKey.COND_TARGET_LOW40;
                if (target.hp < target.maxHp * 0.35) condMask |= StatKey.COND_TARGET_LOW35;
                if (target.hasDotKind(Models.DOT_BLEED)) condMask |= StatKey.COND_TARGET_BLEEDING;
                if (target.hasDotKind(Models.DOT_BLIGHT)) condMask |= StatKey.COND_TARGET_BLIGHTED;

                boolean anyTeammateActed = false;
                boolean allTeammatesActed = true;
                ArrayList<UnitState> myTeamList = state.teams[unit.team];
                for (int i = 0, n = myTeamList.size(); i < n; i++) {
                    UnitState tm = myTeamList.get(i);
                    if (tm.alive && !tm.isCorpse && tm != unit) {
                        if (tm.actedThisRound) anyTeammateActed = true;
                        else allTeammatesActed = false;
                    }
                }
                if (!anyTeammateActed) condMask |= StatKey.COND_FIRST;
                if (allTeammatesActed) condMask |= StatKey.COND_LAST;
            }

            for (int i = 0, n = buffs.size(); i < n; i++) {
                ActiveBuff b = buffs.get(i);
                if (b.condBit != 0 && b.statId >= 0 && (condMask & b.condBit) != 0) {
                    v[b.statId] += b.value;
                    out.presentMask |= (1L << b.statId);
                }
            }
        }

        // 3. Apply official Darkest Dungeon stat bounds AFTER all buffs & debuffs are summed
        // (Keep CRIT unclamped here so -CRIT debuffs properly subtract from skill crit_mod before [0..100] clamp!)
        v[StatKey.DODGE] = Math.max(0.0, v[StatKey.DODGE]);
        v[StatKey.PROT] = clamp(v[StatKey.PROT], 0.0, 0.80);
        v[StatKey.DEATHBLOW_RES] = clamp(v[StatKey.DEATHBLOW_RES], 0.0, 90.0);
        v[StatKey.STRESS_RES] = clamp(v[StatKey.STRESS_RES], -50.0, 80.0);
        return out;
    }

    public EffectiveStats getEffectiveStats(UnitState unit, UnitState target, String attackType) {
        return computeEffectiveStats(unit, target, attackType, new EffectiveStats());
    }

    public EffectiveStats getEffectiveStats(UnitState unit) {
        return computeEffectiveStats(unit, null, "", new EffectiveStats());
    }

    // ------------------------------------------------------------------ Selectable Actors & Legal Actions
    public List<UnitState> getSelectableActors(int team) {
        ArrayList<UnitState> src = state.teams[team];
        if (state.lockedActorUid != null && team == state.activeTeam) {
            for (int i = 0, n = src.size(); i < n; i++) {
                UnitState u = src.get(i);
                if (u.uid.equals(state.lockedActorUid) && u.alive && !u.isCorpse && !u.actedThisRound) {
                    return List.of(u);
                }
            }
            state.lockedActorUid = null;
        }
        ArrayList<UnitState> nonDazed = null;
        ArrayList<UnitState> all = null;
        for (int i = 0, n = src.size(); i < n; i++) {
            UnitState u = src.get(i);
            if (u.alive && !u.isCorpse && !u.actedThisRound) {
                if (all == null) all = new ArrayList<>(4);
                all.add(u);
                if (!u.dazed) {
                    if (nonDazed == null) nonDazed = new ArrayList<>(4);
                    nonDazed.add(u);
                }
            }
        }
        if (all == null) return Collections.emptyList();
        return (nonDazed != null) ? nonDazed : all;
    }

    public boolean hasSelectableActors(int team) {
        ArrayList<UnitState> src = state.teams[team];
        for (int i = 0, n = src.size(); i < n; i++) {
            UnitState u = src.get(i);
            if (u.alive && !u.isCorpse && !u.actedThisRound) return true;
        }
        return false;
    }

    public List<Action> getLegalActions() {
        return getLegalActions(state.activeTeam);
    }

    public List<Action> getLegalActions(int team) {
        if (state.finished || team != state.activeTeam) {
            return Collections.emptyList();
        }
        List<UnitState> actors = getSelectableActors(team);
        ArrayList<Action> actions = new ArrayList<>(32);
        int enemyTeam = 1 - team;
        int numSlots = countActiveSlots(team);

        for (int ai = 0, an = actors.size(); ai < an; ai++) {
            UnitState actor = actors.get(ai);
            if (actor.stunned) {
                actions.add(Action.pass(actor.uid));
                continue;
            }
            HeroDef hdef = actor.heroDef;
            SkillDef[] skills = actor.equippedSkillDefs;
            for (int si = 0; si < skills.length; si++) {
                SkillDef sdef = skills[si];
                if (sdef == null || !canUseSkill(actor, sdef)) continue;
                appendSkillActions(actions, actor, sdef, enemyTeam);
            }

            for (int delta = 1; delta <= hdef.moveForward; delta++) {
                if (actor.rank - delta >= 1) {
                    actions.add(Action.move(actor.uid, delta));
                }
            }
            for (int delta = 1; delta <= hdef.moveBackward; delta++) {
                if (actor.rank + delta <= numSlots) {
                    actions.add(Action.move(actor.uid, -delta));
                }
            }
            actions.add(Action.pass(actor.uid));
        }
        return actions;
    }

    /** Generate actions directly; validation still uses getValidTargetGroups. */
    private void appendSkillActions(ArrayList<Action> actions, UnitState actor, SkillDef skill, int enemyTeam) {
        if ("self".equals(skill.targetTeam)) {
            actions.add(Action.skill(actor.uid, skill.id, List.of(actor.uid)));
            return;
        }
        boolean friendly = "ally".equals(skill.targetTeam) || "ally_other".equals(skill.targetTeam);
        boolean allowSelf = "ally".equals(skill.targetTeam);
        boolean grouped = skill.isAoe || (!friendly && skill.randomTarget);
        boolean ignoreStealth = !friendly && !skill.isAoe
                && (skill.ignoresStealth || skill.deStealth || buffSum(actor, StatKey.IGNORE_STEALTH) != 0.0);
        ArrayList<String> group = grouped ? new ArrayList<>(4) : null;
        ArrayList<UnitState> units = state.teams[friendly ? actor.team : enemyTeam];
        for (int i = 0, n = units.size(); i < n; i++) {
            UnitState target = units.get(i);
            if (!target.alive || !skill.canTargetRank(target.rank)) continue;
            if (friendly) {
                if (target.isCorpse || (!allowSelf && target == actor)) continue;
            } else if (!skill.isAoe && target.stealthRounds > 0 && !ignoreStealth) continue;
            if (grouped) group.add(target.uid);
            else actions.add(Action.skill(actor.uid, skill.id, List.of(target.uid)));
        }
        if (grouped && !group.isEmpty()) actions.add(Action.skill(actor.uid, skill.id, group));
    }

    public boolean canUseSkill(UnitState actor, SkillDef sdef) {
        if (!sdef.canLaunchFrom(actor.rank)) return false;
        if (sdef.maxUses > 0 && actor.skillUses.getOrDefault(sdef.id, 0) >= sdef.maxUses) return false;
        if (actor.skillCooldowns.getOrDefault(sdef.id, 0) > 0) return false;
        if (sdef.requiresHpBelowPct > 0.0 && ((double) actor.hp / Math.max(1, actor.maxHp)) >= sdef.requiresHpBelowPct) {
            return false;
        }
        if ("abomination".equals(actor.heroId)) {
            if (sdef.isTransform && actor.transformedThisRound) return false;
            if ("human".equals(sdef.abomForm) && actor.isBeast) return false;
            if ("beast".equals(sdef.abomForm) && !actor.isBeast) return false;
        }
        return true;
    }

    private List<List<String>> getValidTargetGroups(UnitState actor, SkillDef sdef, int enemyTeam) {
        if ("self".equals(sdef.targetTeam)) {
            return List.of(List.of(actor.uid));
        }
        if ("ally".equals(sdef.targetTeam) || "ally_other".equals(sdef.targetTeam)) {
            boolean allowSelf = "ally".equals(sdef.targetTeam);
            ArrayList<UnitState> teamUnits = state.teams[actor.team];
            ArrayList<String> validUids = new ArrayList<>(4);
            for (int i = 0, n = teamUnits.size(); i < n; i++) {
                UnitState u = teamUnits.get(i);
                if (u.alive && !u.isCorpse && sdef.canTargetRank(u.rank) && (allowSelf || u != actor)) {
                    validUids.add(u.uid);
                }
            }
            if (validUids.isEmpty()) return Collections.emptyList();
            if (sdef.isAoe) return List.of(validUids);
            ArrayList<List<String>> out = new ArrayList<>(validUids.size());
            for (int i = 0, n = validUids.size(); i < n; i++) {
                out.add(List.of(validUids.get(i)));
            }
            return out;
        }

        // Enemy targeting
        ArrayList<UnitState> enemyUnits = state.teams[enemyTeam];
        ArrayList<UnitState> inRange = new ArrayList<>(4);
        for (int i = 0, n = enemyUnits.size(); i < n; i++) {
            UnitState u = enemyUnits.get(i);
            if (u.alive && sdef.canTargetRank(u.rank)) {
                inRange.add(u);
            }
        }
        if (inRange.isEmpty()) return Collections.emptyList();
        if (sdef.isAoe) {
            ArrayList<String> ids = new ArrayList<>(inRange.size());
            for (int i = 0, n = inRange.size(); i < n; i++) ids.add(inRange.get(i).uid);
            return List.of(ids);
        }

        boolean actorIgnoresStealth = sdef.ignoresStealth || sdef.deStealth || buffSum(actor, StatKey.IGNORE_STEALTH) != 0.0;
        ArrayList<String> validSingle = new ArrayList<>(inRange.size());
        for (int i = 0, n = inRange.size(); i < n; i++) {
            UnitState u = inRange.get(i);
            if (u.stealthRounds > 0 && !actorIgnoresStealth) continue;
            validSingle.add(u.uid);
        }
        if (validSingle.isEmpty()) return Collections.emptyList();
        if (sdef.randomTarget) {
            return List.of(validSingle);
        }
        ArrayList<List<String>> out = new ArrayList<>(validSingle.size());
        for (int i = 0, n = validSingle.size(); i < n; i++) {
            out.add(List.of(validSingle.get(i)));
        }
        return out;
    }

    // ------------------------------------------------------------------ Step Execution
    public List<CombatEvent> step(Action action) {
        if (state.finished) {
            throw new IllegalStateException("Cannot step a finished match.");
        }

        List<CombatEvent> events = recordEvents ? new ArrayList<>(12) : null;
        int team = state.activeTeam;

        if ("SURRENDER".equals(action.kind)) {
            int winner = 1 - team;
            state.finished = true;
            state.winner = winner;
            state.winReason = "surrender";
            if (events != null) {
                events.add(new CombatEvent("SURRENDER", mapOf("team", team, "winner", winner)));
                events.add(new CombatEvent("MATCH_END", mapOf("winner", winner, "reason", "surrender", "round", state.roundNum)));
                eventsHistory.addAll(events);
                return events;
            }
            return Collections.emptyList();
        }

        UnitState actor = findUnit(action.actorUid);
        if (actor == null || actor.team != team || !actor.alive || actor.isCorpse) {
            throw new IllegalArgumentException("Invalid actor_uid: " + action.actorUid);
        }

        boolean selectable = false;
        List<UnitState> selList = getSelectableActors(team);
        for (int i = 0, n = selList.size(); i < n; i++) {
            if (selList.get(i) == actor) {
                selectable = true;
                break;
            }
        }
        if (!selectable) {
            throw new IllegalArgumentException("Actor " + actor.uid + " is not selectable (already acted or Dazed while non-dazed allies exist).");
        }

        HeroDef chosenHdef = null;
        SkillDef chosenSdef = null;
        if ("SKILL".equals(action.kind)) {
            chosenHdef = actor.heroDef;
            chosenSdef = chosenHdef.getSkill(action.skillId);
            if (chosenSdef == null || !actor.equippedSkills.contains(chosenSdef.id) || !canUseSkill(actor, chosenSdef)) {
                throw new IllegalArgumentException("Illegal skill " + action.skillId + " for " + actor.uid);
            }
            List<List<String>> groups = getValidTargetGroups(actor, chosenSdef, 1 - team);
            boolean match = false;
            for (int i = 0, n = groups.size(); i < n; i++) {
                if (groups.get(i).equals(action.targetUids)) {
                    match = true;
                    break;
                }
            }
            if (!match) {
                throw new IllegalArgumentException("Invalid targets for " + chosenSdef.id);
            }
        }

        boolean isFreeActionContinuation = state.lockedActorUid != null && state.lockedActorUid.equals(actor.uid);
        if (isFreeActionContinuation) {
            state.lockedActorUid = null;
        } else {
            state.turnCount++;
            actor.dazed = false;
            if (events != null) {
                events.add(new CombatEvent("TURN_START", mapOf("actor_uid", actor.uid, "team", team, "round", state.roundNum)));
            }

            // 1. Start-of-turn DoTs, Regen, Horror, Trait barks
            processTurnStartEffects(actor, events);
            if (!actor.alive) {
                finishTurnAndAdvance(actor, events);
                if (events != null) {
                    eventsHistory.addAll(events);
                    return events;
                }
                return Collections.emptyList();
            }

            // 2. Check Stun
            if (actor.stunned) {
                actor.stunned = false;
                breakGuardBy(actor, events);
                // Official DD Stun Recovery: +50% Stun Resist for 2 full rounds AFTER recovery turn
                actor.buffs.add(new ActiveBuff(StatKey.STUN_RES, 0, "stun_res", 50.0, 2, "stun_recovery", false, true));
                if (events != null) {
                    events.add(new CombatEvent("STUN_SKIP", mapOf("actor_uid", actor.uid)));
                }
                finishTurnAndAdvance(actor, events);
                if (events != null) {
                    eventsHistory.addAll(events);
                    return events;
                }
                return Collections.emptyList();
            }
        }

        // 3. Execute chosen action
        boolean freeActionUsed = false;
        int outcomeMode = this.searchOutcomeMode;
        this.searchOutcomeMode = 0;
        if ("PASS".equals(action.kind)) {
            if (events != null) events.add(new CombatEvent("PASS", mapOf("actor_uid", actor.uid)));
            applyStress(actor, 5, events, "pass");
        } else if ("MOVE".equals(action.kind)) {
            if (action.moveDelta != 0) {
                shiftUnitRank(actor, action.moveDelta, events, true);
            }
        } else if ("SKILL".equals(action.kind) && chosenHdef != null && chosenSdef != null) {
            if (canUseSkill(actor, chosenSdef)) {
                this.searchOutcomeMode = outcomeMode;
                try {
                    executeSkill(actor, chosenHdef, chosenSdef, action.targetUids, events);
                } finally {
                    this.searchOutcomeMode = 0;
                }
                if (chosenSdef.isTransform && actor.alive && !actor.isCorpse) {
                    freeActionUsed = true;
                }
            } else if (events != null) {
                events.add(new CombatEvent("PASS", mapOf("actor_uid", actor.uid, "reason", "interrupted")));
            }
        }

        // Free action (e.g. Abomination Transform): keep this actor's turn active for immediate follow-up
        if (freeActionUsed) {
            state.lockedActorUid = actor.uid;
            if (events != null) {
                eventsHistory.addAll(events);
                return events;
            }
            return Collections.emptyList();
        }

        // 4. End of actor's turn & advance
        finishTurnAndAdvance(actor, events);
        if (events != null) {
            eventsHistory.addAll(events);
            return events;
        }
        return Collections.emptyList();
    }

    private void processTurnStartEffects(UnitState actor, List<CombatEvent> events) {
        if (!actor.dots.isEmpty()) {
            ArrayList<ActiveDot> remaining = new ArrayList<>(actor.dots.size());
            for (int i = 0, n = actor.dots.size(); i < n; i++) {
                ActiveDot dot = actor.dots.get(i);
                if (!actor.alive) break;
                if (dot.kind == Models.DOT_REGEN) {
                    applyHeal(actor, dot.amount, events, "regen", null);
                } else if (dot.kind == Models.DOT_BLEED || dot.kind == Models.DOT_BLIGHT) {
                    if (actor.hp > 0) {
                        int dmg = Math.min(actor.hp, dot.amount);
                        actor.hp -= dmg;
                        if (events != null) {
                            events.add(new CombatEvent("DOT_TICK", mapOf(
                                    "unit_uid", actor.uid, "kind", dot.kindName(), "amount", dmg, "hp", actor.hp
                            )));
                        }
                        if (actor.hp == 0 && !actor.atDeathsDoor) {
                            actor.atDeathsDoor = true;
                            if (events != null) {
                                events.add(new CombatEvent("DEATHS_DOOR", mapOf("unit_uid", actor.uid)));
                            }
                        }
                    } else if (events != null) {
                        events.add(new CombatEvent("DOT_TICK", mapOf(
                                "unit_uid", actor.uid, "kind", dot.kindName(), "amount", 0, "hp", 0, "no_deathblow", true
                        )));
                    }
                } else if (dot.kind == Models.DOT_HORROR) {
                    if (events != null) {
                        events.add(new CombatEvent("HORROR_TICK", mapOf("unit_uid", actor.uid, "amount", dot.amount)));
                    }
                    applyStress(actor, dot.amount, events, "horror");
                }
                dot.roundsLeft--;
                if (dot.roundsLeft > 0) remaining.add(dot);
            }
            actor.dots = remaining;
        }

        if (!actor.alive) return;

        if (actor.traitId != null) {
            TraitDef tdef = GameData.get().traits.get(actor.traitId);
            if (tdef != null) {
                if (tdef.turnHealSelf > 0) {
                    applyHeal(actor, tdef.turnHealSelf, events, tdef.id, null);
                }
                if (tdef.turnStressSelf != 0 && rng.nextDouble() < 0.45) {
                    int bIdx = tdef.barksEn.length > 0 ? rng.nextInt(tdef.barksEn.length) : 0;
                    if (events != null) {
                        events.add(new CombatEvent("BARK", mapOf(
                                "unit_uid", actor.uid, "trait", tdef.id,
                                "text_en", tdef.barksEn.length > 0 ? tdef.barksEn[bIdx] : "",
                                "text_zh", tdef.barksZh.length > 0 ? tdef.barksZh[bIdx] : ""
                        )));
                    }
                    applyStress(actor, tdef.turnStressSelf, events, tdef.id);
                }
                if (tdef.turnStressAllies != 0 && rng.nextDouble() < 0.45) {
                    int bIdx = tdef.barksEn.length > 0 ? rng.nextInt(tdef.barksEn.length) : 0;
                    if (events != null) {
                        events.add(new CombatEvent("BARK", mapOf(
                                "unit_uid", actor.uid, "trait", tdef.id,
                                "text_en", tdef.barksEn.length > 0 ? tdef.barksEn[bIdx] : "",
                                "text_zh", tdef.barksZh.length > 0 ? tdef.barksZh[bIdx] : ""
                        )));
                    }
                    for (UnitState ally : livingHeroes(actor.team)) {
                        if (ally != actor) {
                            if (tdef.turnStressAllies > 0) {
                                applyStress(ally, tdef.turnStressAllies, events, tdef.id);
                            } else {
                                healStress(ally, -tdef.turnStressAllies, events, tdef.id);
                            }
                        }
                    }
                }
            }
        }
    }

    private void executeSkill(UnitState actor, HeroDef hdef, SkillDef sdef, List<String> targetUids, List<CombatEvent> events) {
        actor.skillUses.put(sdef.id, actor.skillUses.getOrDefault(sdef.id, 0) + 1);
        List<String> actualTargetUids = targetUids;
        if (sdef.randomTarget && !targetUids.isEmpty()) {
            actualTargetUids = List.of(targetUids.get(rng.nextInt(targetUids.size())));
        }
        if (sdef.cooldown > 0) {
            actor.skillCooldowns.put(sdef.id, sdef.cooldown + 1);
        }

        if (events != null) {
            Map<String, Object> m = new LinkedHashMap<>(16);
            m.put("actor_uid", actor.uid);
            m.put("hero_id", actor.heroId);
            m.put("skill_id", sdef.id);
            m.put("name_en", sdef.nameEn);
            m.put("name_zh", sdef.nameZh);
            m.put("anim", sdef.anim);
            m.put("fx", sdef.fx);
            m.put("targfx", sdef.targfx);
            m.put("target_anchor", sdef.targetAnchor);
            m.put("sfx_hint", (sdef.isTransform && actor.isBeast) ? "char_al_abom_trans_tohuman" : sdef.sfxHint);
            m.put("target_uids", new ArrayList<>(actualTargetUids));
            m.put("is_aoe", sdef.isAoe);
            m.put("is_attack", sdef.isAttack);
            events.add(new CombatEvent("SKILL_USE", m));
        }

        // Abomination Transform special (free action, once per round)
        if (sdef.isTransform) {
            actor.transformedThisRound = true;
            actor.isBeast = !actor.isBeast;
            if (actor.isBeast) {
                if (events != null) events.add(new CombatEvent("TRANSFORM", mapOf("unit_uid", actor.uid, "is_beast", true)));
                actor.buffs.add(new ActiveBuff(StatKey.MOVE_RES, 0, "move_res", 40.0, 4, "transform", false, true));
                for (UnitState enemy : livingHeroes(1 - actor.team)) {
                    enemy.dots.add(new ActiveDot(Models.DOT_HORROR, 8, 3, "transform"));
                    if (events != null) {
                        events.add(new CombatEvent("STATUS_APPLIED", mapOf(
                                "unit_uid", enemy.uid, "status", "horror", "amount", 8, "rounds", 3
                        )));
                    }
                }
            } else {
                if (events != null) events.add(new CombatEvent("TRANSFORM", mapOf("unit_uid", actor.uid, "is_beast", false)));
                actor.dazed = true;
                if (events != null) events.add(new CombatEvent("STATUS_APPLIED", mapOf("unit_uid", actor.uid, "status", "daze")));
            }
            return;
        }

        // Jester Finale charge
        if (sdef.finaleBuff > 0.0) {
            actor.buffs.add(new ActiveBuff(StatKey.FINALE_DAMAGE, 0, "finale_damage", sdef.finaleBuff, 8, sdef.id, false, true));
            if ("solo".equals(sdef.id) || "battle_ballad".equals(sdef.id) || "inspiring_tune".equals(sdef.id)) {
                actor.buffs.add(new ActiveBuff(StatKey.FINALE_CRIT, 0, "finale_crit", 8.0, 8, sdef.id, false, true));
            }
        }

        // Self buffs / effects
        if (sdef.selfHealPct != 0.0) {
            applyHeal(actor, pyRound(actor.maxHp * sdef.selfHealPct), events, sdef.id, actor);
        }
        if (sdef.selfDaze) {
            actor.dazed = true;
            if (events != null) events.add(new CombatEvent("STATUS_APPLIED", mapOf("unit_uid", actor.uid, "status", "daze")));
        }
        if (sdef.selfMarkRounds > 0) {
            actor.markedRounds = Math.max(actor.markedRounds, sdef.selfMarkRounds);
            actor.freshStatusMask |= UnitState.FRESH_MARK;
        }
        if (sdef.curesSelfDots) {
            actor.dots.removeIf(d -> d.kind == Models.DOT_BLEED || d.kind == Models.DOT_BLIGHT);
        }
        if (sdef.stealthRounds > 0) {
            actor.stealthRounds = Math.max(actor.stealthRounds, sdef.stealthRounds);
            actor.freshStatusMask |= UnitState.FRESH_STEALTH;
            if (events != null) events.add(new CombatEvent("STATUS_APPLIED", mapOf("unit_uid", actor.uid, "status", "stealth", "rounds", sdef.stealthRounds)));
        }
        if (sdef.riposteRounds > 0) {
            actor.riposteRounds = Math.max(actor.riposteRounds, sdef.riposteRounds);
            actor.freshStatusMask |= UnitState.FRESH_RIPOSTE;
            if (events != null) events.add(new CombatEvent("STATUS_APPLIED", mapOf("unit_uid", actor.uid, "status", "riposte", "rounds", sdef.riposteRounds)));
        }
        if (sdef.aegisBlocks > 0) {
            actor.aegisBlocks = Math.min(4, actor.aegisBlocks + sdef.aegisBlocks);
            if (events != null) events.add(new CombatEvent("STATUS_APPLIED", mapOf("unit_uid", actor.uid, "status", "aegis", "amount", sdef.aegisBlocks)));
        }
        if (sdef.selfHealMax > 0) {
            int amt = rng.nextIntInclusive(sdef.selfHealMin, sdef.selfHealMax);
            applyHeal(actor, amt, events, sdef.id, actor);
        }
        if (sdef.clearsSelfDebuffs) {
            actor.buffs.removeIf(b -> b.isDebuff);
        }
        if (sdef.selfStress > 0) {
            applyStress(actor, sdef.selfStress, events, sdef.id);
        } else if (sdef.selfStress < 0) {
            healStress(actor, -sdef.selfStress, events, sdef.id);
        }
        if (sdef.selfBleed != null) {
            computeEffectiveStats(actor, null, "", scratchStatsC);
            if (rng.nextDouble() * 100.0 < sdef.selfBleedChance - scratchStatsC.values[StatKey.BLEED_RES]) {
                actor.dots.add(new ActiveDot(Models.DOT_BLEED, sdef.selfBleed[0], sdef.selfBleed[1], sdef.id));
                if (events != null) events.add(new CombatEvent("STATUS_APPLIED", mapOf("unit_uid", actor.uid, "status", "bleed", "amount", sdef.selfBleed[0])));
            }
        }
        if (sdef.partyBuffs.length > 0) {
            for (UnitState ally : livingHeroes(actor.team)) {
                boolean isSelf = (ally == actor);
                for (BuffDef bd : sdef.partyBuffs) {
                    ally.buffs.add(ActiveBuff.fromDefFresh(bd, isSelf));
                }
            }
            if (events != null) events.add(new CombatEvent("PARTY_BUFF", mapOf("team", actor.team, "skill_id", sdef.id)));
        }
        if (sdef.partyStressHeal > 0) {
            for (UnitState ally : livingHeroes(actor.team)) {
                if (sdef.stressHealChance >= 100 || rng.nextDouble() * 100.0 < sdef.stressHealChance) {
                    computeEffectiveStats(actor, null, "", scratchStatsC);
                    int amount = pyRound(sdef.partyStressHeal * (1.0 + scratchStatsC.values[StatKey.STRESS_HEAL_PCT]));
                    healStress(ally, amount, events, sdef.id);
                }
            }
        }
        if (sdef.partyCleanse) {
            for (UnitState ally : livingHeroes(actor.team)) {
                ally.stunned = false;
                ally.dazed = false;
                ally.markedRounds = 0;
                ally.buffs.removeIf(b -> b.isDebuff);
            }
        }

        if (sdef.isAttack && !sdef.isRanged && actor.hasTrinketFlag(UnitState.TF_CROWN_OF_THORNS)) {
            computeEffectiveStats(actor, null, "", scratchStatsC);
            if (rng.nextDouble() * 100.0 < 50.0 + buffSum(actor, StatKey.BLEED_CHANCE) - scratchStatsC.values[StatKey.BLEED_RES]) {
                actor.dots.add(new ActiveDot(Models.DOT_BLEED, 2, 3, "crown_of_thorns"));
            }
        }

        // Resolve targets
        ArrayList<UnitState> riposteResponders = null;
        int enemyTeam = 1 - actor.team;

        for (int ti = 0, tn = actualTargetUids.size(); ti < tn; ti++) {
            UnitState target = findUnit(actualTargetUids.get(ti));
            if (target == null || !target.alive) continue;

            if (!sdef.isAttack) {
                resolveFriendlyTarget(actor, sdef, target, events);
            } else {
                UnitState actualTarget = target;
                if (!sdef.isAoe && !sdef.ignoresGuard && target.guardedByUid != null) {
                    computeEffectiveStats(actor, target, "", scratchStatsC);
                    if (scratchStatsC.values[StatKey.IGNORE_GUARD] == 0.0) {
                        UnitState guardian = findUnit(target.guardedByUid);
                        if (guardian != null && guardian.alive && !guardian.isCorpse && !guardian.stunned) {
                            if (events != null) {
                                events.add(new CombatEvent("GUARD_INTERCEPT", mapOf(
                                        "guardian_uid", guardian.uid, "protected_uid", target.uid
                                )));
                            }
                            actualTarget = guardian;
                        }
                    }
                }

                resolveAttackTarget(actor, hdef, sdef, actualTarget, events);
                if (sdef.triggersRiposte()
                        && actualTarget.alive && !actualTarget.isCorpse && !actualTarget.stunned && actualTarget.riposteRounds > 0) {
                    if (riposteResponders == null) riposteResponders = new ArrayList<>(2);
                    if (!riposteResponders.contains(actualTarget)) {
                        riposteResponders.add(actualTarget);
                    }
                }
            }
        }

        // Self buffs (applied AFTER target resolution, marked fresh so they don't tick down on cast turn!)
        for (BuffDef bd : sdef.selfBuffs) {
            actor.buffs.add(ActiveBuff.fromDefFresh(bd, true));
        }

        // Corpse clear
        if (sdef.clearsCorpses) {
            boolean clearedAny = false;
            ArrayList<UnitState> eList = state.teams[enemyTeam];
            for (int i = 0, n = eList.size(); i < n; i++) {
                UnitState u = eList.get(i);
                if (u.alive && u.isCorpse) {
                    u.alive = false;
                    u.hp = 0;
                    clearedAny = true;
                    if (events != null) events.add(new CombatEvent("CORPSE_CLEARED", mapOf("unit_uid", u.uid)));
                }
            }
            if (clearedAny) syncRanks(enemyTeam);
        }

        // Self movement (after resolving hits)
        if (sdef.selfMove != 0 && actor.alive) {
            shiftUnitRank(actor, sdef.selfMove, events, true);
        }

        // Jester Finale post-debuff
        if (sdef.isFinale) {
            actor.finaleBonusPct = 0.0;
            actor.buffs.removeIf(b -> b.statId == StatKey.FINALE_DAMAGE || b.statId == StatKey.FINALE_CRIT);
            actor.buffs.add(new ActiveBuff(StatKey.DODGE, 0, "dodge", -25.0, -1, "finale_debuff", true, false));
            actor.buffs.add(new ActiveBuff(StatKey.STRESS_RES, 0, "stress_res", -100.0, -1, "finale_debuff", true, false));
            actor.buffs.add(new ActiveBuff(StatKey.DAZE, 0, "daze", 1.0, 3, "finale", false, true));
        }

        // Trigger Riposte counter-attacks
        if (riposteResponders != null) {
            for (int i = 0, n = riposteResponders.size(); i < n; i++) {
                UnitState resp = riposteResponders.get(i);
                if (!actor.alive || !resp.alive || resp.stunned) continue;
                executeRiposte(resp, actor, events);
            }
        }
    }

    private void resolveFriendlyTarget(UnitState actor, SkillDef sdef, UnitState target, List<CombatEvent> events) {
        if (sdef.transferDots) {
            for (int i = 0, n = target.dots.size(); i < n; i++) {
                ActiveDot d = target.dots.get(i);
                if (d.kind == Models.DOT_BLEED || d.kind == Models.DOT_BLIGHT) {
                    actor.dots.add(d);
                }
            }
            target.dots.removeIf(d -> d.kind == Models.DOT_BLEED || d.kind == Models.DOT_BLIGHT);
        }
        if (sdef.healMax > 0 || sdef.healPct != 0.0) {
            int baseAmt = sdef.healPct != 0.0
                    ? pyRound(target.maxHp * sdef.healPct)
                    : rng.nextIntInclusive(sdef.healMin, sdef.healMax);
            applyHeal(target, baseAmt, events, sdef.id, actor);
            if (sdef.bleed != null && target.alive) {
                computeEffectiveStats(target, null, "", scratchStatsB);
                if (rng.nextDouble() * 100.0 < Math.max(0.0, sdef.bleed.chance - scratchStatsB.values[StatKey.BLEED_RES])) {
                    target.dots.add(new ActiveDot(Models.DOT_BLEED, sdef.bleed.amount, sdef.bleed.rounds, sdef.id));
                    if (events != null) {
                        events.add(new CombatEvent("STATUS_APPLIED", mapOf(
                                "unit_uid", target.uid, "status", "bleed", "amount", sdef.bleed.amount
                        )));
                    }
                }
            }
        }
        if (sdef.stressHeal > 0 && (sdef.stressHealChance >= 100 || rng.nextDouble() * 100.0 < sdef.stressHealChance)) {
            computeEffectiveStats(actor, null, "", scratchStatsA);
            int amount = pyRound(sdef.stressHeal * (1.0 + scratchStatsA.values[StatKey.STRESS_HEAL_PCT]));
            healStress(target, amount, events, sdef.id);
        }
        if (sdef.regen != null) {
            int rounds = pyRound(sdef.regen[1] * (1.0 + buffSum(actor, StatKey.REGEN_DURATION) / 100.0));
            target.dots.add(new ActiveDot(Models.DOT_REGEN, sdef.regen[0], rounds, sdef.id));
            if (events != null) {
                events.add(new CombatEvent("STATUS_APPLIED", mapOf(
                        "unit_uid", target.uid, "status", "regen", "amount", sdef.regen[0]
                )));
            }
        }
        if (sdef.curesBleed) target.dots.removeIf(d -> d.kind == Models.DOT_BLEED);
        if (sdef.curesBlight) target.dots.removeIf(d -> d.kind == Models.DOT_BLIGHT);
        if (sdef.curesHorror) target.dots.removeIf(d -> d.kind == Models.DOT_HORROR);
        if (sdef.clearsMark) target.markedRounds = 0;
        if (sdef.clearsStun) {
            target.stunned = false;
            target.dazed = false;
        }
        if (sdef.clearsDebuffs) target.buffs.removeIf(b -> b.isDebuff);
        if (sdef.targetStealthRounds > 0) {
            target.stealthRounds = Math.max(target.stealthRounds, sdef.targetStealthRounds);
            if (events != null) {
                events.add(new CombatEvent("STATUS_APPLIED", mapOf(
                        "unit_uid", target.uid, "status", "stealth", "rounds", target.stealthRounds
                )));
            }
        }
        if (sdef.targetBuffs.length > 0) {
            boolean isSelf = (target == actor);
            for (BuffDef bd : sdef.targetBuffs) {
                target.buffs.add(ActiveBuff.fromDefFresh(bd, isSelf));
            }
            if (events != null) {
                events.add(new CombatEvent("BUFF_APPLIED", mapOf("unit_uid", target.uid, "skill_id", sdef.id)));
            }
        }
        if (sdef.guardRounds > 0 && target != actor) {
            setGuard(actor, target, sdef.guardRounds, events);
            actor.freshStatusMask |= UnitState.FRESH_GUARD;
        }
        if (sdef.guardMeRounds > 0 && target != actor) {
            setGuard(target, actor, sdef.guardMeRounds, events);
        }
    }

    private void resolveAttackTarget(UnitState actor, HeroDef hdef, SkillDef sdef, UnitState target, List<CombatEvent> events) {
        EffectiveStats astats = computeEffectiveStats(actor, target, sdef.isRanged ? "ranged" : "melee", scratchStatsA);
        EffectiveStats tstats = computeEffectiveStats(target, null, "", scratchStatsB);

        double hitChance = target.isCorpse ? 100.0
                : computeHitChance(sdef.acc + astats.values[StatKey.ACC], tstats.values[StatKey.DODGE]);

        int mode = this.searchOutcomeMode;
        boolean missed = (mode == 3) || (mode == 0 && rng.nextDouble() * 100.0 >= hitChance);
        if (missed) {
            if (actor.hasTrinketFlag(UnitState.TF_TASTE_OF_GRANDEUR)) {
                applyStress(actor, 8, events, "taste_of_grandeur");
            }
            if (events != null) {
                events.add(new CombatEvent("MISS", mapOf(
                        "actor_uid", actor.uid, "target_uid", target.uid,
                        "hero_id", actor.heroId, "skill_id", sdef.id, "sfx_hint", sdef.sfxHint
                )));
            }
            return;
        }

        boolean isBleeding = target.hasDotKind(Models.DOT_BLEED);
        boolean isBlighted = target.hasDotKind(Models.DOT_BLIGHT);
        boolean isMarked = target.markedRounds > 0;

        double critChance = astats.values[StatKey.CRIT] + sdef.critMod + tstats.values[StatKey.CRITS_RECEIVED];
        double dbChance = astats.values[StatKey.DEATHBLOW_CHANCE];
        if (sdef.isFinale) critChance += astats.values[StatKey.FINALE_CRIT];
        if (isMarked) {
            critChance += sdef.bonusCritVsMarked;
            if ("aimed_shot".equals(sdef.id) || "sniper_shot".equals(sdef.id)) {
                dbChance += 30.0;
            }
        }
        if (target.isAfflicted && "bloodlet".equals(sdef.id)) {
            dbChance += 40.0;
        }
        if (isBleeding) critChance += sdef.bonusCritVsBleed;
        if (isBlighted) critChance += sdef.bonusCritVsBlight;
        double effCritPct = clamp(critChance, 0.0, 100.0);
        boolean expectedDmgMode = (mode == 2 || mode == 4);
        boolean isCrit = (!target.isCorpse) && (sdef.dmgMod > -1.0) && !expectedDmgMode
                && (rng.nextDouble() * 100.0 < effCritPct);

        // Compute HP damage
        int dmg = 0;
        boolean blocked = false;
        if (sdef.dmgMod > -1.0) {
            int lo = ("abomination".equals(hdef.id) && actor.isBeast) ? hdef.beastDmgMin : hdef.dmgMin;
            int hi = ("abomination".equals(hdef.id) && actor.isBeast) ? hdef.beastDmgMax : hdef.dmgMax;

            double mult = 1.0 + sdef.dmgMod + astats.values[StatKey.DMG_PCT];
            if ("sidearm".equals(sdef.id)) {
                boolean anyActed = false;
                ArrayList<UnitState> teamUnits = state.teams[actor.team];
                for (int i = 0, n = teamUnits.size(); i < n; i++) {
                    UnitState u = teamUnits.get(i);
                    if (u.alive && !u.isCorpse && u.actedThisRound) {
                        anyActed = true;
                        break;
                    }
                }
                if (!anyActed) mult += 0.30;
            }
            if (isMarked) mult += sdef.bonusDmgVsMarked + astats.values[StatKey.DMG_VS_MARKED_PCT];
            if (isBleeding) mult += sdef.bonusDmgVsBleed + astats.values[StatKey.DMG_VS_BLEED_PCT];
            if (isBlighted) mult += sdef.bonusDmgVsBlight + astats.values[StatKey.DMG_VS_BLIGHT_PCT];
            if (target.stunned) {
                mult += sdef.bonusDmgVsStunned + astats.values[StatKey.DMG_VS_STUNNED_PCT];
            } else if (target.dazed && "finish_him".equals(sdef.id)) {
                mult += 0.30;
            }
            if (target.guardedByUid != null) mult += sdef.bonusDmgVsGuarded;
            if (sdef.isFinale) mult += astats.values[StatKey.FINALE_DAMAGE];
            if ("rake".equals(sdef.id)) mult += astats.values[StatKey.RAKE_DMG_PCT];
            mult = Math.max(0.10, mult);

            double raw;
            if (expectedDmgMode) {
                double pc = effCritPct / 100.0;
                raw = ((lo + hi) * 0.5 * (1.0 - pc) + hi * 1.5 * pc) * mult;
            } else {
                raw = isCrit ? (hi * mult * 1.5) : (rng.nextUniform(lo, hi) * mult);
            }

            if (target.aegisBlocks > 0) {
                target.aegisBlocks--;
                blocked = true;
                dmg = 0;
                if (events != null) {
                    events.add(new CombatEvent("BLOCKED", mapOf("target_uid", target.uid, "remaining", target.aegisBlocks)));
                }
            } else {
                if (!sdef.armorPiercing) {
                    raw *= 1.0 - Math.max(0.0, tstats.values[StatKey.PROT] - astats.values[StatKey.ARMOR_PIERCE]);
                }
                raw *= 1.0 + tstats.values[StatKey.DAMAGE_TAKEN_PCT];
                dmg = Math.max(1, (int) Math.ceil(raw));
            }
        }

        boolean killed = false;
        if (dmg > 0) {
            if (target.isCorpse) {
                target.hp = Math.max(0, target.hp - dmg);
                if (events != null) {
                    events.add(makeHitEvent(actor, target, sdef, isMarked, dmg, false, target.hp));
                }
                if (target.hp <= 0) {
                    target.alive = false;
                    if (events != null) events.add(new CombatEvent("CORPSE_DESTROYED", mapOf("unit_uid", target.uid)));
                    syncRanks(target.team);
                }
                return;
            }

            if (target.atDeathsDoor && target.hp <= 0) {
                if (events != null) {
                    events.add(makeHitEvent(actor, target, sdef, isMarked, dmg, isCrit, 0));
                }
                double killChance = clamp(100.0 - tstats.values[StatKey.DEATHBLOW_RES] + sdef.deathblowBonus + dbChance, 5.0, 100.0);
                boolean doKill = (mode == 1) || (mode != 2 && rng.nextDouble() * 100.0 < killChance);
                if (doKill) {
                    killed = true;
                    killHero(target, events, !(isCrit || sdef.noCorpseOnKill), "deathblow");
                } else if (events != null) {
                    events.add(new CombatEvent("DEATHS_DOOR_RESIST", mapOf("unit_uid", target.uid)));
                }
            } else {
                target.hp = Math.max(0, target.hp - dmg);
                if (events != null) {
                    events.add(makeHitEvent(actor, target, sdef, isMarked, dmg, isCrit, target.hp));
                }
                if (target.hp == 0 && sdef.isFinale) {
                    killHero(target, events, !isCrit, "deathblow");
                    killed = true;
                } else if (target.hp == 0 && !target.atDeathsDoor) {
                    target.atDeathsDoor = true;
                    if (events != null) events.add(new CombatEvent("DEATHS_DOOR", mapOf("unit_uid", target.uid)));
                }
            }
        } else if (!blocked && events != null) {
            events.add(makeHitEvent(actor, target, sdef, isMarked, 0, isCrit, target.hp));
        }

        // Crit caster stress heal
        if (isCrit) {
            healStress(actor, 5, events, "crit");
            if (actor.hasTrinketFlag(UnitState.TF_INSIGNIA_OF_RANK)) {
                for (UnitState ally : livingHeroes(actor.team)) {
                    ally.buffs.add(new ActiveBuff(StatKey.ACC, 0, "acc", 8.0, 3, "insignia_of_rank", false, ally == actor));
                }
            }
        }

        if (killed || !target.alive || target.isCorpse) return;

        // Re-read needed astats/tstats values before any helper overwrites scratchStats
        double aStressDmgPct = astats.values[StatKey.STRESS_DMG_PCT];
        double aBleedChance = astats.values[StatKey.BLEED_CHANCE];
        double aBlightChance = astats.values[StatKey.BLIGHT_CHANCE];
        double aBlightDuration = astats.values[StatKey.BLIGHT_DURATION];
        double aDebuffChance = astats.values[StatKey.DEBUFF_CHANCE];
        double aHorrorDuration = astats.values[StatKey.HORROR_DURATION];
        double aStunChance = astats.values[StatKey.STUN_CHANCE];
        double aMoveChance = astats.values[StatKey.MOVE_CHANCE];

        double tDebuffRes = tstats.values[StatKey.DEBUFF_RES];
        double tStunRes = tstats.values[StatKey.STUN_RES];
        double tBleedRes = tstats.values[StatKey.BLEED_RES];
        double tBlightRes = tstats.values[StatKey.BLIGHT_RES];
        double tMoveRes = tstats.values[StatKey.MOVE_RES];

        if (target.hasTrinketFlag(UnitState.TF_GAUNTLET_OF_ABSOLUTION)) {
            healStress(target, 10, events, "gauntlet_of_absolution");
        }
        if (actor.hasTrinketFlag(UnitState.TF_WELL_BALANCED_STILETTO)) {
            if (sdef.isRanged) {
                actor.buffs.add(new ActiveBuff(StatKey.ACC, StatKey.COND_MELEE, "acc@melee", 15.0, 3, "well_balanced_stiletto", false, true));
                actor.buffs.add(new ActiveBuff(StatKey.DEATHBLOW_CHANCE, StatKey.COND_MELEE, "deathblow_chance@melee", 20.0, 3, "well_balanced_stiletto", false, true));
            } else {
                actor.buffs.add(new ActiveBuff(StatKey.DMG_PCT, StatKey.COND_RANGED, "dmg_pct@ranged", 0.20, 3, "well_balanced_stiletto", false, true));
                actor.buffs.add(new ActiveBuff(StatKey.CRIT, StatKey.COND_RANGED, "crit@ranged", 6.0, 3, "well_balanced_stiletto", false, true));
            }
        }
        if (!sdef.isRanged) {
            if (actor.hasTrinketFlag(UnitState.TF_HARLEQUIN_MASQUE)) {
                healStress(actor, 10, events, "harlequin_masque");
            }
            if (actor.hasTrinketFlag(UnitState.TF_SACRIFICIAL_KRIS)) {
                target.markedRounds = Math.max(target.markedRounds, 4);
            }
            if (actor.hasTrinketFlag(UnitState.TF_GHOUL_CLAW) && rng.nextDouble() * 100.0 < 100.0 - tDebuffRes) {
                target.dots.add(new ActiveDot(Models.DOT_HORROR, 6, 2, "ghoul_claw"));
            }
            if (actor.hasTrinketFlag(UnitState.TF_VETERAN_GAUNTLET) && rng.nextDouble() * 100.0 < 50.0 + aStunChance - tStunRes) {
                target.dazed = true;
            }
        }

        // Collect trinket on-hit extras
        int trinketStress = 0;
        if (!sdef.isRanged) {
            for (TrinketDef td : actor.trinketDefs) {
                trinketStress += td.onHitStress;
            }
        }

        // Stress damage
        int baseStress = sdef.stressDmg + (isCrit ? 10 : 0) + trinketStress;
        if (baseStress > 0) {
            int scaledStress = pyRound(baseStress * Math.max(0.25, 1.0 + aStressDmgPct));
            applyStress(target, scaledStress, events, sdef.id);
            if (!target.alive) return;
        }

        double critBonus = isCrit ? 20.0 : 0.0;

        // De-stealth & Guard Break & Mark
        if (sdef.deStealth && target.stealthRounds > 0) {
            target.stealthRounds = 0;
            if (events != null) events.add(new CombatEvent("STATUS_CLEARED", mapOf("unit_uid", target.uid, "status", "stealth")));
        }
        if (sdef.breaksGuard) {
            breakGuardOnOrBy(target, events);
        }
        if (sdef.markRounds > 0) {
            target.markedRounds = Math.max(target.markedRounds, sdef.markRounds);
            if (events != null) events.add(new CombatEvent("STATUS_APPLIED", mapOf("unit_uid", target.uid, "status", "mark", "rounds", sdef.markRounds)));
        }

        // Bleed (skill + melee trinkets)
        if (sdef.bleed != null) {
            double effCh = sdef.bleed.chance + aBleedChance + critBonus - tBleedRes;
            if (rng.nextDouble() * 100.0 < effCh) {
                target.dots.add(new ActiveDot(Models.DOT_BLEED, sdef.bleed.amount, sdef.bleed.rounds, sdef.id));
                if (events != null) events.add(new CombatEvent("STATUS_APPLIED", mapOf("unit_uid", target.uid, "status", "bleed", "amount", sdef.bleed.amount, "rounds", sdef.bleed.rounds)));
            } else if (events != null) {
                events.add(new CombatEvent("RESIST", mapOf("unit_uid", target.uid, "kind", "bleed")));
            }
        }
        if (!sdef.isRanged) {
            for (TrinketDef td : actor.trinketDefs) {
                if (td.onHitBleed != null) {
                    double effCh = td.onHitBleed.chance + aBleedChance + critBonus - tBleedRes;
                    if (rng.nextDouble() * 100.0 < effCh) {
                        target.dots.add(new ActiveDot(Models.DOT_BLEED, td.onHitBleed.amount, td.onHitBleed.rounds, sdef.id));
                        if (events != null) events.add(new CombatEvent("STATUS_APPLIED", mapOf("unit_uid", target.uid, "status", "bleed", "amount", td.onHitBleed.amount, "rounds", td.onHitBleed.rounds)));
                    }
                }
            }
        }

        // Blight (skill + melee trinkets)
        if (sdef.blight != null) {
            double effCh = sdef.blight.chance + aBlightChance + critBonus - tBlightRes;
            if (rng.nextDouble() * 100.0 < effCh) {
                int rds = pyRound(sdef.blight.rounds * (1.0 + aBlightDuration / 100.0));
                target.dots.add(new ActiveDot(Models.DOT_BLIGHT, sdef.blight.amount, rds, sdef.id));
                if (events != null) events.add(new CombatEvent("STATUS_APPLIED", mapOf("unit_uid", target.uid, "status", "blight", "amount", sdef.blight.amount, "rounds", rds)));
            } else if (events != null) {
                events.add(new CombatEvent("RESIST", mapOf("unit_uid", target.uid, "kind", "blight")));
            }
        }
        if (!sdef.isRanged) {
            for (TrinketDef td : actor.trinketDefs) {
                if (td.onHitBlight != null) {
                    double effCh = td.onHitBlight.chance + aBlightChance + critBonus - tBlightRes;
                    if (rng.nextDouble() * 100.0 < effCh) {
                        int rds = pyRound(td.onHitBlight.rounds * (1.0 + aBlightDuration / 100.0));
                        target.dots.add(new ActiveDot(Models.DOT_BLIGHT, td.onHitBlight.amount, rds, sdef.id));
                        if (events != null) events.add(new CombatEvent("STATUS_APPLIED", mapOf("unit_uid", target.uid, "status", "blight", "amount", td.onHitBlight.amount, "rounds", rds)));
                    }
                }
            }
        }

        // Horror
        if (sdef.horror != null) {
            double effCh = sdef.horror.chance + aDebuffChance + critBonus - tDebuffRes;
            if (rng.nextDouble() * 100.0 < effCh) {
                int rds = pyRound(sdef.horror.rounds * (1.0 + aHorrorDuration / 100.0));
                target.dots.add(new ActiveDot(Models.DOT_HORROR, sdef.horror.amount, rds, sdef.id));
                if (events != null) events.add(new CombatEvent("STATUS_APPLIED", mapOf("unit_uid", target.uid, "status", "horror", "amount", sdef.horror.amount, "rounds", rds)));
            } else if (events != null) {
                events.add(new CombatEvent("RESIST", mapOf("unit_uid", target.uid, "kind", "horror")));
            }
        }

        // Stun vs Daze
        if (sdef.stunChance > 0) {
            double effCh = sdef.stunChance + aStunChance + critBonus - tStunRes;
            if (rng.nextDouble() * 100.0 < effCh) {
                target.stunned = true;
                breakGuardBy(target, events);
                if (events != null) events.add(new CombatEvent("STATUS_APPLIED", mapOf("unit_uid", target.uid, "status", "stun")));
            } else if (events != null) {
                events.add(new CombatEvent("RESIST", mapOf("unit_uid", target.uid, "kind", "stun")));
            }
        }

        if (sdef.dazeChance > 0 && !target.stunned) {
            double effCh = sdef.dazeChance + aStunChance + critBonus - tStunRes;
            if (rng.nextDouble() * 100.0 < effCh) {
                target.dazed = true;
                if (events != null) events.add(new CombatEvent("STATUS_APPLIED", mapOf("unit_uid", target.uid, "status", "daze")));
            } else if (events != null) {
                events.add(new CombatEvent("RESIST", mapOf("unit_uid", target.uid, "kind", "daze")));
            }
        }

        // Target Debuffs
        for (TargetDebuff td : sdef.targetDebuffs) {
            if (td.buff.stat.isEmpty() || td.buff.value == 0.0) continue;
            double effCh = td.chance + aDebuffChance + critBonus - tDebuffRes;
            if (rng.nextDouble() * 100.0 < effCh) {
                target.buffs.add(new ActiveBuff(td.buff.statId, td.buff.condBit, td.buff.stat, td.buff.value, td.buff.duration, td.buff.source, true));
                if (events != null) events.add(new CombatEvent("DEBUFF_APPLIED", mapOf("unit_uid", target.uid, "stat", td.buff.stat, "value", td.buff.value)));
            } else if (events != null) {
                events.add(new CombatEvent("RESIST", mapOf("unit_uid", target.uid, "kind", "debuff")));
            }
        }

        // Enemy Movement (Push / Pull / Shuffle)
        if (sdef.push > 0 || sdef.pull > 0 || sdef.shuffleTarget) {
            double effCh = sdef.moveChance + aMoveChance + critBonus - tMoveRes;
            if (rng.nextDouble() * 100.0 < effCh) {
                if (sdef.push > 0) {
                    shiftUnitRank(target, -sdef.push, events, false);
                } else if (sdef.pull > 0) {
                    shiftUnitRank(target, sdef.pull, events, false);
                } else if (sdef.shuffleTarget) {
                    int slots = countActiveSlots(target.team);
                    if (slots > 1) {
                        int pick = rng.nextInt(slots - 1) + 1;
                        int newR = (pick >= target.rank) ? pick + 1 : pick;
                        shiftUnitRank(target, target.rank - newR, events, false);
                    }
                }
            } else if (events != null) {
                events.add(new CombatEvent("RESIST", mapOf("unit_uid", target.uid, "kind", "move")));
            }
        }
    }

    private static CombatEvent makeHitEvent(UnitState actor, UnitState target, SkillDef sdef, boolean isMarked, int dmg, boolean crit, int hp) {
        Map<String, Object> m = new LinkedHashMap<>(16);
        m.put("actor_uid", actor.uid);
        m.put("target_uid", target.uid);
        m.put("actor_team", actor.team);
        m.put("hero_id", actor.heroId);
        m.put("skill_id", sdef.id);
        m.put("sfx_hint", sdef.sfxHint);
        m.put("is_ranged", sdef.isRanged);
        m.put("marked", isMarked);
        m.put("dmg", dmg);
        m.put("crit", crit);
        m.put("hp", hp);
        return new CombatEvent("HIT", m);
    }

    private void executeRiposte(UnitState responder, UnitState attacker, List<CombatEvent> events) {
        HeroDef hdef = responder.heroDef;
        EffectiveStats rstats = computeEffectiveStats(responder, attacker, "riposte", scratchStatsA);
        EffectiveStats tstats = computeEffectiveStats(attacker, null, "", scratchStatsB);

        if (events != null) {
            events.add(new CombatEvent("RIPOSTE", mapOf(
                    "actor_uid", responder.uid, "target_uid", attacker.uid, "hero_id", responder.heroId
            )));
        }
        double hitChance = computeHitChance(hdef.riposteAcc + rstats.values[StatKey.ACC], tstats.values[StatKey.DODGE]);
        if (rng.nextDouble() * 100.0 >= hitChance) {
            if (events != null) {
                events.add(new CombatEvent("MISS", mapOf(
                        "actor_uid", responder.uid, "target_uid", attacker.uid,
                        "hero_id", responder.heroId, "skill_id", "riposte"
                )));
            }
            return;
        }

        boolean isCrit = rng.nextDouble() * 100.0 < clamp(hdef.riposteCrit + rstats.values[StatKey.CRIT], 0.0, 100.0);
        double mult = Math.max(0.2, 1.0 + hdef.riposteDmgMod + rstats.values[StatKey.DMG_PCT]);
        double raw = isCrit ? (hdef.dmgMax * mult * 1.5) : (rng.nextUniform(hdef.dmgMin, hdef.dmgMax) * mult);

        if (attacker.aegisBlocks > 0) {
            attacker.aegisBlocks--;
            if (events != null) events.add(new CombatEvent("BLOCKED", mapOf("target_uid", attacker.uid, "remaining", attacker.aegisBlocks)));
            return;
        }

        if (responder.hasTrinketFlag(UnitState.TF_SHIELD_SPIKE)
                && rng.nextDouble() * 100.0 < 100.0 + rstats.values[StatKey.BLEED_CHANCE] - tstats.values[StatKey.BLEED_RES]) {
            attacker.dots.add(new ActiveDot(Models.DOT_BLEED, 1, 4, "shield_spike"));
        }
        int dmg = Math.max(1, (int) Math.ceil(raw * (1.0 - tstats.values[StatKey.PROT])));

        if (attacker.atDeathsDoor && attacker.hp <= 0) {
            if (events != null) events.add(makeRiposteHitEvent(responder, attacker, dmg, isCrit, 0));
            double killChance = clamp(100.0 - tstats.values[StatKey.DEATHBLOW_RES] + rstats.values[StatKey.DEATHBLOW_CHANCE], 5.0, 100.0);
            if (rng.nextDouble() * 100.0 < killChance) {
                killHero(attacker, events, !isCrit, "deathblow");
            } else if (events != null) {
                events.add(new CombatEvent("DEATHS_DOOR_RESIST", mapOf("unit_uid", attacker.uid)));
            }
        } else {
            attacker.hp = Math.max(0, attacker.hp - dmg);
            if (events != null) events.add(makeRiposteHitEvent(responder, attacker, dmg, isCrit, attacker.hp));
            if (attacker.hp == 0 && !attacker.atDeathsDoor) {
                attacker.atDeathsDoor = true;
                if (events != null) events.add(new CombatEvent("DEATHS_DOOR", mapOf("unit_uid", attacker.uid)));
            }
        }
    }

    private static CombatEvent makeRiposteHitEvent(UnitState resp, UnitState atk, int dmg, boolean crit, int hp) {
        Map<String, Object> m = new LinkedHashMap<>(16);
        m.put("actor_uid", resp.uid);
        m.put("target_uid", atk.uid);
        m.put("actor_team", resp.team);
        m.put("hero_id", resp.heroId);
        m.put("skill_id", "riposte");
        m.put("sfx_hint", "");
        m.put("is_ranged", false);
        m.put("marked", atk.markedRounds > 0);
        m.put("dmg", dmg);
        m.put("crit", crit);
        m.put("hp", hp);
        return new CombatEvent("HIT", m);
    }

    // ------------------------------------------------------------------ HP / Stress / Death / Guard / Rank
    private void applyHeal(UnitState target, int baseAmt, List<CombatEvent> events, String source, UnitState caster) {
        if (!target.alive || target.isCorpse) return;
        double recvMod = computeEffectiveStats(target, null, "", scratchStatsC).values[StatKey.HEAL_RECV_PCT];
        double doneMod = 0.0;
        if (caster != null) {
            doneMod = computeEffectiveStats(caster, null, "", scratchStatsC).values[StatKey.HEAL_DONE_PCT];
            baseAmt += (int) buffSum(caster, StatKey.HEAL_FLAT);
            if (caster.hasTrinketFlag(UnitState.TF_BLEEDING_SKULL)) {
                target.buffs.add(new ActiveBuff(StatKey.DEATHBLOW_RES, 0, "deathblow_res", -10.0, 3, "bleeding_skull", true));
            }
            if (caster.hasTrinketFlag(UnitState.TF_TINCTURE_OF_IODINE)) {
                target.dots.removeIf(d -> d.kind == Models.DOT_BLIGHT);
                target.buffs.add(new ActiveBuff(StatKey.BLIGHT_RES, 0, "blight_res", 25.0, 2, "tincture_of_iodine", false));
            }
        }
        int amt = Math.max(0, pyRound(baseAmt * Math.max(0.1, 1.0 + doneMod + recvMod)));
        int healed = Math.min(target.maxHp - target.hp, amt);
        target.hp += healed;
        if (target.hp > 0 && target.atDeathsDoor) {
            target.atDeathsDoor = false;
        }
        if (events != null) {
            events.add(new CombatEvent("HEAL", mapOf(
                    "unit_uid", target.uid, "amount", amt, "hp", target.hp, "source", source
            )));
        }
    }

    public void applyStress(UnitState target, int rawAmount, List<CombatEvent> events, String source) {
        if (!target.alive || target.isCorpse || rawAmount <= 0) return;
        double stressRes = computeEffectiveStats(target, null, "", scratchStatsC).values[StatKey.STRESS_RES];
        int amt = Math.max(1, pyRound(rawAmount * (1.0 - stressRes / 100.0)));
        int prev = target.stress;
        target.stress = Math.min(MAX_STRESS, target.stress + amt);
        if (events != null) {
            events.add(new CombatEvent("STRESS", mapOf(
                    "unit_uid", target.uid, "amount", amt, "stress", target.stress, "source", source
            )));
        }

        if (prev < RESOLVE_CHECK_STRESS && target.stress >= RESOLVE_CHECK_STRESS && target.traitId == null) {
            triggerResolveCheck(target, events);
        }
        if (target.stress >= MAX_STRESS) {
            triggerHeartAttack(target, events);
        }
    }

    private void healStress(UnitState target, int amount, List<CombatEvent> events, String source) {
        if (!target.alive || target.isCorpse || amount <= 0) return;
        int before = target.stress;
        target.stress = Math.max(0, target.stress - amount);
        if (events != null) {
            events.add(new CombatEvent("STRESS_HEAL", mapOf(
                    "unit_uid", target.uid, "amount", before - target.stress, "stress", target.stress, "source", source
            )));
        }
    }

    private void triggerResolveCheck(UnitState unit, List<CombatEvent> events) {
        GameData gd = GameData.get();
        if ("flagellant".equals(unit.heroId)) {
            TraitDef tdef = gd.rapturous;
            unit.traitId = tdef.id;
            unit.isAfflicted = true;
            for (BuffDef bd : tdef.buffs) unit.buffs.add(ActiveBuff.permanentFromDef(bd));
            if (events != null) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("unit_uid", unit.uid);
                m.put("trait_id", tdef.id);
                m.put("name_en", tdef.nameEn);
                m.put("name_zh", tdef.nameZh);
                m.put("is_virtue", false);
                m.put("bark_en", tdef.barksEn.length > 0 ? tdef.barksEn[0] : "");
                m.put("bark_zh", tdef.barksZh.length > 0 ? tdef.barksZh[0] : "");
                events.add(new CombatEvent("RESOLVE_CHECK", m));
            }
            return;
        }

        double virtueCh = computeEffectiveStats(unit, null, "", scratchStatsC).values[StatKey.VIRTUE_CHANCE];
        double virtuePct = clamp(BASE_VIRTUE_CHANCE + virtueCh, 0.0, 100.0);
        boolean isVirtue = rng.nextDouble() * 100.0 < virtuePct;
        List<TraitDef> pool = isVirtue ? gd.virtueList : gd.afflictionList;
        TraitDef tdef = pool.get(rng.nextInt(pool.size()));
        unit.traitId = tdef.id;
        unit.isVirtuous = isVirtue;
        unit.isAfflicted = !isVirtue;
        for (BuffDef bd : tdef.buffs) unit.buffs.add(ActiveBuff.permanentFromDef(bd));

        if (isVirtue) {
            unit.stress = Math.max(0, unit.stress - 30);
            for (UnitState ally : livingHeroes(unit.team)) {
                if (ally != unit) healStress(ally, 10, events, "virtue");
            }
        }
        if (events != null) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("unit_uid", unit.uid);
            m.put("trait_id", tdef.id);
            m.put("name_en", tdef.nameEn);
            m.put("name_zh", tdef.nameZh);
            m.put("is_virtue", isVirtue);
            m.put("stress", unit.stress);
            m.put("bark_en", tdef.barksEn.length > 0 ? tdef.barksEn[0] : "");
            m.put("bark_zh", tdef.barksZh.length > 0 ? tdef.barksZh[0] : "");
            events.add(new CombatEvent("RESOLVE_CHECK", m));
        }
    }

    private void triggerHeartAttack(UnitState unit, List<CombatEvent> events) {
        if (events != null) {
            events.add(new CombatEvent("HEART_ATTACK", mapOf("unit_uid", unit.uid, "at_deaths_door", unit.atDeathsDoor)));
        }
        if (unit.atDeathsDoor && unit.hp <= 0) {
            killHero(unit, events, false, "heart_attack");
        } else {
            unit.hp = 0;
            unit.atDeathsDoor = true;
            unit.stress = HEART_ATTACK_RESET_STRESS - pyRound(30.0 * buffSum(unit, StatKey.HEART_HEAL_PCT));
            if (events != null) events.add(new CombatEvent("DEATHS_DOOR", mapOf("unit_uid", unit.uid)));
            for (UnitState ally : livingHeroes(unit.team)) {
                if (ally != unit) applyStress(ally, 10, events, "ally_heart_attack");
            }
        }
    }

    private void killHero(UnitState unit, List<CombatEvent> events, boolean leaveCorpse, String cause) {
        breakGuardOnOrBy(unit, events);
        int team = unit.team;
        String heroId = unit.heroId;

        if (events != null) {
            events.add(new CombatEvent("DEATHBLOW", mapOf(
                    "unit_uid", unit.uid, "hero_id", heroId, "team", team,
                    "cause", cause, "leave_corpse", leaveCorpse
            )));
        }

        if ("flagellant".equals(heroId)) {
            for (UnitState ally : livingHeroes(team)) {
                if (ally != unit) applyHeal(ally, 8, events, "martyrdom", null);
            }
        }

        if (leaveCorpse && countLivingHeroes(team) > 1) {
            unit.isCorpse = true;
            unit.alive = true;
            unit.hp = CORPSE_HP;
            unit.maxHp = CORPSE_HP;
            unit.corpseRounds = 0;
            unit.atDeathsDoor = false;
            unit.stunned = false;
            unit.dazed = false;
            unit.stealthRounds = 0;
            unit.markedRounds = 0;
            unit.riposteRounds = 0;
            unit.aegisBlocks = 0;
            unit.dots.clear();
            unit.buffs.clear();
            if (events != null) {
                events.add(new CombatEvent("CORPSE_SPAWNED", mapOf(
                        "unit_uid", unit.uid, "hero_id", heroId, "rank", unit.rank
                )));
            }
        } else {
            unit.alive = false;
            unit.isCorpse = false;
            unit.hp = 0;
            syncRanks(team);
        }

        for (UnitState ally : livingHeroes(team)) {
            applyStress(ally, 10, events, "ally_death");
        }
    }

    private void setGuard(UnitState guardian, UnitState target, int rounds, List<CombatEvent> events) {
        breakGuardBy(guardian, events);
        if (target.guardedByUid != null) {
            UnitState oldG = findUnit(target.guardedByUid);
            if (oldG != null) {
                oldG.guardingUid = null;
                oldG.guardRounds = 0;
            }
        }
        guardian.guardingUid = target.uid;
        int effRounds = pyRound(rounds * (1.0 + buffSum(guardian, StatKey.GUARD_DURATION) / 100.0));
        guardian.guardRounds = effRounds;
        target.guardedByUid = guardian.uid;
        if (events != null) {
            events.add(new CombatEvent("GUARD_SET", mapOf(
                    "guardian_uid", guardian.uid, "protected_uid", target.uid, "rounds", effRounds
            )));
        }
    }

    private void breakGuardBy(UnitState guardian, List<CombatEvent> events) {
        if (guardian.guardingUid != null) {
            UnitState prot = findUnit(guardian.guardingUid);
            if (prot != null && guardian.uid.equals(prot.guardedByUid)) {
                prot.guardedByUid = null;
            }
            guardian.guardingUid = null;
            guardian.guardRounds = 0;
            if (events != null) events.add(new CombatEvent("GUARD_BROKEN", mapOf("guardian_uid", guardian.uid)));
        }
    }

    private void breakGuardOnOrBy(UnitState unit, List<CombatEvent> events) {
        breakGuardBy(unit, events);
        if (unit.guardedByUid != null) {
            UnitState g = findUnit(unit.guardedByUid);
            if (g != null) breakGuardBy(g, events);
            unit.guardedByUid = null;
        }
    }

    private void shiftUnitRank(UnitState unit, int deltaForward, List<CombatEvent> events, boolean voluntary) {
        int team = unit.team;
        ArrayList<UnitState> src = state.teams[team];
        ArrayList<UnitState> alive = new ArrayList<>(4);
        ArrayList<UnitState> dead = new ArrayList<>(4);
        int oldIdx = -1;
        for (int i = 0, n = src.size(); i < n; i++) {
            UnitState u = src.get(i);
            if (u.alive) {
                if (u == unit) oldIdx = alive.size();
                alive.add(u);
            } else {
                dead.add(u);
            }
        }
        if (oldIdx < 0 || alive.size() <= 1) return;
        int newIdx = (int) clamp(oldIdx - deltaForward, 0, alive.size() - 1);
        if (newIdx == oldIdx) return;

        alive.remove(oldIdx);
        alive.add(newIdx, unit);
        for (int i = 0, n = alive.size(); i < n; i++) alive.get(i).rank = i + 1;
        src.clear();
        src.addAll(alive);
        src.addAll(dead);

        if (events != null) {
            events.add(new CombatEvent("MOVE", mapOf(
                    "unit_uid", unit.uid, "from_rank", oldIdx + 1, "to_rank", newIdx + 1, "voluntary", voluntary
            )));
        }
    }

    // ------------------------------------------------------------------ End of Turn & Round Management
    private void finishTurnAndAdvance(UnitState actor, List<CombatEvent> events) {
        state.lockedActorUid = null;
        actor.actedThisRound = true;
        if (buffSum(actor, StatKey.DAZE) != 0.0) {
            actor.dazed = true;
        }

        // Tick buffs (freshly self-applied buffs & stun_recovery skip same-turn decrement so they last full duration!)
        if (!actor.buffs.isEmpty()) {
            int kept = 0;
            for (int i = 0, n = actor.buffs.size(); i < n; i++) {
                ActiveBuff b = actor.buffs.get(i);
                if (b.roundsLeft < 0 || SECOND_TURN_DODGE_SOURCE.equals(b.source)) {
                    b.freshThisTurn = false;
                    actor.buffs.set(kept++, b);
                } else if (b.freshThisTurn) {
                    b.freshThisTurn = false;
                    actor.buffs.set(kept++, b);
                } else if (b.roundsLeft > 1) {
                    b.roundsLeft--;
                    actor.buffs.set(kept++, b);
                }
            }
            if (kept < actor.buffs.size()) actor.buffs.subList(kept, actor.buffs.size()).clear();
        }
        actor.finaleBonusPct = buffSum(actor, StatKey.FINALE_DAMAGE);

        if (!actor.skillCooldowns.isEmpty()) {
            Iterator<Map.Entry<String, Integer>> it = actor.skillCooldowns.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Integer> e = it.next();
                int next = e.getValue() - 1;
                if (next <= 0) it.remove();
                else e.setValue(next);
            }
        }

        int freshMask = actor.freshStatusMask;
        actor.freshStatusMask = 0;
        if (actor.markedRounds > 0 && (freshMask & UnitState.FRESH_MARK) == 0) actor.markedRounds--;
        if (actor.stealthRounds > 0 && (freshMask & UnitState.FRESH_STEALTH) == 0) actor.stealthRounds--;
        if (actor.riposteRounds > 0 && (freshMask & UnitState.FRESH_RIPOSTE) == 0) actor.riposteRounds--;
        if (actor.guardRounds > 0 && (freshMask & UnitState.FRESH_GUARD) == 0) {
            actor.guardRounds--;
            if (actor.guardRounds <= 0) breakGuardBy(actor, events);
        }

        // Victory check
        int alive0 = countLivingHeroes(0);
        int alive1 = countLivingHeroes(1);
        if (alive0 == 0 || alive1 == 0) {
            state.finished = true;
            if (alive0 > 0) state.winner = 0;
            else if (alive1 > 0) state.winner = 1;
            else state.winner = actor.team;
            state.winReason = "elimination";
            if (events != null) {
                events.add(new CombatEvent("MATCH_END", mapOf(
                        "winner", state.winner, "reason", "elimination", "round", state.roundNum
                )));
            }
            return;
        }

        int curTeam = state.activeTeam;
        int oppTeam = 1 - curTeam;
        boolean oppHas = hasSelectableActors(oppTeam);
        boolean curHas = hasSelectableActors(curTeam);

        if (oppHas) {
            state.activeTeam = oppTeam;
        } else if (curHas) {
            state.activeTeam = curTeam;
        } else {
            startNextRound(events, curTeam);
        }
    }

    private void startNextRound(List<CombatEvent> events, int lastActorTeam) {
        if (state.roundNum == 1) {
            for (int t = 0; t < 2; t++) {
                ArrayList<UnitState> list = state.teams[t];
                for (int i = 0, n = list.size(); i < n; i++) {
                    list.get(i).buffs.removeIf(b -> SECOND_TURN_DODGE_SOURCE.equals(b.source));
                }
            }
        }
        for (int t = 0; t < 2; t++) {
            boolean changed = false;
            ArrayList<UnitState> list = state.teams[t];
            for (int i = 0, n = list.size(); i < n; i++) {
                UnitState u = list.get(i);
                if (u.alive && u.isCorpse) {
                    u.corpseRounds++;
                    if (u.corpseRounds >= CORPSE_MAX_ROUNDS) {
                        u.alive = false;
                        u.hp = 0;
                        changed = true;
                        if (events != null) events.add(new CombatEvent("CORPSE_DECAYED", mapOf("unit_uid", u.uid)));
                    }
                }
            }
            if (changed) syncRanks(t);
        }

        state.roundNum++;
        if (state.roundNum > state.maxRounds) {
            int winner = compareTiebreak(0, 1) >= 0 ? 0 : 1;
            state.finished = true;
            state.winner = winner;
            state.winReason = "stalemate_decision";
            if (events != null) {
                events.add(new CombatEvent("MATCH_END", mapOf(
                        "winner", winner, "reason", "stalemate_decision", "round", state.roundNum - 1
                )));
            }
            return;
        }

        state.lockedActorUid = null;
        for (int t = 0; t < 2; t++) {
            ArrayList<UnitState> list = state.teams[t];
            for (int i = 0, n = list.size(); i < n; i++) {
                UnitState u = list.get(i);
                if (u.alive && !u.isCorpse) {
                    u.actedThisRound = false;
                    u.transformedThisRound = false;
                }
            }
        }

        int nextFirst = 1 - lastActorTeam;
        state.firstTeamThisRound = nextFirst;
        state.activeTeam = nextFirst;
        if (events != null) {
            events.add(new CombatEvent("ROUND_START", mapOf("round", state.roundNum, "first_team", nextFirst)));
        }
    }

    private int compareTiebreak(int t0, int t1) {
        List<UnitState> h0 = livingHeroes(t0);
        List<UnitState> h1 = livingHeroes(t1);
        if (h0.size() != h1.size()) return Integer.compare(h0.size(), h1.size());
        double r0 = 0.0;
        for (UnitState u : h0) r0 += (double) u.hp / Math.max(1, u.maxHp);
        double r1 = 0.0;
        for (UnitState u : h1) r1 += (double) u.hp / Math.max(1, u.maxHp);
        r0 = Math.rint(r0 * 10000.0) / 10000.0;
        r1 = Math.rint(r1 * 10000.0) / 10000.0;
        if (r0 != r1) return Double.compare(r0, r1);
        int s0 = 0;
        for (UnitState u : h0) s0 -= u.stress;
        int s1 = 0;
        for (UnitState u : h1) s1 -= u.stress;
        return Integer.compare(s0, s1);
    }

    // ------------------------------------------------------------------ Serialization & Observations
    public Map<String, Object> serializeUnit(UnitState unit, boolean revealTrinkets) {
        EffectiveStats stats = getEffectiveStats(unit);
        HeroDef hdef = unit.heroDef;
        boolean isSelectable = false;
        if (!state.finished && unit.team == state.activeTeam) {
            for (UnitState u : getSelectableActors(unit.team)) {
                if (u == unit) {
                    isSelectable = true;
                    break;
                }
            }
        }
        Map<String, Object> m = new LinkedHashMap<>(40);
        m.put("uid", unit.uid);
        m.put("team", unit.team);
        m.put("hero_id", unit.heroId);
        m.put("name", unit.name);
        m.put("name_en", hdef.nameEn);
        m.put("name_zh", hdef.nameZh);
        m.put("variant", unit.variant);
        m.put("rank", unit.rank);
        m.put("hp", unit.hp);
        m.put("max_hp", unit.maxHp);
        m.put("stress", unit.stress);
        m.put("max_stress", MAX_STRESS);
        m.put("alive", unit.alive);
        m.put("is_corpse", unit.isCorpse);
        m.put("corpse_rounds", unit.corpseRounds);
        m.put("acted_this_round", unit.actedThisRound);
        m.put("selectable", isSelectable);
        m.put("stunned", unit.stunned);
        m.put("dazed", unit.dazed);
        m.put("at_deaths_door", unit.atDeathsDoor);
        m.put("marked_rounds", unit.markedRounds);
        m.put("stealth_rounds", unit.stealthRounds);
        m.put("riposte_rounds", unit.riposteRounds);
        m.put("guarding_uid", unit.guardingUid);
        m.put("guarded_by_uid", unit.guardedByUid);
        m.put("guard_rounds", unit.guardRounds);
        m.put("aegis_blocks", unit.aegisBlocks);
        m.put("is_beast", unit.isBeast);
        m.put("transformed_this_round", unit.transformedThisRound);
        m.put("finale_bonus_pct", Math.rint(unit.finaleBonusPct * 100.0) / 100.0);
        m.put("trait_id", unit.traitId);
        m.put("is_virtuous", unit.isVirtuous);
        m.put("is_afflicted", unit.isAfflicted);
        m.put("equipped_skills", new ArrayList<>(unit.equippedSkills));
        m.put("trinkets", revealTrinkets ? new ArrayList<>(unit.trinkets) : Collections.emptyList());
        m.put("skill_uses", new LinkedHashMap<>(unit.skillUses));
        m.put("skill_cooldowns", new LinkedHashMap<>(unit.skillCooldowns));
        List<Map<String, Object>> dotList = new ArrayList<>(unit.dots.size());
        for (ActiveDot d : unit.dots) {
            dotList.add(mapOf("kind", d.kindName(), "amount", d.amount, "rounds_left", d.roundsLeft));
        }
        m.put("dots", dotList);

        // Serialize all active combat buffs & debuffs (excluding passive permanent trinket/trait buffs)
        GameData gd = GameData.get();
        List<Map<String, Object>> buffList = new ArrayList<>(unit.buffs.size());
        for (ActiveBuff b : unit.buffs) {
            if (b.source != null && b.source.startsWith("trinket:") && b.roundsLeft < 0) continue;
            if (b.source != null && gd.traits.containsKey(b.source) && b.roundsLeft < 0) continue;
            buffList.add(mapOf(
                    "stat", b.stat,
                    "value", Math.rint(b.value * 1000.0) / 1000.0,
                    "rounds_left", b.roundsLeft,
                    "source", b.source != null ? b.source : "",
                    "is_debuff", b.isDebuff
            ));
        }
        m.put("buffs", buffList);
        m.put("stats", stats.toRoundedMap());
        m.put("dmg_min", ("abomination".equals(hdef.id) && unit.isBeast) ? hdef.beastDmgMin : hdef.dmgMin);
        m.put("dmg_max", ("abomination".equals(hdef.id) && unit.isBeast) ? hdef.beastDmgMax : hdef.dmgMax);
        return m;
    }

    public Observation getObservation(int viewerTeam) {
        return getObservation(viewerTeam, true);
    }

    /** Lightweight observation for bots explicitly opting out of unit snapshots. */
    public Observation getActionObservation(int viewerTeam) {
        return new Observation(viewerTeam, state.roundNum, state.activeTeam,
                !state.finished && state.activeTeam == viewerTeam, state.finished,
                state.winner, state.winReason, Collections.emptyList(), Collections.emptyList(),
                getLegalActions(viewerTeam));
    }

    public Observation getObservation(int viewerTeam, boolean revealEnemyTrinkets) {
        int enemyTeam = 1 - viewerTeam;
        List<Map<String, Object>> myUnits = new ArrayList<>(4);
        for (UnitState u : state.teams[viewerTeam]) myUnits.add(serializeUnit(u, true));
        List<Map<String, Object>> enemyUnits = new ArrayList<>(4);
        for (UnitState u : state.teams[enemyTeam]) enemyUnits.add(serializeUnit(u, revealEnemyTrinkets));
        List<Action> legal = (state.activeTeam == viewerTeam) ? getLegalActions(viewerTeam) : Collections.emptyList();
        return new Observation(
                viewerTeam,
                state.roundNum,
                state.activeTeam,
                !state.finished && state.activeTeam == viewerTeam,
                state.finished,
                state.winner,
                state.winReason,
                myUnits,
                enemyUnits,
                legal
        );
    }

    /**
     * Fast 64-bit state hash for Transposition Tables in search bots.
     * Hashes active team, round, and all unit combat states (HP, stress, rank, flags, timers, DoTs).
     */
    public long computeStateHash() {
        long h = 0x9E3779B97F4A7C15L;
        h ^= (state.activeTeam * 0xBF58476D1CE4E5B9L);
        h = Long.rotateLeft(h, 13) ^ (state.roundNum * 0x94D049BB133111EBL);
        if (state.lockedActorUid != null) {
            h = Long.rotateLeft(h, 17) ^ (state.lockedActorUid.hashCode() * 0x517CC1B727220A95L);
        }
        for (int t = 0; t < 2; t++) {
            ArrayList<UnitState> team = state.teams[t];
            for (int i = 0, n = team.size(); i < n; i++) {
                UnitState u = team.get(i);
                long uh = ((long) u.hp & 0xFFL)
                        | (((long) u.stress & 0x1FFL) << 8)
                        | (((long) u.rank & 0x7L) << 17)
                        | (u.alive ? (1L << 20) : 0L)
                        | (u.isCorpse ? (1L << 21) : 0L)
                        | (u.actedThisRound ? (1L << 22) : 0L)
                        | (u.stunned ? (1L << 23) : 0L)
                        | (u.dazed ? (1L << 24) : 0L)
                        | (u.atDeathsDoor ? (1L << 25) : 0L)
                        | (u.isBeast ? (1L << 26) : 0L)
                        | (((long) u.markedRounds & 0xFL) << 27)
                        | (((long) u.stealthRounds & 0xFL) << 31)
                        | (((long) u.riposteRounds & 0xFL) << 35)
                        | (((long) u.aegisBlocks & 0x7L) << 39)
                        | (((long) u.guardRounds & 0xFL) << 42)
                        | (u.guardedByUid != null ? (1L << 46) : 0L)
                        | (u.isVirtuous ? (1L << 47) : 0L)
                        | (u.isAfflicted ? (1L << 48) : 0L)
                        | (((long) u.dots.size() & 0xFL) << 49)
                        | (((long) u.buffs.size() & 0x1FL) << 53)
                        | (u.transformedThisRound ? (1L << 58) : 0L);
                h ^= mix64(uh ^ ((long) (t * 4 + i + 1) * 0x517CC1B727220A95L));
                h = Long.rotateLeft(h, 7);
            }
        }
        return h;
    }

    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>(kv.length);
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
