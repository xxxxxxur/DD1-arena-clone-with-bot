package com.circus;

import com.circus.bot.BotContext;
import com.circus.bot.CircusSearchBot;
import com.circus.bot.TeamConfigStore;
import com.circus.engine.ArenaEngine;
import com.circus.engine.Models;
import com.circus.engine.Models.*;

import java.util.*;

/**
 * Verifies Abomination Transform free-action & once-per-round rules in both ArenaEngine and CircusSearchBot.
 */
public final class AbominationTransformTest {
    private static void check(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
    }

    public static void main(String[] args) {
        TeamConfigStore.reload();
        TeamConfig stunKill = TeamConfigStore.get("stun_kill").team().copy();
        TeamConfig directKill = TeamConfigStore.get("direct_kill").team().copy();

        ArenaEngine engine = new ArenaEngine(stunKill, directKill, 42L);
        engine.state.activeTeam = 0;

        UnitState abom = null;
        for (UnitState u : engine.state.teams[0]) {
            if ("abomination".equals(u.heroId)) { abom = u; break; }
        }
        check(abom != null, "Abomination not found in stun_kill");
        check(!abom.isBeast, "Abomination should start in Human form");
        check(!abom.transformedThisRound, "transformedThisRound should start false");

        // Add a Bleed DoT to verify start-of-turn DoT ticks ONLY ONCE across Transform + follow-up action!
        abom.dots.add(new ActiveDot(Models.DOT_BLEED, 3, 2, "test_bleed"));
        int hpBefore = abom.hp;
        int turnBefore = engine.state.turnCount;

        // 1. Use Transform (Human -> Beast)
        Action transformAct = Action.skill(abom.uid, "transform", List.of(abom.uid));
        List<CombatEvent> ev1 = engine.step(transformAct);
        check(abom.isBeast, "Abomination should now be Beast");
        check(abom.transformedThisRound, "transformedThisRound should be true");
        check(!abom.actedThisRound, "Transform must NOT end Abomination's turn");
        check(engine.state.activeTeam == 0, "Active team must remain 0 after Transform");
        check(abom.uid.equals(engine.state.lockedActorUid), "lockedActorUid must lock to Abomination");
        check(engine.state.turnCount == turnBefore + 1, "turnCount should increment once on turn start");
        check(abom.hp == hpBefore - 3, "Bleed should tick once on Transform turn start (expected " + (hpBefore - 3) + ", got " + abom.hp + ")");

        // 2. Only Abomination is selectable, and Transform is NOT legal again this round
        List<UnitState> selectable = engine.getSelectableActors(0);
        check(selectable.size() == 1 && selectable.get(0) == abom, "Only Abomination should be selectable after Transform");
        List<Action> legalAfter = engine.getLegalActions(0);
        boolean hasTransformAgain = legalAfter.stream().anyMatch(a -> "SKILL".equals(a.kind) && "transform".equals(a.skillId));
        check(!hasTransformAgain, "Transform must be limited to once per round");
        boolean hasBeastSkill = legalAfter.stream().anyMatch(a -> "SKILL".equals(a.kind) && ("rake".equals(a.skillId) || "rage".equals(a.skillId) || "slam".equals(a.skillId)));
        check(hasBeastSkill, "Abomination should have Beast skills immediately available after Transform");

        // 3. Follow-up Beast action finishes turn WITHOUT ticking Bleed a second time
        Action beastAct = legalAfter.stream()
                .filter(a -> "SKILL".equals(a.kind) && ("rage".equals(a.skillId) || "rake".equals(a.skillId)))
                .findFirst().orElseThrow();
        int hpAfterTransform = abom.hp;
        int turnAfterTransform = engine.state.turnCount;
        engine.step(beastAct);
        check(abom.actedThisRound, "Follow-up action should end Abomination's turn");
        check(engine.state.lockedActorUid == null, "lockedActorUid should clear after follow-up action");
        check(engine.state.activeTeam == 1, "Turn should advance to opponent after follow-up action");
        check(abom.hp == hpAfterTransform, "Bleed must NOT tick a second time on follow-up action");
        check(engine.state.turnCount == turnAfterTransform, "turnCount must NOT double-increment on free-action follow-up");

        // 4. Verify Beast -> Human revert in next round is also a free action and preserves self-Daze
        for (UnitState u : engine.state.teams[0]) u.actedThisRound = false;
        abom.transformedThisRound = false;
        engine.state.activeTeam = 0;
        engine.state.roundNum++;
        engine.step(Action.skill(abom.uid, "transform", List.of(abom.uid)));
        check(!abom.isBeast, "Abomination should revert to Human");
        check(abom.dazed, "Reverting to Human should apply Daze");
        check(!abom.actedThisRound, "Reverting to Human must NOT end turn");
        check(abom.uid.equals(engine.state.lockedActorUid), "Reverting to Human must keep Abomination locked");
        List<UnitState> selRevert = engine.getSelectableActors(0);
        check(selRevert.size() == 1 && selRevert.get(0) == abom, "Locked Abomination must remain selectable even when Dazed");
        engine.step(Action.pass(abom.uid));
        check(abom.actedThisRound, "Follow-up pass should end turn");
        check(abom.dazed, "Self-Daze from reverting to Human must persist into next round");

        // 5. Verify CircusSearchBot at depth=1 understands Transform free-action follow-up
        ArenaEngine botEng = new ArenaEngine(stunKill, directKill, 99L);
        botEng.state.activeTeam = 0;
        // Mark other allies as acted so Abomination is the active hero
        for (UnitState u : botEng.state.teams[0]) {
            if (!"abomination".equals(u.heroId)) u.actedThisRound = true;
        }
        var entry = TeamConfigStore.get("stun_kill");
        Map<String, Object> opts = new HashMap<>(entry.botOptions());
        opts.put("max_depth", 1);
        CircusSearchBot bot = new CircusSearchBot(new BotContext("stun_kill", "stun_kill", null, stunKill, opts, 99L, 0.2));
        Action chosen = bot.chooseAction(botEng.getActionObservation(0), botEng);
        check(botEng.getLegalActions(0).contains(chosen), "Bot returned illegal action");
        if ("SKILL".equals(chosen.kind) && "transform".equals(chosen.skillId)) {
            botEng.step(chosen);
            Action followUp = bot.chooseAction(botEng.getActionObservation(0), botEng);
            check("SKILL".equals(followUp.kind) && !"transform".equals(followUp.skillId),
                    "After Transform, bot should immediately execute a Beast skill, got: " + followUp);
            System.out.println("Bot chose Transform -> immediate follow-up: " + followUp.skillId);
        } else {
            System.out.println("Bot chose action: " + chosen.skillId + " (legal)");
        }

        System.out.println("PASS: Abomination Transform free-action, once-per-round, DoT single-tick, Daze persistence, and Bot lookahead");
    }
}
