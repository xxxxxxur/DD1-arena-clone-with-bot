package com.circus.bot;

import com.circus.engine.ArenaEngine;
import com.circus.engine.Models.Action;
import com.circus.engine.Models.Observation;
import com.circus.util.Json;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs an external executable or script (Python / C++ / Rust / Go / Node.js / Java) as a Bot
 * over newline-delimited JSON (JSON-Lines) stdin/stdout.
 *
 * Wire protocol (100% compatible with Python bots/base.py ExternalSubprocessBot):
 *   Engine -> Bot stdin:  {"type":"CHOOSE_ACTION","observation":{...}}\n
 *   Bot stdout -> Engine: {"kind":"SKILL","actor_uid":"t0_0","skill_id":"smite","target_uids":["t1_0"],"move_delta":0}\n
 */
public final class ExternalSubprocessBot extends AbstractBot {
    private final List<String> command;
    private Process process;
    private BufferedWriter stdin;
    private BufferedReader stdout;

    public ExternalSubprocessBot(List<String> command, String name, String presetId) {
        super(name != null ? name : "ExternalBot", presetId);
        this.command = List.copyOf(command);
    }

    private void ensureProcess() throws IOException {
        if (process == null || !process.isAlive()) {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            process = pb.start();
            stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        }
    }

    @Override
    public Action chooseAction(Observation obs, ArenaEngine engine) {
        try {
            ensureProcess();
            Map<String, Object> req = new LinkedHashMap<>(2);
            req.put("type", "CHOOSE_ACTION");
            req.put("observation", obs.toMap());
            stdin.write(Json.stringify(req));
            stdin.newLine();
            stdin.flush();

            String line = stdout.readLine();
            if (line == null || line.isBlank()) {
                return obs.legalActions.get(0);
            }
            Action chosen = Action.fromMap(Json.parseObject(line));
            if (obs.legalActions.contains(chosen)) {
                return chosen;
            }
        } catch (Exception ignored) {}
        return obs.legalActions.get(0);
    }

    @Override
    public void close() {
        if (process != null) {
            process.destroy();
            process = null;
        }
    }
}
