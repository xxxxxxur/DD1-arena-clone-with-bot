package com.circus;

import com.circus.bot.*;
import com.circus.engine.*;
import com.circus.engine.Models.*;
import java.lang.reflect.Method;
import java.util.*;

/** Behavior checks for allocation optimizations, independent of local game/audio assets. */
public final class TournamentPerformanceTest {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    private static List<Action> referenceActions(ArenaEngine engine, Method groups) throws Exception {
        int team = engine.state.activeTeam;
        List<Action> result = new ArrayList<>();
        if (engine.state.finished) return result;
        for (UnitState actor : engine.getSelectableActors(team)) {
            if (actor.stunned) {
                result.add(Action.pass(actor.uid));
                continue;
            }
            for (SkillDef skill : actor.equippedSkillDefs) {
                if (skill == null || !engine.canUseSkill(actor, skill)) continue;
                @SuppressWarnings("unchecked")
                var targets = (List<List<String>>) groups.invoke(engine, actor, skill, 1 - team);
                for (var target : targets) result.add(Action.skill(actor.uid, skill.id, target));
            }
            for (int d = 1; d <= actor.heroDef.moveForward; d++)
                if (actor.rank - d >= 1) result.add(Action.move(actor.uid, d));
            for (int d = 1; d <= actor.heroDef.moveBackward; d++)
                if (actor.rank + d <= engine.countActiveSlots(team)) result.add(Action.move(actor.uid, -d));
            result.add(Action.pass(actor.uid));
        }
        return result;
    }

    private static Map<String, Object> matchMap(Simulator.MatchResult result) {
        var map = result.toMatchMap(0);
        map.remove("elapsed_ms");
        map.remove("team0_bot_class");
        return map;
    }

    public static void main(String[] args) throws Exception {
        Method groups = ArenaEngine.class.getDeclaredMethod("getValidTargetGroups", UnitState.class, SkillDef.class, int.class);
        groups.setAccessible(true);
        var presets = GameData.get().getPresets();
        for (PresetDef preset : GameData.get().basePresets) {
            var bot = new RandomBot("preset", 1L, preset.id);
            TeamConfig selected = bot.chooseTeam(42);
            check(selected.toMap().equals(preset.team.toMap()), "Named preset selection changed");
            selected.heroes.clear();
            check(bot.chooseTeam(42).heroes.size() == preset.team.heroes.size(), "Preset copy is not isolated");
        }
        CircusSearchBot scorer = new CircusSearchBot("scorer", .46, "balanced", 1L, null, 2, 8, 4);
        Method ordered = CircusSearchBot.class.getDeclaredMethod("orderedCandidates", ArenaEngine.class,
                int.class, int.class, Action.class, int.class);
        ordered.setAccessible(true);
        int turns = 0;
        var scratch = new ArenaEngine.SearchScratch();
        for (int seed = 0; seed < 60; seed++) {
            ArenaEngine engine = new ArenaEngine(presets.get(seed % presets.size()).team,
                    presets.get((seed + 1) % presets.size()).team, (long) seed, 15);
            RandomBot fullBot = new RandomBot("full", (long) seed, null);
            RandomBot leanBot = new RandomBot("lean", (long) seed, null);
            while (!engine.state.finished) {
                int team = engine.state.activeTeam;
                Observation full = engine.getObservation(team);
                Observation lean = engine.getActionObservation(team);
                check(full.legalActions.equals(referenceActions(engine, groups)), "Legal action order changed");
                check(full.legalActions.equals(lean.legalActions), "Lightweight actions differ");
                if (turns % 100 == 0) {
                    List<?> all = (List<?>) ordered.invoke(scorer, engine, team, Integer.MAX_VALUE, null, 0);
                    List<?> top = (List<?>) ordered.invoke(scorer, engine, team, 4, null, 0);
                    check(top.equals(all.subList(0, Math.min(4, all.size()))), "Top-K ordering changed");
                    Action fullChoice = scorer.chooseAction(full, engine);
                    check(scorer.getLastDepthReached() == 2, "Fixed-depth search incomplete");
                    check(fullChoice.equals(scorer.chooseAction(lean, engine)), "Lightweight search choice differs");
                }
                for (Action a : full.legalActions) {
                    check(a.hashCode() == Objects.hash(a.kind, a.actorUid, a.skillId, a.targetUids, a.moveDelta), "Action hash changed");
                }
                Action action = fullBot.chooseAction(full, engine);
                check(action.equals(leanBot.chooseAction(lean, engine)), "Random RNG/action order changed");
                var before = engine.getObservation(0).toMap();
                ArenaEngine silent = engine.cloneForSearch();
                ArenaEngine shared = engine.cloneForSearch(scratch);
                ArenaEngine recorded = engine.cloneEngine();
                silent.step(action);
                shared.step(action);
                var expectedEvents = recorded.step(action).stream().map(CombatEvent::toMap).toList();
                check(before.equals(engine.getObservation(0).toMap()), "Clone mutated parent");
                var events = engine.step(action).stream().map(CombatEvent::toMap).toList();
                check(events.equals(expectedEvents), "Clone/replay events differ");
                for (int viewer = 0; viewer < 2; viewer++) {
                    var expected = engine.getObservation(viewer).toMap();
                    check(expected.equals(silent.getObservation(viewer).toMap()), "Silent clone state differs");
                    check(expected.equals(shared.getObservation(viewer).toMap()), "Shared scratch state differs");
                }
                check(engine.rng.copy().nextLong() == shared.rng.copy().nextLong(), "Clone RNG changed");
                turns++;
            }
        }

        // A custom bot retains the full observation contract; replay recording does not change analytics.
        for (int seed = 0; seed < 10; seed++) {
            RandomBot delegate = new RandomBot("a", (long) seed, null);
            Bot custom = new AbstractBot("a", null) {
                @Override public Action chooseAction(Observation obs, ArenaEngine engine) {
                    check(!obs.myUnits.isEmpty() && !obs.enemyUnits.isEmpty(), "Custom bot lost unit snapshots");
                    return delegate.chooseAction(obs, engine);
                }
            };
            var a = Simulator.runMatch(custom, new RandomBot("b", 99L, null),
                    presets.get(0).team, presets.get(1).team, seed, 15, true);
            var b = Simulator.runMatch(new RandomBot("a", (long) seed, null), new RandomBot("b", 99L, null),
                    presets.get(0).team, presets.get(1).team, seed, 15, false);
            check(matchMap(a).equals(matchMap(b)), "Replay/lightweight mode changed match analytics");
            check(a.replay() != null && b.replay() == null, "Replay mode ignored");
        }
        check(new CircusSearchBot(.01) {}.requiresUnitObservations(), "Custom search subclass lost snapshots");
        check(new TacticalBot("custom", 1L, null, .01) {}.requiresUnitObservations(), "Custom tactical subclass lost snapshots");
        var specs = List.of(new Simulator.BotSpec("random", "meta_direct_kill"),
                new Simulator.BotSpec("random", "meta_stun_kill"));
        var serial = Simulator.runTournament(specs, 80, 1, 42L, 15, 0, null, null, null);
        var parallel = Simulator.runTournament(specs, 80, 4, 42L, 15, 0, null, null, null);
        check(serial.winrates().equals(parallel.winrates()), "Parallel standings changed");
        check(serial.headToHead().equals(parallel.headToHead()), "Parallel head-to-head changed");
        for (int i = 0; i < serial.matches().size(); i++) {
            var a = new LinkedHashMap<>(serial.matches().get(i));
            var b = new LinkedHashMap<>(parallel.matches().get(i));
            a.remove("elapsed_ms"); b.remove("elapsed_ms");
            check(a.equals(b), "Parallel match order/analytics changed");
        }
        System.out.printf("PASS: %d turns across 60 matches; legal actions, hash/RNG, clones, custom bots and replay analytics%n", turns);
        System.out.println("PASS: Top-K/search choices; 80 serial/parallel matches with identical standings and analytics");
    }
}
