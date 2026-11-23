package com.circus;

import com.circus.assets.AssetResolver;
import com.circus.assets.FmodAudioExtractor;
import com.circus.assets.LocalizationStore;
import com.circus.assets.SpineConverter;
import com.circus.bot.Bot;
import com.circus.bot.BotRegistry;
import com.circus.bot.CircusSearchBot;
import com.circus.bot.RandomBot;
import com.circus.bot.TeamConfigStore;
import com.circus.engine.ArenaEngine;
import com.circus.engine.GameData;
import com.circus.engine.Models.*;
import com.circus.engine.Simulator;
import com.circus.engine.StatKey;
import com.circus.server.ArenaServer;
import com.circus.util.Json;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/**
 * Full verification & performance benchmark suite for the Java Butcher's Circus backend.
 * Mirrors every test in tests/test_all.py plus FMOD FFM stress & search-depth benchmarks.
 */
public final class CircusTestSuite {
    private static int passed = 0;
    private static int failed = 0;

    private static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError("Assertion failed: " + msg);
    }

    private static void checkEq(Object expected, Object actual, String msg) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(msg + " | expected=" + expected + " actual=" + actual);
        }
    }

    private static void checkClose(double expected, double actual, double tol, String msg) {
        if (Math.abs(expected - actual) > tol) {
            throw new AssertionError(msg + " | expected=" + expected + " actual=" + actual);
        }
    }

    private static void runTest(String name, ThrowingRunnable r) {
        long t0 = System.nanoTime();
        try {
            r.run();
            double ms = (System.nanoTime() - t0) / 1_000_000.0;
            System.out.printf("  [PASS] %-54s (%.1f ms)%n", name, ms);
            passed++;
        } catch (Throwable t) {
            System.out.printf("  [FAIL] %-54s -> %s%n", name, t);
            t.printStackTrace(System.out);
            failed++;
        }
    }

    @FunctionalInterface
    interface ThrowingRunnable { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        System.out.println("=== Darkest Dungeon: The Butcher's Circus Java 26 Verification Suite ===");

        runTest("test_18_heroes_126_skills_103_trinkets", CircusTestSuite::testDataCounts);
        runTest("test_hit_chance_formula_and_guaranteed_hit", CircusTestSuite::testHitChanceFormula);
        runTest("test_alternating_turns_and_round_initiative", CircusTestSuite::testAlternatingTurns);
        runTest("test_arena_exclusives_and_conditional_trinket_damage", CircusTestSuite::testConditionalTrinkets);
        runTest("test_stygian_embrace_from_legacy_loadout", CircusTestSuite::testStygianEmbrace);
        runTest("test_daze_forces_act_last", CircusTestSuite::testDazeActLast);
        runTest("test_stun_forces_pass_and_grants_stun_resist", CircusTestSuite::testStunBehavior);
        runTest("test_riposte_rules_debuffs_and_buff_serialization", CircusTestSuite::testRiposteDebuffsAndBuffSerialization);
        runTest("test_dot_cannot_trigger_deathblow", CircusTestSuite::testDotNoDeathblow);
        runTest("test_heart_attack_rules", CircusTestSuite::testHeartAttackRules);
        runTest("test_flagellant_rapturous_and_abom_transform", CircusTestSuite::testFlagellantAndAbom);
        runTest("test_clone_determinism_and_search_bot_kill_priority", CircusTestSuite::testCloneAndSearchBot);
        runTest("test_five_meta_teams_and_no_duplicate_classes", CircusTestSuite::testMetaTeamsAndValidation);
        runTest("test_assets_spine_fmod_openmemory_localization", CircusTestSuite::testAssetsSpineFmodLoc);
        runTest("test_team_json_decoupled_bot_class_loading", CircusTestSuite::testTeamJsonAndBotClasses);
        runTest("test_tournament_complete_report_json_and_http_endpoints", CircusTestSuite::testTournamentReportAndApis);
        runTest("test_http_range_and_local_2p_websocket_match", CircusTestSuite::testHttpAndDualWs);

        System.out.printf("%nSummary: %d passed, %d failed.%n", passed, failed);
        if (failed > 0) System.exit(1);

        runBenchmarks();
    }

    private static TeamConfig[] sampleTeams() {
        List<PresetDef> presets = GameData.get().getPresets();
        return new TeamConfig[]{presets.get(0).team.copy(), presets.get(1).team.copy()};
    }

    private static void testHitChanceFormula() {
        checkClose(5.0, ArenaEngine.computeHitChance(0.0, 20.0), 1e-9, "diff=-20 -> 5%");
        checkClose(5.0, ArenaEngine.computeHitChance(10.0, 10.0), 1e-9, "diff=0 -> 5%");
        checkClose(5.0, ArenaEngine.computeHitChance(14.9, 10.0), 1e-9, "diff=4.9 -> 5%");
        checkClose(10.0, ArenaEngine.computeHitChance(15.0, 10.0), 1e-9, "diff=5 -> 10%");
        checkClose(55.0, ArenaEngine.computeHitChance(85.0, 35.0), 1e-9, "diff=50 -> 55%");
        checkClose(99.0, ArenaEngine.computeHitChance(104.0, 10.0), 1e-9, "diff=94 -> 99%");
        checkClose(100.0, ArenaEngine.computeHitChance(105.0, 10.0), 1e-9, "diff=95 -> 100%");
        checkClose(100.0, ArenaEngine.computeHitChance(140.0, 10.0), 1e-9, "diff=130 -> 100%");

        // Verify engine attack with ACC - DODGE >= 95 never misses across 200 seeds
        TeamConfig[] teams = sampleTeams();
        for (int seed = 1; seed <= 200; seed++) {
            ArenaEngine eng = new ArenaEngine(teams[0], teams[1], seed);
            Action attack = null;
            for (Action a : eng.getLegalActions()) {
                if ("SKILL".equals(a.kind) && a.targetUids.size() == 1) {
                    UnitState actor = eng.findUnit(a.actorUid);
                    UnitState target = eng.findUnit(a.targetUids.get(0));
                    SkillDef sdef = actor.heroDef.getSkill(a.skillId);
                    if (sdef != null && "enemy".equals(sdef.targetTeam) && !sdef.isAoe && !sdef.randomTarget && sdef.dmgMod > -1.0 && target != null) {
                        actor.buffs.add(new ActiveBuff(StatKey.ACC, 0, "acc", 100.0, 3, "test_acc", false, true));
                        target.aegisBlocks = 0;
                        attack = a;
                        break;
                    }
                }
            }
            check(attack != null, "found single-target attack");
            List<CombatEvent> evs = eng.step(attack);
            boolean missed = evs.stream().anyMatch(e -> "MISS".equals(e.type));
            check(!missed, "guaranteed hit should never miss on seed " + seed);
        }
    }

    private static void testDataCounts() {
        GameData gd = GameData.get();
        checkEq(18, gd.heroOrder.size(), "heroOrder size");
        checkEq(18, gd.heroes.size(), "heroes size");
        int totalSkills = 0;
        for (HeroDef h : gd.heroes.values()) totalSkills += h.skills.length;
        checkEq(126, totalSkills, "total skills");
        checkEq(103, gd.trinkets.size(), "trinkets size");
    }

    private static void testAlternatingTurns() {
        TeamConfig[] teams = sampleTeams();
        ArenaEngine eng = new ArenaEngine(teams[0], teams[1], 101L);
        int first = eng.state.activeTeam;
        int lastTeam = -1;
        for (int stepIdx = 0; stepIdx < 8; stepIdx++) {
            int expected = (stepIdx % 2 == 0) ? first : (1 - first);
            checkEq(expected, eng.state.activeTeam, "step " + stepIdx);
            Action passAct = eng.getLegalActions(expected).stream().filter(a -> "PASS".equals(a.kind)).findFirst().orElseThrow();
            eng.step(passAct);
            lastTeam = expected;
        }
        checkEq(1 - first, lastTeam, "lastTeam R1");
        checkEq(2, eng.state.roundNum, "round 2");
        checkEq(first, eng.state.firstTeamThisRound, "R2 first");
        checkEq(first, eng.state.activeTeam, "R2 active");

        // 4v3 unequal team sizes
        List<UnitState> oppLiving = eng.livingHeroes(1 - first);
        oppLiving.get(oppLiving.size() - 1).alive = false;
        eng.syncRanks(1 - first);
        int r2Last = -1;
        while (eng.state.roundNum == 2) {
            r2Last = eng.state.activeTeam;
            Action passAct = eng.getLegalActions(r2Last).stream().filter(a -> "PASS".equals(a.kind)).findFirst().orElseThrow();
            eng.step(passAct);
        }
        checkEq(first, r2Last, "R2 last actor");
        checkEq(3, eng.state.roundNum, "round 3");
        checkEq(1 - first, eng.state.firstTeamThisRound, "R3 first");
        checkEq(1 - first, eng.state.activeTeam, "R3 active");
    }

    private static void testConditionalTrinkets() {
        TeamConfig[] teams = sampleTeams();
        teams[0].heroes.set(0, new HeroLoadout("plague_doctor", List.of("incision"),
                List.of("sacred_blade", "amputation_saw", "amputation_saw"), "A"));
        ArenaEngine eng = new ArenaEngine(teams[0], teams[1], 2L);
        UnitState doc = eng.livingHeroes(0).get(0);
        UnitState target = eng.livingHeroes(1).get(0);
        checkEq(List.of("amputation_saw"), doc.trinkets, "filtered trinkets");
        var melee = eng.getEffectiveStats(doc, target, "melee");
        var ranged = eng.getEffectiveStats(doc, target, "ranged");
        checkClose(0.30, melee.get(StatKey.DMG_PCT) - ranged.get(StatKey.DMG_PCT), 1e-6, "melee bonus");
        target.dots.add(new ActiveDot("bleed", 2, 3, "test"));
        var rangedBleed = eng.getEffectiveStats(doc, target, "ranged");
        checkClose(10.0, rangedBleed.get(StatKey.ACC) - ranged.get(StatKey.ACC), 1e-6, "vs bleed acc");

        GameData gd = GameData.get();
        for (PresetDef p : gd.getPresets()) {
            for (HeroLoadout h : p.team.heroes) {
                for (String tid : h.trinkets) {
                    TrinketDef td = gd.trinkets.get(tid);
                    check(td != null && (td.heroClass.isEmpty() || td.heroClass.equals(h.heroId)), "preset trinket valid: " + tid);
                }
            }
        }
    }

    private static void testStygianEmbrace() {
        TeamConfig[] teams = sampleTeams();
        teams[0].heroes.set(0, new HeroLoadout("occultist", List.of("hands_from_abyss"), List.of(), "A"));
        ArenaEngine eng = new ArenaEngine(teams[0], teams[1], 2L);
        eng.state.activeTeam = 0;
        List<UnitState> heroes = eng.livingHeroes(0);
        UnitState actor = heroes.get(0);
        UnitState ally = heroes.get(1);
        ally.hp -= 5;
        List<Action> actions = eng.getLegalActions().stream().filter(a -> "stygian_embrace".equals(a.skillId)).toList();
        checkEq(3, actions.size(), "3 other allies targeted");
        Action chosen = actions.stream().filter(a -> a.targetUids.equals(List.of(ally.uid))).findFirst().orElseThrow();
        List<CombatEvent> evs = eng.step(chosen);
        checkEq(2, ally.stealthRounds, "ally stealthed 2 rounds");
        check(evs.stream().anyMatch(e -> "HEAL".equals(e.type) && List.of(1, 2).contains(e.data.get("amount"))), "heal 1-2");
        double boosted = eng.getEffectiveStats(ally).get(StatKey.STRESS_DMG_PCT);
        ally.stealthRounds = 0;
        checkClose(0.5, boosted - eng.getEffectiveStats(ally).get(StatKey.STRESS_DMG_PCT), 1e-6, "stealth stress dmg bonus");
    }

    private static void testDazeActLast() {
        TeamConfig[] teams = sampleTeams();
        ArenaEngine eng = new ArenaEngine(teams[0], teams[1], 7L);
        int active = eng.state.activeTeam;
        List<UnitState> units = eng.livingHeroes(active);
        units.get(0).dazed = true;
        List<UnitState> sel = eng.getSelectableActors(active);
        check(!sel.contains(units.get(0)), "dazed excluded");
        checkEq(3, sel.size(), "3 non-dazed selectable");
        for (int i = 1; i < units.size(); i++) units.get(i).actedThisRound = true;
        List<UnitState> selAfter = eng.getSelectableActors(active);
        checkEq(List.of(units.get(0)), selAfter, "dazed becomes selectable last");
    }

    private static void testStunBehavior() {
        TeamConfig[] teams = sampleTeams();
        ArenaEngine eng = new ArenaEngine(teams[0], teams[1], 12L);
        int active = eng.state.activeTeam;
        UnitState u = eng.livingHeroes(active).get(0);
        u.stunned = true;
        List<Action> legalU = eng.getLegalActions(active).stream().filter(a -> a.actorUid.equals(u.uid)).toList();
        checkEq(1, legalU.size(), "stunned only 1 action");
        checkEq("PASS", legalU.get(0).kind, "stunned must PASS");
        double baseSr = eng.getEffectiveStats(u).get(StatKey.STUN_RES);
        List<CombatEvent> evs = eng.step(legalU.get(0));
        check(evs.stream().anyMatch(e -> "STUN_SKIP".equals(e.type)), "STUN_SKIP emitted");
        check(!u.stunned, "stun cleared");
        checkClose(baseSr + 50.0, eng.getEffectiveStats(u).get(StatKey.STUN_RES), 1e-6, "+50% stun res");
        // Verify stun_recovery lasts FULL 2 rounds after stun-skip turn AND is serialized in buffs!
        var recBuff = u.buffs.stream().filter(b -> "stun_recovery".equals(b.source)).findFirst().orElse(null);
        check(recBuff != null && recBuff.roundsLeft == 2, "stun_recovery has 2 rounds left after recovery turn");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> serBuffs = (List<Map<String, Object>>) eng.serializeUnit(u, true).get("buffs");
        check(serBuffs.stream().anyMatch(b -> "stun_recovery".equals(b.get("source")) && Integer.valueOf(2).equals(b.get("rounds_left"))),
                "stun_recovery serialized in unit.buffs");
    }

    @SuppressWarnings("unchecked")
    private static void testRiposteDebuffsAndBuffSerialization() {
        TeamConfig t0 = new TeamConfig("T0", List.of(
                new HeroLoadout("man_at_arms", List.of("bellow", "crush", "defender", "retribution"), List.of(), "A"),
                new HeroLoadout("bounty_hunter", List.of("target_tag", "collect_bounty", "flashbang", "finish_him"), List.of(), "A"),
                new HeroLoadout("musketeer", List.of("smokescreen", "aimed_shot", "call_the_shot", "skeet_shot"), List.of(), "A"),
                new HeroLoadout("grave_robber", List.of("shadow_fade", "lunge", "thrown_dagger", "toxin_trickery"), List.of(), "A")
        ));
        TeamConfig t1 = new TeamConfig("T1", List.of(
                new HeroLoadout("highwayman", List.of("duelist_advance", "wicked_slice", "point_blank_shot", "pistol_shot"), List.of(), "A"),
                new HeroLoadout("crusader", List.of("smite", "stunning_blow", "battle_heal", "holy_lance"), List.of(), "A"),
                new HeroLoadout("vestal", List.of("judgement", "dazzling_light", "divine_grace", "gods_comfort"), List.of(), "A"),
                new HeroLoadout("jester", List.of("dirk_stab", "heroic_end", "battle_ballad", "inspiring_tune"), List.of(), "A")
        ));
        ArenaEngine eng = new ArenaEngine(t0, t1, 42L);
        eng.state.activeTeam = 0;

        UnitState maa = eng.livingHeroes(0).get(0);
        UnitState bh = eng.livingHeroes(0).get(1);
        UnitState gr = eng.livingHeroes(0).get(3);
        UnitState hwm = eng.livingHeroes(1).get(0);
        hwm.riposteRounds = 3;

        // 1. Non-damaging skill (bounty_hunter.target_tag, dmg_mod=-1.0) MUST NOT trigger Riposte!
        List<CombatEvent> evTag = eng.step(Action.skill(bh.uid, "target_tag", List.of(hwm.uid)));
        check(evTag.stream().noneMatch(e -> "RIPOSTE".equals(e.type)), "target_tag (0 dmg) must NOT trigger Riposte");

        // 2. Non-damaging AoE skill (man_at_arms.bellow, dmg_mod=-1.0) MUST NOT trigger Riposte!
        eng.state.activeTeam = 0;
        List<String> allEn = eng.activeSlots(1).stream().map(u -> u.uid).toList();
        List<CombatEvent> evBellow = eng.step(Action.skill(maa.uid, "bellow", allEn));
        check(evBellow.stream().noneMatch(e -> "RIPOSTE".equals(e.type)), "bellow (0 dmg) must NOT trigger Riposte");

        // 3. Direct-damage skill (man_at_arms.crush, dmg_mod=0.0) MUST trigger Riposte!
        maa.actedThisRound = false;
        eng.state.activeTeam = 0;
        List<CombatEvent> evCrush = eng.step(Action.skill(maa.uid, "crush", List.of(hwm.uid)));
        check(evCrush.stream().anyMatch(e -> "RIPOSTE".equals(e.type)), "crush (damaging) MUST trigger Riposte");

        // 4. Negative CRIT debuff (smokescreen -20% CRIT) is NOT prematurely clamped to 0 before skill crit_mod!
        hwm.buffs.add(new ActiveBuff(StatKey.CRIT, 0, "crit", -20.0, 2, "smokescreen", true));
        check(eng.getEffectiveStats(hwm).get(StatKey.CRIT) < 0.0, "net CRIT reflects -20% debuff before skill crit_mod");
        List<Map<String, Object>> hwmBuffs = (List<Map<String, Object>>) eng.serializeUnit(hwm, true).get("buffs");
        check(hwmBuffs.stream().anyMatch(b -> Boolean.TRUE.equals(b.get("is_debuff")) && "crit".equals(b.get("stat"))),
                "debuff serialized in unit.buffs");

        // 5. Self-applied stealth + conditional buffs keep FULL 2 rounds after Shadow Fade cast turn!
        eng.state.activeTeam = 0;
        gr.rank = 2; // rank 1-2 required for shadow_fade
        eng.step(Action.skill(gr.uid, "shadow_fade", List.of(gr.uid)));
        checkEq(2, gr.stealthRounds, "shadow_fade stealth keeps full 2 rounds after cast turn");
    }

    private static void testDotNoDeathblow() {
        TeamConfig[] teams = sampleTeams();
        ArenaEngine eng = new ArenaEngine(teams[0], teams[1], 19L);
        int active = eng.state.activeTeam;
        UnitState u = eng.livingHeroes(active).get(0);
        u.hp = 0;
        u.atDeathsDoor = true;
        u.dots.add(new ActiveDot("bleed", 10, 3, "test"));
        u.dots.add(new ActiveDot("blight", 10, 3, "test"));
        List<CombatEvent> evs = eng.step(Action.pass(u.uid));
        check(u.alive && !u.isCorpse && u.hp == 0, "survived DoT at Death's Door");
        check(evs.stream().anyMatch(e -> "DOT_TICK".equals(e.type) && Boolean.TRUE.equals(e.data.get("no_deathblow"))), "no_deathblow event");
        check(evs.stream().noneMatch(e -> "DEATHBLOW".equals(e.type)), "no DEATHBLOW");
    }

    private static void testHeartAttackRules() {
        TeamConfig[] teams = sampleTeams();
        ArenaEngine eng = new ArenaEngine(teams[0], teams[1], 23L);
        UnitState u = eng.livingHeroes(0).get(0);
        List<CombatEvent> evs = new ArrayList<>();
        u.stress = 195;
        u.traitId = "focused";
        u.isVirtuous = true;
        eng.applyStress(u, 20, evs, "test");
        check(u.alive && !u.isCorpse && u.hp == 0 && u.atDeathsDoor, "HA drops to DD");
        checkEq(170, u.stress, "stress reset to 170");
        check(u.isVirtuous, "keeps virtue");

        List<CombatEvent> evs2 = new ArrayList<>();
        u.stress = 195;
        eng.applyStress(u, 20, evs2, "test");
        check(evs2.stream().anyMatch(e -> "DEATHBLOW".equals(e.type) && "heart_attack".equals(e.data.get("cause"))), "HA instant death");
        check(!u.alive, "no corpse on HA");
    }

    private static void testFlagellantAndAbom() {
        TeamConfig team = new TeamConfig("Special", List.of(
                new HeroLoadout("flagellant", List.of("punish", "rain_of_sorrows", "exsanguinate", "redeem"), List.of(), "A"),
                new HeroLoadout("abomination", List.of(), List.of(), "A"),
                new HeroLoadout("vestal", List.of("judgement", "dazzling_light", "divine_grace", "gods_comfort"), List.of(), "A"),
                new HeroLoadout("plague_doctor", List.of("plague_grenade", "blinding_gas", "battlefield_medicine", "disorienting_blast"), List.of(), "A")
        ));
        ArenaEngine eng = new ArenaEngine(team, team, 55L);
        UnitState flag = eng.livingHeroes(0).get(0);
        eng.applyStress(flag, 120, new ArrayList<>(), "test");
        checkEq("rapturous", flag.traitId, "flagellant rapturous");

        eng.state.activeTeam = 0;
        UnitState abom = eng.livingHeroes(0).get(1);
        check(!abom.isBeast, "starts human");
        eng.step(Action.skill(abom.uid, "transform", List.of(abom.uid)));
        check(abom.isBeast, "transformed to beast");
    }

    private static void testCloneAndSearchBot() {
        TeamConfig[] teams = sampleTeams();
        ArenaEngine e1 = new ArenaEngine(teams[0], teams[1], 777L);
        ArenaEngine e2 = e1.cloneEngine();
        Action act = e1.getLegalActions().get(0);
        List<CombatEvent> ev1 = e1.step(act);
        List<CombatEvent> ev2 = e2.step(act);
        checkEq(Json.stringify(ev1.stream().map(CombatEvent::toMap).toList()),
                Json.stringify(ev2.stream().map(CombatEvent::toMap).toList()), "clone determinism");

        CircusSearchBot bot = new CircusSearchBot(0.10);
        Observation obs = e1.getObservation(e1.state.activeTeam);
        long ts = System.nanoTime();
        Action chosen = bot.chooseAction(obs, e1);
        double elapsedSec = (System.nanoTime() - ts) / 1e9;
        check(obs.legalActions.contains(chosen), "chosen is legal");
        check(elapsedSec < 0.25, "strictly under budget");

        // Kill > Death's Door priority
        int oppTeam = 1 - e1.state.activeTeam;
        UnitState ddEnemy = e1.livingHeroes(oppTeam).get(0);
        ddEnemy.hp = 0;
        ddEnemy.atDeathsDoor = true;
        Observation obsKill = e1.getObservation(e1.state.activeTeam);
        Action killAct = bot.chooseAction(obsKill, e1);
        checkEq("SKILL", killAct.kind, "uses skill to kill DD enemy");
        check(killAct.targetUids.contains(ddEnemy.uid), "targets DD enemy");

        Simulator.MatchResult res = Simulator.runMatch(
                new CircusSearchBot(0.015), new RandomBot("Rand", 2L, null), null, null, 88L, 40, false
        );
        checkEq(0, res.winner(), "SearchBot beats RandomBot");
        check(res.rounds() > 0, "positive rounds");
    }

    private static void testMetaTeamsAndValidation() {
        GameData gd = GameData.get();
        Map<String, PresetDef> map = new HashMap<>();
        for (PresetDef p : gd.getPresets()) map.put(p.id, p);
        for (String pid : List.of("meta_direct_kill", "meta_stun_kill", "meta_iron_wall", "meta_iron_hound", "meta_shuffle_kill")) {
            PresetDef p = map.get(pid);
            check(p != null, "missing meta preset " + pid);
            checkEq(4, p.team.heroes.size(), "4 heroes");
            Set<String> ids = new HashSet<>();
            for (HeroLoadout h : p.team.heroes) {
                ids.add(h.heroId);
                checkEq(2, h.trinkets.size(), "2 trinkets");
                int expSk = "abomination".equals(h.heroId) ? 7 : 4;
                checkEq(expSk, h.skills.size(), "skill count for " + h.heroId);
            }
            checkEq(4, ids.size(), "no duplicate classes");
        }
        TeamConfig dup = new TeamConfig("Dup", List.of(
                new HeroLoadout("crusader", List.of("smite"), List.of(), "A"),
                new HeroLoadout("crusader", List.of("smite"), List.of(), "B")
        ));
        boolean threw = false;
        try {
            new ArenaEngine(dup, map.get("meta_direct_kill").team, 1L);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "duplicate hero rejected");
    }

    private static void testAssetsSpineFmodLoc() throws Exception {
        AssetResolver res = AssetResolver.get();
        for (String hid : GameData.get().heroOrder) {
            var info = res.resolveHeroAnim(hid, "combat", "A");
            check(info != null, "hero combat anim exists: " + hid);
            var parsed = SpineConverter.parseSpine21Skel(Files.readAllBytes(info.skel()));
            check(!((List<?>) parsed.get("bones")).isEmpty(), "parsed bones for " + hid);
        }
        for (String fx : List.of("coinflip", "arena_torch")) {
            var info = res.resolveGlobalFx(fx);
            check(info != null, "global fx exists: " + fx);
        }

        // Test COLD FMOD_OPENMEMORY decoding into an isolated temp cache directory!
        Path tmpAudioCache = Files.createTempDirectory("circus_fmod_test_");
        try {
            FmodAudioExtractor coldAe = new FmodAudioExtractor(Config.FMOD_DLL_PATH, Config.DD_GAME_DIR, Config.DD_DLC_DIR, tmpAudioCache);
            try {
                long t0 = System.nanoTime();
                byte[] wav0 = coldAe.extractWavBytes("dlc_thepit_sfx", "0");
                double firstMs = (System.nanoTime() - t0) / 1_000_000.0;
                check(wav0 != null && wav0.length > 44 && wav0[0] == 'R' && wav0[1] == 'I' && wav0[2] == 'F' && wav0[3] == 'F',
                        "FMOD_OPENMEMORY decoded RIFF WAV");

                // Extract 5 more distinct samples from same bank (tests LRU open-bank cache!)
                long t1 = System.nanoTime();
                for (int i = 1; i <= 5; i++) {
                    byte[] w = coldAe.extractWavBytes("dlc_thepit_sfx", String.valueOf(i));
                    check(w != null && w.length > 44 && w[0] == 'R', "FMOD sample " + i);
                }
                double next5Ms = (System.nanoTime() - t1) / 1_000_000.0;
                System.out.printf("         FMOD cold bank+sample0: %.1f ms | next 5 samples (LRU bank pool): %.1f ms (%.2f ms/sample)%n",
                        firstMs, next5Ms, next5Ms / 5.0);

                // Verify FMOD native WAVWRITER_NRT multi-stem mixer via FFM + FMOD_OPENMEMORY
                long tMix = System.nanoTime();
                Path mixed = coldAe.extractMixedMusicPath("circus_town");
                double mixMs = (System.nanoTime() - tMix) / 1_000_000.0;
                check(mixed != null && Files.isRegularFile(mixed) && Files.size(mixed) > 100_000, "FMOD WAVWRITER_NRT mixed music WAV");
                byte[] hdr = new byte[4];
                try (var is = Files.newInputStream(mixed)) { is.read(hdr); }
                checkEq("RIFF", new String(hdr), "mixed WAV starts with RIFF");
                System.out.printf("         FMOD WAVWRITER_NRT multi-stem mix (circus_town): %.1f ms (%.2f MB)%n",
                        mixMs, Files.size(mixed) / (1024.0 * 1024.0));
            } finally {
                coldAe.close();
            }
        } finally {
            deleteRecursively(tmpAudioCache);
        }

        LocalizationStore loc = LocalizationStore.get();
        checkEq("十字军", loc.heroName("crusader", "zh"), "zh hero name");
        checkEq("Crusader", loc.heroName("crusader", "en"), "en hero name");
    }

    private static void testTeamJsonAndBotClasses() {
        var entries = TeamConfigStore.reload();
        check(entries.size() >= 10, "loaded >= 10 team/*.json files");

        // Multiple teams sharing the exact same bot_class (CircusSearchBot) with distinct lineups!
        try (Bot b1 = BotRegistry.create("direct_kill", null);
             Bot b2 = BotRegistry.create("stun_kill", null);
             Bot custom = BotRegistry.create("bot.ExampleCustomBot", "CustomTest", "meta_direct_kill", 1L, null)) {
            checkEq("com.circus.bot.CircusSearchBot", b1.getClass().getName(), "direct_kill uses CircusSearchBot");
            checkEq("com.circus.bot.CircusSearchBot", b2.getClass().getName(), "stun_kill shares CircusSearchBot");
            check(!b1.chooseTeam(1).name.equals(b2.chooseTeam(1).name), "distinct teams from team/*.json");
            checkEq("bot.ExampleCustomBot", custom.getClass().getName(), "loaded custom bot from bot/ folder");
        }
    }

    @SuppressWarnings("unchecked")
    private static void testTournamentReportAndApis() throws Exception {
        Path tmpTourDir = Files.createTempDirectory("circus_tour_test_");
        try {
            var report = Simulator.runTournament(
                    List.of(new Simulator.BotSpec("direct_kill", null), new Simulator.BotSpec("stress_circus", null)),
                    4, 4, 123L, 40, 0, null, tmpTourDir, 0.015
            );
            checkEq(4, report.totalMatches(), "4 matches");
            check(!report.reportFilePath().isEmpty(), "saved report file");
            Path rf = Path.of(report.reportFilePath());
            check(Files.isRegularFile(rf), "report JSON exists on disk");

            Map<String, Object> parsed = Json.parseObject(Files.readString(rf));
            List<Object> matches = Json.listVal(parsed, "matches");
            checkEq(4, matches.size(), "matches array has 4 entries");
            Map<String, Object> m0 = (Map<String, Object>) matches.get(0);
            for (String key : List.of(
                    "rounds", "winner", "winner_name", "team0_name", "team1_name",
                    "first_death_round", "team0_total_damage", "team1_total_damage",
                    "team0_total_stress", "team1_total_stress",
                    "first_deaths_door_round", "first_affliction_round", "first_heart_attack_round"
            )) {
                check(m0.containsKey(key), "match JSON contains required key: " + key);
            }
            check(Json.intVal(m0, "team0_total_damage", 0) + Json.intVal(m0, "team1_total_damage", 0) > 0, "positive total damage recorded");
        } finally {
            deleteRecursively(tmpTourDir);
        }

        // Verify /api/teams, /api/tournaments, and /static/tournament_report.html endpoints
        ArenaServer server = new ArenaServer("127.0.0.1", 0);
        int port = server.start();
        String base = "http://127.0.0.1:" + port;
        HttpClient client = HttpClient.newHttpClient();
        try {
            var rTeams = client.send(HttpRequest.newBuilder(URI.create(base + "/api/teams")).GET().build(), HttpResponse.BodyHandlers.ofString());
            checkEq(200, rTeams.statusCode(), "/api/teams 200");
            check(Json.listVal(Json.parseObject(rTeams.body()), "teams").size() >= 10, "/api/teams returns >= 10 teams");

            var rHtml = client.send(HttpRequest.newBuilder(URI.create(base + "/static/tournament_report.html")).GET().build(), HttpResponse.BodyHandlers.ofString());
            checkEq(200, rHtml.statusCode(), "tournament_report.html 200");
            check(rHtml.body().contains("队伍深度对比"), "tournament_report.html content verified");
        } finally {
            server.stop();
        }
    }

    @SuppressWarnings("unchecked")
    private static void testHttpAndDualWs() throws Exception {
        ArenaServer server = new ArenaServer("127.0.0.1", 0);
        int port = server.start();
        String base = "http://127.0.0.1:" + port;
        HttpClient client = HttpClient.newHttpClient();
        try {
            // 1. Bootstrap
            var rBoot = client.send(HttpRequest.newBuilder(URI.create(base + "/api/bootstrap")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            checkEq(200, rBoot.statusCode(), "bootstrap 200");
            Map<String, Object> boot = Json.parseObject(rBoot.body());
            checkEq(18, Json.listVal(boot, "hero_order").size(), "bootstrap 18 heroes");
            checkEq(103, Json.listVal(boot, "trinkets").size(), "bootstrap 103 trinkets");

            // 2. Spine JSON
            var rSpine = client.send(HttpRequest.newBuilder(URI.create(base + "/api/spine/hero/crusader/combat")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            checkEq(200, rSpine.statusCode(), "spine 200");
            check(Json.parseObject(rSpine.body()).containsKey("bones"), "spine has bones");

            // 3. FMOD Audio + HTTP Range 206
            var rWav = client.send(HttpRequest.newBuilder(URI.create(base + "/api/audio/dlc_thepit_sfx/0")).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            checkEq(200, rWav.statusCode(), "audio 200");
            check(rWav.body().length > 44 && rWav.body()[0] == 'R', "RIFF wav");

            var rPart = client.send(HttpRequest.newBuilder(URI.create(base + "/api/audio/dlc_thepit_sfx/0"))
                    .header("Range", "bytes=0-3").GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            checkEq(206, rPart.statusCode(), "audio Range 206");
            checkEq("RIFF", new String(rPart.body()), "Range bytes=0-3 == RIFF");

            // 4. Create PvP Room & Dual WebSocket match
            var rRoom = client.send(HttpRequest.newBuilder(URI.create(base + "/api/rooms"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"mode\":\"pvp\",\"arena_tier\":\"darkest\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            checkEq(200, rRoom.statusCode(), "create room 200");
            String rid = Json.str(Json.parseObject(rRoom.body()), "room_id", "");

            BlockingQueue<Map<String, Object>> q0 = new LinkedBlockingQueue<>();
            BlockingQueue<Map<String, Object>> q1 = new LinkedBlockingQueue<>();
            WebSocket ws0 = client.newWebSocketBuilder().buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws/room/" + rid + "?seat=0"),
                    new QueueWsListener(q0)).get(5, TimeUnit.SECONDS);
            WebSocket ws1 = client.newWebSocketBuilder().buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws/room/" + rid + "?seat=1"),
                    new QueueWsListener(q1)).get(5, TimeUnit.SECONDS);

            Map<String, Object> m0 = q0.poll(5, TimeUnit.SECONDS);
            check(m0 != null && "ROOM_STATE".equals(m0.get("type")), "ws0 got ROOM_STATE");

            ws0.sendText("{\"type\":\"SET_READY\",\"ready\":true}", true).join();
            ws1.sendText("{\"type\":\"SET_READY\",\"ready\":true}", true).join();

            Map<String, Object> obs0 = waitForType(q0, "COMBAT_UPDATE");
            Map<String, Object> obs1 = waitForType(q1, "COMBAT_UPDATE");
            Map<String, Object> o0 = (Map<String, Object>) obs0.get("observation");
            Map<String, Object> o1 = (Map<String, Object>) obs1.get("observation");
            boolean turn0 = Json.boolVal(o0, "is_my_turn", false);
            boolean turn1 = Json.boolVal(o1, "is_my_turn", false);
            check(turn0 != turn1, "perspective flip: exactly 1 player active");

            WebSocket activeWs = turn0 ? ws0 : ws1;
            Map<String, Object> activeObs = turn0 ? o0 : o1;
            Object firstAct = Json.listVal(activeObs, "legal_actions").get(0);
            activeWs.sendText(Json.stringify(Map.of("type", "ACTION", "action", firstAct)), true).join();

            Map<String, Object> u0 = waitForType(q0, "COMBAT_UPDATE");
            Map<String, Object> u1 = waitForType(q1, "COMBAT_UPDATE");
            boolean next0 = Json.boolVal((Map<String, Object>) u0.get("observation"), "is_my_turn", false);
            boolean next1 = Json.boolVal((Map<String, Object>) u1.get("observation"), "is_my_turn", false);
            checkEq(!turn0, next0, "turn alternated for P0");
            checkEq(!turn1, next1, "turn alternated for P1");

            ws0.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
            ws1.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        } finally {
            server.stop();
        }
    }

    private static Map<String, Object> waitForType(BlockingQueue<Map<String, Object>> q, String type) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < end) {
            Map<String, Object> m = q.poll(200, TimeUnit.MILLISECONDS);
            if (m != null && type.equals(m.get("type"))) return m;
        }
        throw new AssertionError("Timed out waiting for WS message type " + type);
    }

    private static final class QueueWsListener implements WebSocket.Listener {
        private final BlockingQueue<Map<String, Object>> queue;
        private final StringBuilder buf = new StringBuilder();

        QueueWsListener(BlockingQueue<Map<String, Object>> queue) { this.queue = queue; }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buf.append(data);
            if (last) {
                queue.offer(Json.parseObject(buf.toString()));
                buf.setLength(0);
            }
            webSocket.request(1);
            return null;
        }
    }

    private static void runBenchmarks() {
        System.out.println("\n=== Java 26 Engine & SearchBot Performance Benchmarks ===");
        TeamConfig[] teams = sampleTeams();
        ArenaEngine eng = new ArenaEngine(teams[0], teams[1], 42L);
        Action act = eng.getLegalActions().get(0);

        // Warmup JIT
        for (int i = 0; i < 3000; i++) {
            ArenaEngine c = eng.cloneForSearch();
            c.step(act);
        }

        // 1. cloneForSearch + step benchmark
        int N = 50_000;
        long t0 = System.nanoTime();
        for (int i = 0; i < N; i++) {
            ArenaEngine c = eng.cloneForSearch();
            c.step(act);
        }
        double dtSec = (System.nanoTime() - t0) / 1e9;
        System.out.printf("Java cloneForSearch+step: %,.0f ops/sec (%.4f ms/op)%n", N / dtSec, (dtSec / N) * 1000.0);

        // 2. CircusSearchBot depth & nodes under tight budgets
        Observation obs = eng.getObservation(eng.state.activeTeam);
        // Warmup search JIT
        new CircusSearchBot(0.05).chooseAction(obs, eng);

        for (double budget : new double[]{0.02, 0.05, 0.10, 0.20}) {
            CircusSearchBot bot = new CircusSearchBot("BenchBot", budget, "balanced", 1L, null);
            Action chosen = bot.chooseAction(obs, eng);
            System.out.printf("Java SearchBot budget=%3.0fms -> elapsed=%5.1fms | depth=%d | nodes=%6d | ttHits=%5d | action=%s%n",
                    budget * 1000.0, bot.getLastElapsedMs(), bot.getLastDepthReached(),
                    bot.getLastNodesVisited(), bot.getLastTtHits(), chosen);
        }

        // 3. Multi-threaded tournament throughput (Random vs Random baseline)
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        var rep = Simulator.runTournament(
                List.of(new Simulator.BotSpec("random", "meta_direct_kill"), new Simulator.BotSpec("random", "meta_stun_kill")),
                100, cores, 42L, 40, 0, null, null
        );
        System.out.printf("Java Multi-Threaded Tournament (%d threads): %d matches in %.2fs (%.1f matches/sec)%n",
                cores, rep.totalMatches(), rep.elapsedSec(), rep.matchesPerSec());
    }

    private static void deleteRecursively(Path dir) {
        try (var s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }
}
