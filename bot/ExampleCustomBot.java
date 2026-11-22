package bot;

import com.circus.bot.AbstractBot;
import com.circus.bot.BotContext;
import com.circus.engine.ArenaEngine;
import com.circus.engine.Models.Action;
import com.circus.engine.Models.Observation;
import com.circus.util.Json;

import java.util.Map;

/**
 * 示例自定义 Bot（放置在项目根目录 bot/ 文件夹下，build.bat 会自动编译）。
 *
 * 使用方法：
 * 在 team/ 文件夹下新建任意 .json 文件，并将 "bot_class" 设为 "bot.ExampleCustomBot"（或简写 "ExampleCustomBot"）。
 * 多个不同的 team/*.json 可以共用同一个 Bot 类！
 */
public class ExampleCustomBot extends AbstractBot {
    public ExampleCustomBot(BotContext ctx) {
        super(ctx);
    }

    public ExampleCustomBot() {
        super("ExampleCustomBot", (String) null);
    }

    @Override
    public Action chooseAction(Observation obs, ArenaEngine engine) {
        // 1. 优先尝试终结处于死门（0 HP）的敌方英雄
        for (Action act : obs.legalActions) {
            if (!"SKILL".equals(act.kind)) continue;
            for (Map<String, Object> enemy : obs.enemyUnits) {
                if (Json.boolVal(enemy, "at_deaths_door", false)
                        && act.targetUids.contains(Json.str(enemy, "uid", ""))) {
                    return act;
                }
            }
        }
        // 2. 否则优先释放任意技能
        for (Action act : obs.legalActions) {
            if ("SKILL".equals(act.kind)) return act;
        }
        return obs.legalActions.get(0);
    }
}
