package com.circus.bot;

import com.circus.engine.GameData;
import java.util.*;

/** Replay the exact generation-zero lineup sampler without rerunning battles. */
public final class DeepSetsCoverage {
    public static void main(String[] args) {
        int games = Integer.parseInt(args[0]);
        var heroes = new TreeSet<String>();
        var skills = new TreeSet<String>();
        var items = new TreeSet<String>();
        var seeds = new SplittableRandom(819012L);
        for (int i = 0; i < games; i++) {
            var players = DeepSetsGames.matchup(new Random(seeds.nextLong()), i, false, .04);
            for (var player : players) for (var u : player.team().heroes) {
                heroes.add(u.heroId);
                for (var skill : u.skills) skills.add(u.heroId + "/" + skill);
                items.addAll(u.trinkets);
            }
        }
        var data = GameData.get();
        var missing = new TreeSet<String>();
        for (var h : data.heroes.values()) for (var skill : h.skills)
            if (!skills.contains(h.id + "/" + skill.id)) missing.add(h.id + "/" + skill.id);
        System.out.printf("games=%d heroes=%d/%d equipped_skills=%d missing_skills=%s trinkets=%d/%d%n",
                games, heroes.size(), data.heroes.size(), skills.size(), missing, items.size(), data.trinkets.size());
    }
}
