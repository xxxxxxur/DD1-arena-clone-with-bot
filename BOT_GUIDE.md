# Butcher's Circus Bot 开发指南与接口文档

本文档简要说明如何为《暗黑地牢：屠夫马戏团》Java 后端编写自定义 Bot，以及引擎暴露的全部核心接口。

### 防守消耗型队伍

`iron_wall` 与 `iron_hound` 使用 `com.circus.bot.FortressBot`，继承通用搜索框架，专门为长期防御与消耗调整动作排序和叶子估值。评估包含有效护甲、闪避、压力抗性、命中、敌方减伤 debuff 与压力积累，限制重复再生的溢出收益；永久全队增益获得启动价值，危急救援仍优先于启动。置换表键额外包含 buff 内容/剩余时间、DoT、技能使用次数、冷却及守护关系。

两队默认每步预算仍为 `0.25` 秒，搜索默认根宽 `10`、分支宽 `4`、最大深度 `10`。可以通过 `bot_options` 调整 `pressure_weight`（长期压力价值，铁桶为 2、猎犬为 3）、`guard_bias`（低威胁时守护的动作偏置，猎犬为 -300）。实验还可覆盖 `mitigation_weight`、`setup_weight`、`bulwark_priority`、`control_bonus`、`root_width`、`branch_width`、`max_depth`。

配装、对照赛结果与复现命令见 `benchmarks/DEFENSE_TUNING.md`。修改 Java 源码后执行 `build.ps1`，已运行的 Java 服务需要重新启动以加载新类。

### 目录解耦设计（推荐）
为了方便 **多个队伍共用同一个 Bot 程序** 以及自由扩展自定义 AI，配置与代码严格分离：
- **`team/*.json`**：存放队伍配置（队伍 ID、队伍名、4 人阵容/技能/饰品/皮肤、使用的 `bot_class` 类名及 `bot_options` 参数）。
- **`bot/*.java`（及内置 `java/src/com/circus/bot/*.java`）**：存放 Bot 算法程序类。运行 `.\build.bat` 会自动编译 `bot/` 目录下所有自定义 `.java` 文件！

示例 `team/my_squad.json`：
```json
{
  "id": "my_squad",
  "name": "我的斩杀队",
  "bot_class": "bot.ExampleCustomBot",
  "bot_options": { "profile": "direct_kill", "time_budget": 0.08 },
  "team": {
    "name": "My Squad",
    "heroes": [
      { "hero_id": "crusader", "skills": ["smite", "stunning_blow", "battle_heal", "holy_lance"], "trinkets": ["sacred_blade", "brass_knuckles"], "variant": "A", "name": "" },
      { "hero_id": "highwayman", "skills": ["duelist_advance", "point_blank_shot", "wicked_slice", "take_aim"], "trinkets": ["parrying_dagger", "the_finisher"], "variant": "B", "name": "" },
      { "hero_id": "jester", "skills": ["dirk_stab", "heroic_end", "battle_ballad", "inspiring_tune"], "trinkets": ["monkey_paw", "harlequin_masque"], "variant": "C", "name": "" },
      { "hero_id": "vestal", "skills": ["dazzling_light", "divine_grace", "gods_comfort", "judgement"], "trinkets": ["idol_of_purity", "gleaming_breastplate"], "variant": "A", "name": "" }
    ]
  }
}
```
> 多个不同的 `team/*.json` 可以把 `bot_class` 指向同一个类（如 `"com.circus.bot.CircusSearchBot"` 或 `"bot.ExampleCustomBot"`），系统会通过 `BotContext` 自动注入对应 JSON 的阵容与参数。

支持两种接入方式：
1. **原生 Java Bot（推荐）**：在 `bot/` 目录编写类并实现 `com.circus.bot.Bot` 接口（推荐继承 `AbstractBot` 并提供 `public MyBot(BotContext ctx)` 构造方法），可调用 `engine.cloneForSearch()` 在微秒级进行零事件分配的前瞻搜索（实测 **99万 ops/s**）。
2. **外部进程 Bot（跨语言）**：通过 `ExternalSubprocessBot` 以 JSON-Lines（stdin/stdout）协议接入 Python / C++ / Rust / Go 等任意语言编写的 AI。

---

## 一、核心接口一览

### 1. `com.circus.bot.Bot`（Bot 顶层接口）

所有 Java Bot 均需实现 `Bot` 接口（通常直接继承便捷基类 `AbstractBot`）：

| 方法签名 | 必需 | 说明 |
| --- | :---: | --- |
| `String getName()` | 是 | 返回 Bot 显示名称（用于 UI 展示、锦标赛 ELO 榜单与录像文件名）。 |
| `TeamConfig chooseTeam(int seed)` | 是 | 返回己方 4 人阵容配置（1~4 号位顺序，**禁止重复职业**，每名英雄最多 4 技能/咒缚者自动全 7 技能、最多 2 件合法饰品）。 |
| `Action chooseAction(Observation obs, ArenaEngine engine)` | 是 | **核心决策方法**：从 `obs.legalActions` 中选出一个合法动作返回。`engine` 为当前实时引擎引用（可能为 `null`）；**严禁直接修改 `engine`**，如需模拟推演请先调用 `engine.cloneForSearch()`。 |
| `void onMatchStart(int teamId, long seed)` | 否 | 对局开始时的生命周期钩子（`teamId` 为 `0` 或 `1`）。 |
| `void onMatchEnd(Integer winner, String reason)` | 否 | 对局结束钩子（`reason` 为 `"elimination"` / `"stalemate_decision"` / `"surrender"`）。 |
| `void close()` | 否 | 资源清理钩子（实现 `AutoCloseable`，如释放子进程或线程池）。 |

> 继承 `com.circus.bot.AbstractBot` 时，只需在构造函数中传入 `(name, presetId)` 即可自动获得基于预设的 `chooseTeam(int seed)` 实现，只需重写 `chooseAction(...)`。

---

### 2. `com.circus.bot.BotRegistry`（Bot 注册表）

将自定义 Bot 注册到全局注册表后，即可直接在 Web UI、房间对战和命令行锦标赛中通过名称调用：

```java
// 注册自定义 Bot
BotRegistry.register("my_bot", opts ->
    new MyCustomBot(opts.name(), opts.presetId(), opts.seed(), opts.timeBudgetSec())
);

// 创建实例
Bot bot = BotRegistry.create("my_bot", "MyBot", "meta_direct_kill", 42L, 0.05);

// 查询所有已注册 Bot 类型
List<String> types = BotRegistry.availableTypes();
```

内置类型包括：`direct_kill`、`stun_kill`、`iron_wall`、`iron_hound`、`shuffle_kill`、`lookahead`、`tactical`、`stress`、`random`。

---

### 3. 核心数据模型（`com.circus.engine.Models`）

#### `Observation`（视角归一化观测对象）
引擎会自动将当前决策方映射为 `myUnits`，对手映射为 `enemyUnits`：
- `int viewerTeam`：当前视角队伍 ID（`0` 或 `1`）
- `int roundNum`：当前回合数（从 1 开始）
- `int activeTeam`：当前行动方队伍 ID
- `boolean isMyTurn` / `boolean finished` / `Integer winner` / `String winReason`
- `List<Map<String, Object>> myUnits` / `enemyUnits`：双方全部单位的序列化状态（包含 `uid`、`hero_id`、`rank`、`hp`、`max_hp`、`stress`、`alive`、`is_corpse`、`acted_this_round`、`selectable`、`stunned`、`dazed`、`at_deaths_door`、`marked_rounds`、`stealth_rounds`、`riposte_rounds`、`guarding_uid`、`guarded_by_uid`、`aegis_blocks`、`is_beast`、`dots`、`stats` 等）
- `List<Action> legalActions`：当前回合**全部合法动作列表**（引擎已处理 Daze 延迟、Stun 强制跳过、潜行、站位与冷却校验）

#### `Action`（原子决策动作）
通过静态工厂方法构造，或直接从 `obs.legalActions` 中选取：
- `Action.skill(actorUid, skillId, targetUids)` — 使用技能（`kind = "SKILL"`）
- `Action.move(actorUid, moveDelta)` — 主动换位（`+1/+2` 前进，`-1/-2` 后退，`kind = "MOVE"`）
- `Action.pass(actorUid)` — 跳过回合（`kind = "PASS"`）
- `Action.surrender()` — 投降（`kind = "SURRENDER"`）

#### `TeamConfig` / `HeroLoadout`（阵容配置）
```java
TeamConfig team = new TeamConfig("My Squad", List.of(
    new HeroLoadout("crusader", List.of("smite", "stunning_blow", "battle_heal", "holy_lance"),
                    List.of("sacred_blade", "brass_knuckles"), "A"),
    new HeroLoadout("highwayman", List.of("duelist_advance", "point_blank_shot", "wicked_slice", "take_aim"),
                    List.of("parrying_dagger", "the_finisher"), "B"),
    new HeroLoadout("jester", List.of("dirk_stab", "heroic_end", "battle_ballad", "inspiring_tune"),
                    List.of("monkey_paw", "harlequin_masque"), "C"),
    new HeroLoadout("vestal", List.of("dazzling_light", "divine_grace", "gods_comfort", "judgement"),
                    List.of("idol_of_purity", "gleaming_breastplate"), "A")
));
```

---

### 4. 高性能搜索专用接口（`ArenaEngine`、`StatKey`、`EffectiveStats`）

如果你编写 Alpha-Beta / Expectimax / MCTS 等搜索类 Bot，推荐使用以下零分配高频 API：

| API | 说明 |
| --- | --- |
| `ArenaEngine sim = engine.cloneForSearch()` | **极速浅/深混合克隆**（关闭 `CombatEvent` 构造，共享不可变技能与永久 Buff，零事件 GC 压力）。 |
| `sim.rng.reseed(long seed)` | 为该分支设置确定性随机种子（用于分层抽样降低 Expectimax 方差）。 |
| `sim.step(Action act)` | 在克隆实例上推进 1 步状态。 |
| `sim.getLegalActions(int team)` | 获取指定队伍的合法动作列表。 |
| `sim.computeEffectiveStats(unit, target, atkType, scratch)` | 单遍原始数组计算有效属性（含死门修正、兽化加成与 19 种条件饰品位掩码），配合 `StatKey.*` 索引读取，**零 HashMap 分配**。 |
| `sim.computeStateHash()` | 计算 64 位局面哈希，可直接作为置换表（Transposition Table）的 Key。 |
| `sim.state` | 直接访问 `roundNum`、`activeTeam`、`finished`、`winner`、`teams[0]`、`teams[1]`（`ArrayList<UnitState>`）。 |
| `sim.findUnit(uid)` / `sim.livingHeroes(team)` / `sim.countLivingHeroes(team)` | 快速检索单位与统计存活英雄。 |

---

## 二、编写 Bot 示例

### 示例 1：基于规则的简单 Java Bot（仅用 `Observation`）

```java
package com.circus.bot;

import com.circus.engine.ArenaEngine;
import com.circus.engine.Models.Action;
import com.circus.engine.Models.Observation;
import com.circus.util.Json;

import java.util.Map;

public class FinisherRuleBot extends AbstractBot {
    public FinisherRuleBot(String name, String presetId) {
        super(name, presetId);
    }

    @Override
    public Action chooseAction(Observation obs, ArenaEngine engine) {
        // 1. 优先寻找处于死门（0 HP）的敌人并直接斩杀
        for (Action act : obs.legalActions) {
            if (!"SKILL".equals(act.kind)) continue;
            for (Map<String, Object> enemy : obs.enemyUnits) {
                String uid = Json.str(enemy, "uid", "");
                boolean atDd = Json.boolVal(enemy, "at_deaths_door", false);
                if (atDd && act.targetUids.contains(uid)) {
                    return act;
                }
            }
        }
        // 2. 否则优先释放任意非 PASS 技能
        for (Action act : obs.legalActions) {
            if ("SKILL".equals(act.kind)) return act;
        }
        return obs.legalActions.get(0);
    }
}
```

### 示例 2：基于 `cloneForSearch()` 的前瞻搜索 Bot

```java
package com.circus.bot;

import com.circus.engine.ArenaEngine;
import com.circus.engine.Models.Action;
import com.circus.engine.Models.Observation;
import com.circus.engine.Models.UnitState;

public class SimpleLookaheadBot extends AbstractBot {
    private final long budgetNs;

    public SimpleLookaheadBot(String name, String presetId, double budgetSec) {
        super(name, presetId);
        this.budgetNs = (long) (budgetSec * 1_000_000_000.0);
    }

    @Override
    public Action chooseAction(Observation obs, ArenaEngine engine) {
        if (obs.legalActions.size() == 1 || engine == null) {
            return obs.legalActions.get(0);
        }
        long deadline = System.nanoTime() + budgetNs;
        int myTeam = obs.viewerTeam;
        Action bestAct = obs.legalActions.get(0);
        double bestVal = Double.NEGATIVE_INFINITY;

        for (Action act : obs.legalActions) {
            if (System.nanoTime() >= deadline) break;
            double sum = 0.0;
            int samples = 4;
            for (int s = 0; s < samples; s++) {
                ArenaEngine sim = engine.cloneForSearch();
                sim.rng.reseed(engine.state.turnCount * 10007L + s * 7919L + act.hashCode());
                sim.step(act);
                sum += evaluate(sim, myTeam);
            }
            double avg = sum / samples;
            if (avg > bestVal) {
                bestVal = avg;
                bestAct = act;
            }
        }
        return bestAct;
    }

    private double evaluate(ArenaEngine eng, int myTeam) {
        if (eng.state.finished) {
            return (eng.state.winner != null && eng.state.winner == myTeam) ? 100000.0 : -100000.0;
        }
        double score = 0.0;
        for (UnitState u : eng.livingHeroes(myTeam)) {
            score += 1000.0 + u.hp * 10.0 - u.stress * 2.0 - (u.atDeathsDoor ? 300.0 : 0.0);
        }
        for (UnitState u : eng.livingHeroes(1 - myTeam)) {
            score -= 1000.0 + u.hp * 10.0 - u.stress * 2.0 - (u.atDeathsDoor ? 300.0 : 0.0);
        }
        return score;
    }
}
```

> 完整的迭代加深 Expectimax + Alpha-Beta + 64K 置换表 + PV/Killer 排序实现可参考源码 [CircusSearchBot.java](file:///d:/Users/bradxrr/OneDrive/文档/gemini_dd/java/src/com/circus/bot/CircusSearchBot.java)。

---

### 示例 3：外部进程 Bot（JSON-Lines 跨语言协议）

通过 `ExternalSubprocessBot` 可将任何可执行文件或脚本接入引擎：

```java
Bot extBot = new ExternalSubprocessBot(List.of("py", "my_bot.py"), "PyExternalBot", "meta_direct_kill");
```

通信协议（每条消息一行 JSON，UTF-8）：
- **引擎写入子进程 `stdin`**：
  ```json
  {"type":"CHOOSE_ACTION","observation":{"viewer_team":0,"round_num":1,"active_team":0,"is_my_turn":true,"my_units":[...],"enemy_units":[...],"legal_actions":[{"kind":"SKILL","actor_uid":"t0_0","skill_id":"pierce","target_uids":["t1_0"],"move_delta":0}]}}
  ```
- **子进程输出到 `stdout`（必须及时 flush）**：
  ```json
  {"kind":"SKILL","actor_uid":"t0_0","skill_id":"pierce","target_uids":["t1_0"],"move_delta":0}
  ```

最小 Python 外部 Bot 示例：
```python
import json, sys

for line in sys.stdin:
    req = json.loads(line)
    legal = req["observation"]["legal_actions"]
    skills = [a for a in legal if a["kind"] == "SKILL"]
    chosen = skills[0] if skills else legal[0]
    sys.stdout.write(json.dumps(chosen) + "\n")
    sys.stdout.flush()
```

---

## 三、编译、测试与锦标赛评测

1. **编译 Java 后端与测试**：
   ```powershell
   .\build.bat
   ```
2. **启动游戏服务器（自动兼容现有 Web UI 与本地双开 PvP）**：
   ```powershell
   .\run_server.bat
   # 默认监听 http://127.0.0.1:8765
   ```
3. **运行多线程无头锦标赛评测 Bot 强度（输出 ELO、胜率与网页可回放录像）**：
   ```powershell
   .\run_tournament.bat --bots direct_kill,stun_kill,iron_wall,shuffle_kill --matches 20 --workers 8 --budget 0.05 --replays 2
   ```
   也支持指定阵容预设（`<bot类型>:<预设ID>`）：
   ```powershell
   .\run_tournament.bat --bots lookahead:mark_execution,stress:stress_circus --matches 30 --workers 8 --budget 0.03
   ```

---

## 四、开发注意事项

1. **绝不直接修改传入的 `engine`**：`chooseAction` 收到的 `ArenaEngine` 是正在运行的权威对局实例；任何推演必须基于 `engine.cloneForSearch()` 或 `engine.cloneEngine()`。
2. **只返回 `obs.legalActions` 中的动作**：避免自行拼接未经校验的 `target_uids`（例如潜行单位、守护、Daze 强制延后等规则均已由 `getLegalActions()` 严格过滤）。
3. **充分利用屠夫马戏团专属规则评估局面**：
   - **流血 / 腐蚀 DoT 不会触发致死检定**：对 0 HP 死门敌人叠 DoT 无法完成击杀，必须使用直接伤害、谢幕（Finale）或 200 压力心脏骤停。
   - **心脏骤停规则**：非死门时掉至 0 HP 且压力回落至 170；**已在死门时 200 压力无视抗死门直接即死**。
   - **严格交替行动**：回合边界同样交替（上回合最后行动的一方，下回合必为后手），Daze 英雄被迫延后至队内未晕眩队友全行动完才能出场。
4. **复用缓冲对象减少 GC**：高频搜索时优先使用 `EffectiveStats` 复用对象与原始数组，配合每 64 节点检查一次 `System.nanoTime()`，可在极短时间预算内最大化有效搜索深度。
