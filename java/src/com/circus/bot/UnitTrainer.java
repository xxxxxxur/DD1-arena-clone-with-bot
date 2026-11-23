package com.circus.bot;

import com.circus.engine.*;
import java.util.*;
import java.util.concurrent.*;

public final class UnitTrainer {
    record Position(UnitValue.Row[] rows, int split, double base) {}
    record Game(List<Position> positions, double target) {}

    private static final class Worker {
        final NeuralTrainer.Worker teachers=new NeuralTrainer.Worker();
        final NeuralSearchBot baseline=new NeuralSearchBot(new BotContext("teacher","teacher",null,null,
                Map.of(),null,.8));
        final NeuralSearchBot fast=new NeuralSearchBot(new BotContext("fast","fast",null,null,
                Map.of(),null,.012));

        Game play(long seed) {
            var rng=new Random(seed);
            var left=NeuralTrainer.lineup(rng);
            var right=NeuralTrainer.lineup(rng);
            if(rng.nextInt(4)==0) right=left;
            var e=new ArenaEngine(left,right,seed).cloneForSearch();
            var pool=teachers.teachers;
            var players=new CircusSearchBot[]{pool.get(rng.nextInt(pool.size())),pool.get(rng.nextInt(pool.size()))};
            if(rng.nextInt(3)==0) players[rng.nextInt(2)]=fast;
            var positions=new ArrayList<Position>();
            int offset=rng.nextInt(6);
            while(!e.state.finished) {
                if(e.state.turnCount%6==offset) {
                    var units=new ArrayList<UnitValue.Row>();
                    int split=0;
                    for (var side:e.state.teams) for(var u:side) if(u.alive) {
                        var r=new UnitValue.Row();
                        UnitValue.encode(e,u,r);
                        units.add(r);
                        if(u.team==0) split++;
                    }
                    positions.add(new Position(units.toArray(UnitValue.Row[]::new),split,
                            baseline.evaluateState(e,0)/ArenaValue.SCALE));
                }
                int side=e.state.activeTeam;
                e.step(players[side].chooseAction(e.getActionObservation(side),e));
            }
            Collections.shuffle(positions,rng);
            return new Game(positions.subList(0,Math.min(positions.size(),16)),
                    e.state.winner==null ? .5 : e.state.winner==0 ? 1 : 0);
        }
    }
    private static final ThreadLocal<Worker> WORKER=ThreadLocal.withInitial(Worker::new);
    private static List<Game> games(ExecutorService pool,int n,long seed) throws Exception {
        var jobs=new ArrayList<Future<Game>>();
        var rng=new SplittableRandom(seed);
        for(int i=0;i<n;i++) {
            long s=rng.nextLong();
            jobs.add(pool.submit(()->WORKER.get().play(s)));
        }
        var data=new ArrayList<Game>();
        for(var f:jobs) data.add(f.get());
        return data;
    }

    private static double pass(UnitValue model,List<Game> data,double rate) {
        var hidden=new float[8][UnitValue.HIDDEN];
        var outGradient=new float[UnitValue.HIDDEN];
        double loss=0;
        int count=0;
        for(var game:data) for(var p:game.positions) {
            double z=p.base;
            for(int u=0;u<p.rows.length;u++) z+=(u<p.split ? 1 : -1)*model.score(p.rows[u],hidden[u]);
            loss+=Math.max(z,0)-game.target*z+Math.log1p(Math.exp(-Math.abs(z)));
            count++;
            if(rate==0) continue;
            double step=rate*(1/(1+Math.exp(-z))-game.target);
            Arrays.fill(outGradient,0);
            for(int u=0;u<p.rows.length;u++) {
                var row=p.rows[u];
                float sign=u<p.split ? 1 : -1;
                var h=hidden[u];
                for(int k=0;k<h.length;k++) {
                    outGradient[k]+=sign*h[k];
                    h[k]=(float)(step*sign*model.out[k]*(1-h[k]*h[k]));
                }
                for(int j=0;j<row.n;j++) {
                    float[] w=model.w[row.id[j]];
                    for(int k=0;k<w.length;k++) w[k]-=h[k]*row.v[j];
                }
            }
            for(int k=0;k<model.out.length;k++) model.out[k]-=step*outGradient[k];
        }
        return loss/count;
    }
    public static void main(String[] args) throws Exception {
        int n=args.length>0 ? Integer.parseInt(args[0]) : 2048;
        var rng=new Random(781193);
        var net=new UnitValue();
        for(var w:net.w) for(int k=0;k<w.length;k++) w[k]=(float)(rng.nextGaussian()*.075);
        try(var pool=Executors.newFixedThreadPool(10)) {
            var valid=games(pool,192,238812);
            var train=games(pool,n,993411);
            double best=pass(net,valid,0);
            System.out.printf("games=%d train_positions=%d unit_parameters=%d base_validation=%.5f%n",n,
                    train.stream().mapToInt(g->g.positions.size()).sum(),(UnitValue.C.size+1)*UnitValue.HIDDEN,best);
            boolean saved=false;
            for(int epoch=0;epoch<12;epoch++) {
                Collections.shuffle(train,rng);
                pass(net,train,.0015/Math.sqrt(1+epoch));
                double loss=pass(net,valid,0);
                if(loss<best){best=loss;net.write();saved=true;}
                System.out.printf("epoch=%d validation=%.5f best=%.5f%n",epoch+1,loss,best);
            }
            if(!saved) throw new IllegalStateException("No checkpoint beat the frozen baseline");
            var test=games(pool,192,628892);
            System.out.printf("independent_games=192 baseline_loss=%.5f candidate_loss=%.5f%n",
                    pass(new UnitValue(),test,0),pass(UnitValue.read(),test,0));
        }
    }
}
