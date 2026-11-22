package com.circus.bot;

import com.circus.engine.GameData;
import com.circus.engine.Models.PresetDef;
import com.circus.engine.Models.TeamConfig;

import java.util.List;

/**
 * Convenience base class for Butcher's Circus bots.
 * Automatically supports fixed teams loaded from {@code team/*.json} via {@link BotContext},
 * falling back to {@code presetId} or seeded preset rotation when no fixed team is set.
 */
public abstract class AbstractBot implements Bot {
    protected String name;
    protected String presetId;
    protected TeamConfig fixedTeam;

    protected AbstractBot(String name, String presetId) {
        this.name = (name != null && !name.isEmpty()) ? name : getClass().getSimpleName();
        this.presetId = (presetId != null && !presetId.isEmpty()) ? presetId : null;
        this.fixedTeam = null;
    }

    protected AbstractBot(BotContext ctx) {
        this(ctx != null ? ctx.name() : null, ctx != null ? ctx.presetId() : null);
        if (ctx != null && ctx.teamConfig() != null) {
            this.fixedTeam = ctx.teamConfig().copy();
        }
    }

    public void setFixedTeam(TeamConfig team) {
        this.fixedTeam = (team != null) ? team.copy() : null;
    }

    public void setName(String name) {
        if (name != null && !name.isEmpty()) this.name = name;
    }

    @Override
    public String getName() {
        return name;
    }

    public String getPresetId() {
        return presetId;
    }

    @Override
    public TeamConfig chooseTeam(int seed) {
        if (fixedTeam != null) {
            return fixedTeam.copy();
        }
        GameData data = GameData.get();
        // Named built-in presets need neither the full copied catalog nor DLC file I/O.
        if (presetId != null) {
            for (PresetDef preset : data.basePresets) {
                if (preset.id.equals(presetId)) return preset.team.copy();
            }
        }
        List<PresetDef> presets = data.getPresets();
        if (presetId != null) {
            for (PresetDef p : presets) {
                if (p.id.equals(presetId)) {
                    return p.team.copy();
                }
            }
        }
        int idx = Math.floorMod(seed, presets.size());
        return presets.get(idx).team.copy();
    }
}
