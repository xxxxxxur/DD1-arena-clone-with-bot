package com.circus.bot;

import com.circus.engine.*;
import com.circus.engine.Models.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

/** Generate games with search teachers, then fit a residual win logit to completed games. */
public final class NeuralTrainer {
    record Example(double[] x, double base, double result) {}
    static final List<TeamConfig> PRESETS = TeamConfigStore.list().stream().map(t -> t.team()).toList();

    static TeamConfig lineup(Random rng) {
        if (!PRESETS.isEmpty() && rng.nextInt(3) == 0) return PRESETS.get(rng.nextInt(PRESETS.size()));
        return randomLineup(rng);
    }

    static TeamConfig randomLineup(Random rng) {
        GameData data = GameData.get();
        var heroes = new ArrayList<>(data.heroOrder);
        Collections.shuffle(heroes, rng);
        var members = new ArrayList<HeroLoadout>();
        for (String id : heroes.subList(0, 4)) {
            var def = data.heroes.get(id);
            var skills = new ArrayList<String>();
            for (var s : def.skills) skills.add(s.id);
            Collections.shuffle(skills, rng);
            var trinkets = new ArrayList<String>();
            for (var t : data.trinkets.values())
                if (t.heroClass.isEmpty() || t.heroClass.equals(id)) trinkets.add(t.id);
            Collections.shuffle(trinkets, rng);
            int count = rng.nextDouble() < .8 ? 2 : rng.nextInt(2);
            members.add(new HeroLoadout(id, skills.subList(0, Math.min(4, skills.size())),
                    trinkets.subList(0, Math.min(count, trinkets.size())), "A"));
        }
        return new TeamConfig("random", members);
    }

    private static boolean tactics, balanced;
    static final class Worker {
        final ArenaValue frozen = tactics ? ArenaValue.read(ArenaValue.FILE) : null;
        final double[] hidden = new double[ArenaValue.HIDDEN];
        final CircusSearchBot base = new CircusSearchBot(.005);
        final NeuralSearchBot neuralTeacher = balanced ? new NeuralSearchBot(new BotContext("teacher", "teacher", null, null,
                Map.of("extended", 1, "control_variate", 0, "tactics_model", TacticalFeatures.FILE.toString()), null, .012)) : null;
        final EffectiveStats stats = new EffectiveStats();
        final List<CircusSearchBot> teachers = new ArrayList<>();
        Worker() {
            for (String profile : new TreeSet<>(CircusSearchBot.PROFILES.keySet()))
                teachers.add(new CircusSearchBot(profile, .010, profile, null, null, 3, 8, 4));
        }
        List<Example> play(long seed) {
            var rng = new Random(seed);
            var left = lineup(rng);
            var right = lineup(rng);
            if (balanced) {
                int kind = rng.nextInt(4);
                if (kind == 0 || kind == 1) right = left;
                else if (kind == 2 && !PRESETS.isEmpty()) {
                    left = PRESETS.get(rng.nextInt(PRESETS.size()));
                    right = PRESETS.get(rng.nextInt(PRESETS.size()));
                }
            }
            var e = new ArenaEngine(left, right, seed).cloneForSearch();
            var players = new CircusSearchBot[]{teachers.get(rng.nextInt(teachers.size())),
                    teachers.get(rng.nextInt(teachers.size()))};
            if (balanced && rng.nextBoolean()) players[rng.nextInt(2)] = neuralTeacher;
            var samples = new ArrayList<Example>();
            int offset = rng.nextInt(4);
            while (!e.state.finished) {
                if (e.state.turnCount % 4 == offset) {
                    double[] x = new double[ArenaValue.INPUT];
                    ArenaValue.encode(e, 0, x, stats);
                    double baseline = base.evaluateState(e, 0) / ArenaValue.SCALE;
                    if (frozen != null) {
                        baseline += frozen.residual(x, hidden);
                        TacticalFeatures.encode(e, 0, x, stats);
                    }
                    samples.add(new Example(x, baseline, 0));
                }
                int side = e.state.activeTeam;
                e.step(players[side].chooseAction(e.getActionObservation(side), e));
            }
            double y = e.state.winner == null ? .5 : e.state.winner == 0 ? 1 : 0;
            return samples.stream().map(s -> new Example(s.x, s.base, y)).toList();
        }
    }

    static final ThreadLocal<Worker> WORKER = ThreadLocal.withInitial(Worker::new);
    static void collect(ExecutorService pool, List<Example> into, int n, long seed) throws Exception {
        var futures = new ArrayList<Future<List<Example>>>();
        var seeds = new SplittableRandom(seed);
        for (int i = 0; i < n; i++) {
            long game = seeds.nextLong();
            futures.add(pool.submit(() -> WORKER.get().play(game)));
        }
        for (var f : futures) into.addAll(f.get());
    }

    static double loss(ArenaValue m, List<Example> set) {
        double sum = 0;
        double[] h = new double[ArenaValue.HIDDEN];
        for (var s : set) {
            double z = s.base + m.residual(s.x, h);
            sum += Math.max(0, z) - s.result * z + Math.log1p(Math.exp(-Math.abs(z)));
        }
        return sum / set.size();
    }

    static void epoch(ArenaValue m, List<Example> set, Random rng, double rate) {
        Collections.shuffle(set, rng);
        double[] hidden = new double[ArenaValue.HIDDEN];
        for (var s : set) {
            double z = s.base + m.residual(s.x, hidden);
            double error = 1 / (1 + Math.exp(-z)) - s.result;
            for (int h = 0; h < hidden.length; h++) {
                double delta = error * m.output[h] * (1 - hidden[h] * hidden[h]);
                m.output[h] -= rate * (error * hidden[h] + .0005 * m.output[h]);
                for (int i = 0; i < s.x.length; i++)
                    m.weights[h][i] -= rate * (delta * s.x[i] + .0005 * m.weights[h][i]);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        int games = args.length > 0 ? Integer.parseInt(args[0]) : 2048;
        int workers = args.length > 1 ? Integer.parseInt(args[1]) : 4;
        long seed = args.length > 2 ? Long.parseLong(args[2]) : 719211;
        if (games < 1 || workers < 1) throw new IllegalArgumentException("Positive games and workers required");
        var rng = new Random(seed);
        balanced = args.length > 3 && args[3].equals("balanced");
        tactics = balanced || (args.length > 3 && args[3].equals("tactics"));
        var output = balanced ? TacticalFeatures.BALANCED : tactics ? TacticalFeatures.FILE : ArenaValue.FILE;
        var model = new ArenaValue();
        var valid = new ArrayList<Example>();
        var train = new ArrayList<Example>();
        int validationGames = Math.clamp(games / 12, 8, 160);
        System.out.printf("Starting %d training games, %d held-out games, %d workers, seed %d%n",
                games, validationGames, workers, seed);
        try (var pool = Executors.newFixedThreadPool(workers)) {
            collect(pool, valid, validationGames, ~seed);
            double best = loss(model, valid);
            System.out.printf("Baseline validation log loss %.5f%n", best);
            var initial = balanced && !Files.exists(output) ? TacticalFeatures.FILE : output;
            if (Files.exists(initial)) {
                model = ArenaValue.read(initial);
                double initialLoss = loss(model, valid);
                if (!Files.exists(output) && initialLoss < best) model.save(output);
                best = Math.min(best, initialLoss);
                System.out.printf("Starting checkpoint validation log loss %.5f%n", initialLoss);
            } else {
                for (var row : model.weights) for (int i = 0; i < row.length; i++)
                    row[i] = .12 * rng.nextGaussian();
            }
            for (int start = 0; start < games; start += 64) {
                int count = Math.min(64, games - start);
                collect(pool, train, count, rng.nextLong());
                for (int epoch = 0; epoch < 4; epoch++) {
                    epoch(model, train, rng, (balanced ? .0015 : .006) / Math.sqrt(1 + start / 256.0));
                    double score = loss(model, valid);
                    if (score < best) {
                        model.save(output);
                        best = score;
                    }
                }
                System.out.printf("Games %d/%d positions %d best validation log loss %.5f%n",
                        start + count, games, train.size(), best);
            }
        }
        System.out.println("Training complete");
    }
}
