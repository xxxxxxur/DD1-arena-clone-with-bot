package com.circus.bot;

import com.circus.engine.ArenaEngine;
import com.circus.engine.Models.Action;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class DeepSetsBot extends CircusSearchBot {
    static final int DEFAULT_H = 24;
    static final Path FILE = ArenaValue.FILE.resolveSibling("neural-deepsets-v1.bin");
    private final String searchMode = System.getProperty("circus.deepsets.searchMode", "rescue_wide");
    private final ArenaEngine.SearchScratch rootScratch = new ArenaEngine.SearchScratch();
    private final int h1, h2;
    private final float[][] encoder, head;
    private final float[] encoderBias, headBias, output;
    private final DeepSetsPosition position = new DeepSetsPosition();
    private final float[][] sum, max;
    private final float[] hidden1, hidden2, input;
    // Adjacent search leaves frequently share unchanged rows at the same rank.
    private final float[][] lastRows = new float[8][DeepSetsPosition.D];
    private final float[][] embeddings;

    public DeepSetsBot(BotContext ctx) {
        this(ctx, Path.of(System.getProperty("circus.deepsets.model", FILE.toString())));
    }

    @Override public boolean requiresUnitObservations() { return false; }

    DeepSetsBot(BotContext ctx, Path model) {
        super(ctx);
        try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(model)))) {
            int magic = in.readInt();
            if (magic == 0x44535631) {
                h1 = DEFAULT_H;
                h2 = DEFAULT_H;
            } else if (magic == 0x44535632) {
                h1 = in.readInt();
                h2 = in.readInt();
                if (h1 < 1 || h1 > 256 || h2 < 1 || h2 > 256) throw new IOException("Invalid DeepSets dimensions");
            } else {
                throw new IOException("Invalid DeepSets model header");
            }
            int inputDim = 3 * h1 + DeepSetsPosition.G;
            encoder = matrix(in, DeepSetsPosition.D, h1);
            encoderBias = vector(in, h1);
            head = matrix(in, inputDim, h2);
            headBias = vector(in, h2);
            output = vector(in, h2);
            if (in.read() != -1) throw new IOException("Unexpected trailing weights");
            sum = new float[2][h1];
            max = new float[2][h1];
            hidden1 = new float[h1];
            hidden2 = new float[h2];
            input = new float[inputDim];
            embeddings = new float[8][h1];
        } catch (IOException ex) { throw new UncheckedIOException(model.toString(), ex); }
    }

    private static float[][] matrix(DataInputStream in, int rows, int cols) throws IOException {
        var result = new float[rows][];
        for (int i = 0; i < rows; i++) result[i] = vector(in, cols);
        return result;
    }

    private static float[] vector(DataInputStream in, int size) throws IOException {
        var result = new float[size];
        for (int i = 0; i < size; i++) {
            result[i] = in.readFloat();
            if (!Float.isFinite(result[i])) throw new IOException("Invalid model weight");
        }
        return result;
    }

    private static void dense(float[] features, float[][] weights, float[] bias, float[] out, int cols) {
        System.arraycopy(bias, 0, out, 0, cols);
        for (int i = 0, len = features.length; i < len; i++) {
            float value = features[i];
            if (value == 0) continue;
            var weight = weights[i];
            for (int j = 0; j < cols; j++) out[j] += value * weight[j];
        }
    }

    private float view(int side) {
        int opp = 1 - side;
        int h = h1;
        var mySum = sum[side];
        var oppSum = sum[opp];
        var myMax = max[side];
        var oppMax = max[opp];
        for (int j = 0; j < h; j++) {
            input[j] = mySum[j] - oppSum[j];
            input[j + h] = myMax[j];
            input[j + 2 * h] = oppMax[j];
        }
        int offset = h * 3;
        var g = position.global;
        int sign = 1 - 2 * side;
        input[offset] = sign * g[0];
        input[offset + 1] = sign * g[1];
        input[offset + 2] = g[2 + side];
        input[offset + 3] = g[3 - side];
        input[offset + 4] = g[4];
        input[offset + 5] = sign * g[5];
        dense(input, head, headBias, hidden2, h2);
        float result = 0;
        for (int j = 0; j < h2; j++) {
            float v = hidden2[j];
            if (v > 0) result += v * output[j];
        }
        return result;
    }

    @Override
    protected List<ScoredAction> selectRootCandidates(ArenaEngine engine, int myTeam, List<Action> legal) {
        if (!searchMode.startsWith("rescue") || legal.size() <= 1) {
            return super.selectRootCandidates(engine, myTeam, legal);
        }
        // Score every non-surrender legal move with 1-ply neural evaluation (~0.1ms total)
        ArrayList<ScoredAction> neural = new ArrayList<>(legal.size());
        long baseSeed = (engine.state.turnCount + 1L) * 10007L;
        for (int i = 0, n = legal.size(); i < n; i++) {
            Action a = legal.get(i);
            if ("SURRENDER".equals(a.kind)) continue;
            ArenaEngine sim = engine.cloneForSearch(rootScratch);
            sim.rng.reseed(baseSeed + (a.hashCode() & 0x7FFFFFFFL));
            sim.searchOutcomeMode = 4; // expected hit for deterministic 1-ply ranking
            sim.step(a);
            double v = evaluateState(sim, myTeam);
            neural.add(new ScoredAction(v, a));
        }
        if (neural.isEmpty()) return super.selectRootCandidates(engine, myTeam, legal);
        neural.sort((a, b) -> Double.compare(b.score(), a.score()));

        // Union of top-8 neural moves + top-6 heuristic moves (capped at 12), preserving neural order
        // When keeping heuristic prior ("rescue_prior"), score every legal move with orderedCandidates so priorMap stays calibrated.
        boolean keepPrior = "rescue_prior".equals(searchMode);
        List<ScoredAction> heur = orderedCandidates(engine, myTeam, keepPrior ? legal.size() : 6, null, 0, legal);
        HashMap<Action, Double> heurScores = keepPrior ? new HashMap<>(heur.size() * 2) : null;
        if (keepPrior) {
            for (int i = 0; i < heur.size(); i++) heurScores.put(heur.get(i).action(), heur.get(i).score());
        }
        LinkedHashMap<Action, Double> merged = new LinkedHashMap<>(16);
        int nKeep = Math.min(8, neural.size());
        for (int i = 0; i < nKeep; i++) {
            ScoredAction sa = neural.get(i);
            merged.put(sa.action(), keepPrior ? heurScores.getOrDefault(sa.action(), 0.0) : sa.score());
        }
        int hLimit = Math.min(6, heur.size());
        for (int i = 0; i < hLimit && merged.size() < 12; i++) {
            Action a = heur.get(i).action();
            if (!merged.containsKey(a)) {
                double sc = keepPrior ? heur.get(i).score() : -1e9;
                if (!keepPrior) {
                    for (int j = nKeep; j < neural.size(); j++) {
                        if (neural.get(j).action().equals(a)) { sc = neural.get(j).score(); break; }
                    }
                }
                merged.put(a, sc);
            }
        }
        ArrayList<ScoredAction> out = new ArrayList<>(merged.size());
        for (var e : merged.entrySet()) out.add(new ScoredAction(e.getValue(), e.getKey()));
        return out;
    }

    @Override
    protected double rootPriorWeight(int depth) {
        if ("noprior".equals(searchMode)) return 0.0;
        if ("rescue".equals(searchMode) || "rescue_wide".equals(searchMode)) return (depth == 1) ? 0.05 : 0.0;
        return super.rootPriorWeight(depth);
    }

    @Override
    protected int nodeBeamWidth(int depth, int ply) {
        if (searchMode.endsWith("wide")) {
            return (depth >= 3) ? (branchWidth + 1) : (depth == 2 ? Math.max(4, branchWidth) : Math.max(3, branchWidth - 1));
        }
        return super.nodeBeamWidth(depth, ply);
    }

    @Override public double evaluateState(ArenaEngine engine, int team) {
        if (engine.state.finished) return engine.state.winner == null ? 0 : super.evaluateState(engine, team);
        position.encode(engine);
        float base = 0;
        int h = h1;
        for (int side = 0; side < 2; side++) {
            var sideSum = sum[side];
            var sideMax = max[side];
            Arrays.fill(sideSum, 0);
            Arrays.fill(sideMax, 0);
            int sideSign = 1 - 2 * side;
            for (int rank = 0; rank < 4; rank++) {
                int slot = 4 * side + rank;
                var x = position.row[slot];
                if (x[0] == 0) continue;
                base += sideSign * (.9f + .75f * x[1] - .5f * x[5]);
                var embedding = embeddings[slot];
                if (!Arrays.equals(x, lastRows[slot])) {
                    dense(x, encoder, encoderBias, hidden1, h);
                    for (int j = 0; j < h; j++) embedding[j] = Math.max(0, hidden1[j]);
                    System.arraycopy(x, 0, lastRows[slot], 0, x.length);
                }
                for (int j = 0; j < h; j++) {
                    float value = embedding[j];
                    sideSum[j] += value;
                    if (value > sideMax[j]) sideMax[j] = value;
                }
            }
        }
        double z = base + (view(0) - view(1)) * .5;
        return (1 - 2 * team) * ArenaValue.SCALE * z;
    }
}
