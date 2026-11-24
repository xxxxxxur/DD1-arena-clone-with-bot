package com.circus.bot;

import com.circus.engine.ArenaEngine;
import com.circus.engine.Models.Action;
import com.circus.engine.Models.Observation;
import com.circus.engine.Models.TeamConfig;

/**
 * Core interface for all Darkest Dungeon: The Butcher's Circus bots.
 *
 * Implement this interface (or extend {@link AbstractBot}) to write a custom AI.
 * Register your bot via {@link BotRegistry#register(String, BotRegistry.BotFactory)}
 * to use it in Web UI matches, PvP rooms, and multi-threaded tournaments.
 */
public interface Bot extends AutoCloseable {
    /** Display name of this bot. */
    String getName();

    /** Select a 4-hero Butcher's Circus team configuration for this bot. */
    TeamConfig chooseTeam(int seed);

    /**
     * Choose one legal {@link Action} from {@code obs.legalActions}.
     *
     * @param obs    Perspective-normalized observation ({@code myUnits} vs {@code enemyUnits},
     *               plus the exact list of {@code legalActions}).
     * @param engine Live authoritative {@link ArenaEngine} reference (may be {@code null} for
     *               pure observation-only / remote bots). Search bots should call
     *               {@link ArenaEngine#cloneForSearch()} and NEVER mutate {@code engine} directly.
     * @return One legal action from {@code obs.legalActions}.
     */
    Action chooseAction(Observation obs, ArenaEngine engine);

    /** Opt in to action-only observations when a live engine is supplied.
     *  Custom bots receive full unit snapshots by default. */
    default boolean requiresUnitObservations() { return true; }

    /** Optional lifecycle hook called when a match starts. */
    default void onMatchStart(int teamId, long seed) {}

    /** Optional lifecycle hook called when a match ends. */
    default void onMatchEnd(Integer winner, String reason) {}

    /** Optional cleanup hook (e.g. shutting down subprocesses or thread pools). */
    @Override
    default void close() {}

    /** Optional search telemetry for single-match live console (null if not a search bot). */
    default java.util.Map<String, Object> getLastSearchStats() { return null; }
}
