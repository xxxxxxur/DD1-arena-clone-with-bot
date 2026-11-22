package com.circus;

import com.circus.bot.*;
import com.circus.engine.*;
import com.circus.engine.Models.*;
import com.circus.util.Json;
import java.nio.file.*;
import java.util.*;

/** Compact action trace for diagnosing specific paired-evaluation losses. */
public final class DefenseTrace {
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        var create = DefenseEvaluation.class.getDeclaredMethod("create", Map.class, long.class, double.class, int.class, boolean.class);
        create.setAccessible(true);
        long seed = Long.parseLong(args[2]);
        var subjectConfig = Json.parseObject(Files.readString(Path.of(args[0])));
        var opponentConfig = Json.parseObject(Files.readString(Path.of("team", args[1] + ".json")));
        Bot a = (Bot) create.invoke(null, subjectConfig, seed, .46, 2, false);
        Bot b = (Bot) create.invoke(null, opponentConfig, seed + 991, .46, 2, true);
        var result = Simulator.runMatch(a, b, null, null, seed, 40, true);
        System.out.printf("winner=%d rounds=%d%n", result.winner(), result.rounds());
        Files.writeString(Path.of("benchmarks", "defense-trace.json"), Json.stringify(result.replay()));
        for (var frame : (List<Map<String, Object>>) result.replay().get("frames")) {
            Map<String, Object> obs = (Map<String, Object>) frame.get("obs0");
            if (frame.get("action") == null) continue;
            System.out.printf("r%s %s ", obs.get("round_num"), frame.get("action"));
            for (String key : List.of("my_units", "enemy_units")) {
                System.out.print(" | ");
                for (var u : (List<Map<String, Object>>) obs.get(key)) {
                    if (Boolean.FALSE.equals(u.get("alive")) || Boolean.TRUE.equals(u.get("is_corpse"))) continue;
                    System.out.printf("%s:%s/%s%s ", u.get("hero_id"), u.get("hp"), u.get("stress"),
                            Boolean.TRUE.equals(u.get("stunned")) ? "!" : "");
                }
            }
            System.out.println();
        }
    }
}
