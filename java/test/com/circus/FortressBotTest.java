package com.circus;

import com.circus.bot.*;
import com.circus.engine.*;
import com.circus.engine.Models.*;
import java.lang.reflect.Method;
import java.util.*;

/** Regression checks for long-horizon defense valuation and loadout integrity. */
public final class FortressBotTest {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        TeamConfigStore.reload();
        Method hash = FortressBot.class.getDeclaredMethod("searchStateHash", ArenaEngine.class);
        hash.setAccessible(true);
        for (String id : List.of("iron_wall", "iron_hound")) {
            var entry = TeamConfigStore.get(id);
            var options = new HashMap<>(entry.botOptions()); options.put("max_depth", 2);
            FortressBot bot = new FortressBot(new BotContext(id, id, null, entry.team(), options, 42L, .46));
            TeamConfig team = bot.chooseTeam(42);
            check(team.heroes.size() == 4 && ArenaEngine.validateTeamConfig(team) == null, "Invalid team");
            for (HeroLoadout loadout : team.heroes) {
                HeroDef hero = GameData.get().heroes.get(loadout.heroId);
                check(loadout.skills.size() == 4 && new HashSet<>(loadout.skills).size() == 4, "Invalid skill count");
                for (String skill : loadout.skills) check(hero.getSkill(skill) != null, "Unknown equipped skill");
                check(loadout.trinkets.size() == 2 && new HashSet<>(loadout.trinkets).size() == 2, "Invalid trinket count");
                for (String trinket : loadout.trinkets) {
                    TrinketDef t = GameData.get().trinkets.get(trinket);
                    check(t != null && (t.heroClass.isEmpty() || t.heroClass.equals(hero.id)), "Illegal trinket");
                }
            }
            ArenaEngine engine = new ArenaEngine(team, GameData.get().basePresets.get(0).team, 42L);
            engine.state.activeTeam = 0;
            double before = bot.evaluateState(engine, 0);
            ArenaEngine fortified = engine.cloneForSearch();
            for (UnitState u : fortified.state.teams[0]) {
                u.buffs.add(new ActiveBuff("dodge", 10, -1, "bolster", false));
                u.buffs.add(new ActiveBuff("stress_res", 15, -1, "bolster", false));
            }
            check(bot.evaluateState(fortified, 0) > before + 100, "Permanent party defense is invisible");
            ArenaEngine weakened = engine.cloneForSearch();
            for (UnitState u : weakened.state.teams[1]) u.buffs.add(new ActiveBuff("dmg_pct", -.2, 3, "bellow", true));
            check(bot.evaluateState(weakened, 0) > before, "Enemy damage suppression is invisible");
            ArenaEngine armored = engine.cloneForSearch();
            armored.state.teams[0].get(0).buffs.add(new ActiveBuff("prot", .2, 3, "defender", false));
            ArenaEngine agile = engine.cloneForSearch();
            agile.state.teams[0].get(0).buffs.add(new ActiveBuff("dodge", 20, 3, "guard_dog", false));
            check(!hash.invoke(bot, armored).equals(hash.invoke(bot, agile)), "Different defense buffs alias in TT");
            var snapshot = engine.getObservation(0).toMap();
            Action chosen = bot.chooseAction(engine.getActionObservation(0), engine);
            check(engine.getLegalActions().contains(chosen), "Search returned illegal action");
            check(bot.getLastDepthReached() == 2, "Fixed-depth search incomplete");
            check(snapshot.equals(engine.getObservation(0).toMap()), "Search mutated live engine");
            // Emergency rescue must take precedence over long-term setup.
            UnitState endangered = engine.state.teams[0].get(3);
            endangered.hp = 0; endangered.atDeathsDoor = true;
            Action rescue = bot.chooseAction(engine.getActionObservation(0), engine);
            check(!List.of("bolster", "bulwark_of_faith", "toxin_trickery").contains(rescue.skillId),
                    "Opening setup displaced emergency rescue: " + rescue);
            System.out.println("PASS " + id + ": legal loadout, long-horizon defense/debuffs, TT distinction, non-mutating search and rescue");
        }
    }
}
