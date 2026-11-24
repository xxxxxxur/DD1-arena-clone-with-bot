package com.circus.bot;

import com.circus.engine.*;
import com.circus.engine.Models.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Resumable per-game shards. The validation split uses fixed preset pairings and game seeds. */
public final class DeepSetsGames {
    static final List<TeamConfigStore.TeamEntry> teams = TeamConfigStore.list();
    static final List<String> profiles = new ArrayList<>(new TreeSet<>(CircusSearchBot.PROFILES.keySet()));
    record Sample(DeepSetsPosition position, double teacher) {}
    record Player(TeamConfig team, BotContext context) {}

    static Player player(Random rng, int index, boolean random, boolean variant, double budget) {
        var entry = teams.get(index);
        var team = entry.team();
        var options = entry.botOptions();
        if (random) {
            team = NeuralTrainer.randomLineup(rng);
            options = Map.of("profile", profiles.get(rng.nextInt(profiles.size())));
        } else if (variant) {
            var members = new ArrayList<>(team.heroes);
            int slot = rng.nextInt(members.size());
            var member = members.get(slot);
            var skills = new ArrayList<>(member.skills);
            var alternatives = new ArrayList<String>();
            for (var s : GameData.get().heroes.get(member.heroId).skills)
                if (!skills.contains(s.id)) alternatives.add(s.id);
            if (!alternatives.isEmpty() && !skills.isEmpty())
                skills.set(rng.nextInt(skills.size()), alternatives.get(rng.nextInt(alternatives.size())));
            members.set(slot, new HeroLoadout(member.heroId, skills, member.trinkets, member.variant));
            team = new TeamConfig("variant", members);
        }
        return new Player(team, new BotContext(entry.id(), entry.name(), null, team, options, rng.nextLong(), budget));
    }

    static Player[] matchup(Random rng, int id, boolean validation, double budget) {
        boolean random = !validation && id % 10 >= 7;
        int first = validation ? id % teams.size() : rng.nextInt(teams.size());
        var left = player(rng, first, random, !validation && id % 10 == 5, budget);
        var right = !random && id % 4 == 0 ? left :
                player(rng, rng.nextInt(teams.size()), random, !validation && id % 10 == 6, budget);
        return new Player[]{left, right};
    }

    static void game(Path file, int id, int generation, boolean validation, long seed, double budget) throws IOException {
        var rng = new Random(seed);
        var players = matchup(rng, id, validation, budget);
        var engine = new ArenaEngine(players[0].team, players[1].team, seed).cloneForSearch();
        CircusSearchBot[] bots = new CircusSearchBot[2];
        for (int side = 0; side < 2; side++) {
            var ctx = players[side].context;
            bots[side] = generation > 0 && !validation && rng.nextBoolean()
                    ? (CircusSearchBot)BotRegistry.instantiateBotClass("deepsets_neural", ctx) : new CircusSearchBot(ctx);
        }
        var samples = new ArrayList<Sample>();
        int offset = rng.nextInt(5);
        try (var leftBot = bots[0]; var rightBot = bots[1]) {
            while (!engine.state.finished) {
                int side = engine.state.activeTeam;
                var bot = bots[side];
                var action = bot.chooseAction(engine.getActionObservation(side), engine);
                if (engine.state.turnCount % 5 == offset) {
                    var pos = new DeepSetsPosition();
                    pos.encode(engine);
                    double score = bot.lastSearchScore();
                    double probability = Double.isFinite(score) && score > -1e10
                            ? 1 / (1 + Math.exp(-(side == 0 ? score : -score) / ArenaValue.SCALE)) : Double.NaN;
                    samples.add(new Sample(pos, probability));
                }
                engine.step(action);
            }
        }
        double outcome = engine.state.winner == null ? .5 : engine.state.winner == 0 ? 1 : 0;
        var temp = Files.createTempFile(file.getParent(), "game-", ".tmp");
        try (var out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))) {
            for (var sample : samples) {
                out.writeFloat((float)(Double.isFinite(sample.teacher) ? .7 * outcome + .3 * sample.teacher : outcome));
                for (var row : sample.position.row) for (float value : row) out.writeFloat(value);
                for (float value : sample.position.global) out.writeFloat(value);
            }
        }
        Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE);
    }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createDirectories(Path.of(args[0]));
        int count = Integer.parseInt(args[1]), workers = Integer.parseInt(args[2]), generation = Integer.parseInt(args[3]);
        boolean validation = args[4].equals("valid");
        var seeds = new SplittableRandom(validation ? 42703 : 819012L + generation);
        double budget = args.length > 5 ? Double.parseDouble(args[5]) : .04;
        if (count < 1 || workers < 1 || generation < 0 || !Double.isFinite(budget) || budget <= 0)
            throw new IllegalArgumentException("Invalid game collection parameters");
        long start = System.nanoTime();
        try (var pool = Executors.newFixedThreadPool(workers)) {
            var tasks = new ExecutorCompletionService<Void>(pool);
            for (int i = 0; i < count; i++) {
                int id = i;
                long seed = seeds.nextLong();
                tasks.submit(() -> {
                    var file = dir.resolve(id + ".dat");
                    if (!Files.exists(file)) {
                        game(file, id, generation, validation, seed, budget);
                    } else if (Files.size(file) % ((1 + 8 * DeepSetsPosition.D + DeepSetsPosition.G) * Float.BYTES) != 0) {
                        throw new IOException("Incomplete training shard: " + file);
                    }
                    return null;
                });
            }
            for (int i = 1; i <= count; i++) {
                tasks.take().get();
                if (i % 100 == 0 || i == count) System.out.printf("games=%d/%d elapsed_s=%.1f%n", i, count, (System.nanoTime()-start)*1e-9);
            }
        }
    }
}
