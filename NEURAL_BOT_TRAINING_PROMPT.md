# 通用神经搜索 Bot 重构与训练任务说明（Darkest Dungeon: The Butcher's Circus）

## 1. 任务目标
设计并训练一个**真正通用（不依赖特定阵容手写规则）、推理极快、能在相同时间预算下稳定战胜现有 `CircusSearchBot` 的神经搜索 Bot**，并在本仓库中完成代码集成、训练与严格胜率验证。

---

## 2. 仓库现状与已验证的瓶颈诊断

### 2.1 关键文件位置
- 战斗引擎与规则：`java/src/com/circus/engine/ArenaEngine.java`（注意：命中率公式已更新为 `ArenaEngine.computeHitChance(acc, dodge)`：`diff < 5% -> 5%`，`diff >= 95% -> 100%`，其余 `diff + 5%`）
- 现有最强纯搜索基线：`java/src/com/circus/bot/CircusSearchBot.java`（迭代加深 Alpha-Beta + Expectimax 死门/命中双分支 + 置换表 TT + PVS + 死门静止搜索 Quiescence）
- 现有神经 Bot 与训练器：
  - `java/src/com/circus/bot/NeuralValueBot.java`
  - `java/src/com/circus/bot/NeuralSearchBot.java`（注意：当前 `use_alphabeta` 默认为 `1`，可按需调整或新建独立类）
  - `java/src/com/circus/bot/UnitNeuralSearchBot.java`
  - `java/src/com/circus/bot/ArenaValue.java` / `TacticalFeatures.java` / `UnitValue.java`
  - `java/src/com/circus/bot/NeuralTrainer.java` / `UnitTrainer.java`
- Bot 注册表：`java/src/com/circus/bot/BotRegistry.java`
- 预设队伍：`team/*.json`（动态通过 `TeamConfigStore.list()` 加载，队伍数量会持续扩充、流派百花齐放，默认 `"time_budget": 0.25`）
- 编译与无头锦标赛命令：
  - 编译：`./build.sh`（Windows 用 `build.ps1` / `build.bat`）
  - 跑对抗：`./run_tournament.sh --bots <bot1>,<bot2> --matches 30 --workers <cores> --budget 0.25 --seed 42 --replays 2`

### 2.2 为什么现有 `NeuralSearchBot` / `UnitNeuralSearchBot` 打不过 `CircusSearchBot`？（已实测定位）
实测（`iron_hound` vs `iron_wall`，30 局，0.25s，seed 42）：
- 纯搜索 `iron_wall` vs `iron_hound`：**13 - 17（43.33%）**
- 原版 rollout `iron_wall@unit_neural_search` vs `iron_hound`（24 局）：**11 - 13（45.83%）**
- Alpha-Beta + 现有 3 层神经 `iron_wall@unit_neural_search` vs `iron_hound`：**8 - 22（26.67%）**；去掉 `iron_wall` 的 `strategicValue`（`align_base=1`）更是暴跌到 **2 - 22（8.33%）**。

核心根因有 4 条，新架构必须全部解决：
1. **3 个网络串联太慢，拖垮 Alpha-Beta 深度**：`UnitNeuralSearchBot.evaluateState()` 每个叶节点依次跑 `CircusSearchBot` + `ArenaValue` + `TacticalFeatures` + `UnitValue`，且反复调用 `computeEffectiveStats()` 与技能遍历，单节点耗时过高，把 Alpha-Beta 深度砍掉一大截。
2. **看不见跨单位交互**：`UnitValue` 是单人独立打分再求和 `sum(ally) - sum(enemy)`，无法表达最关键的战术关系——例如“己方死门时我方奶妈是否未行动”、“敌方高致死斩杀手（如赏金）是否未行动”、“标记/流血目标是否在斩杀手射程内”。
3. **参数冗余且缺正则**：`UnitValue` 建了 ~800+ 维稀疏词表（按英雄名/技能名/饰品名 one-hot）× 32 隐藏层（约 2.8 万参数），`UnitTrainer` 无 L2 权重衰减，且仅在 2048 局上训练，极易过拟合且泛化差。
4. **训练数据分布弱 + 底座错位**：`NeuralTrainer` / `UnitTrainer` 有 2/3 对局是完全随机拼凑的杂牌阵容，老师只有 `0.010s` 深度 3；且训练残差时用的是 `balanced`（`strategic_eval=false`）底座，实战却叠在 `iron_wall` / `iron_hound` 的 `strategic_eval=true` 上造成双重计分。

---

## 3. 新架构设计要求

### 3.1 模型架构：单网络、超轻量、支持跨单位交互（DeepSets / NNUE 风格）
不要再堆 3 个串联网络！合并为**单个纯 Java 前向网络**（零外部依赖，热路径零对象分配，单次叶节点评估目标 `< 10 µs`）：
1. **通用单位特征编码（约 32~40 维稠密数值特征，不要 800 维技能名 one-hot）**：
   - 生存与状态：`hp / maxHp`、`hp / 45`、`atDeathsDoor`、`deathblowRes / 100`、`stress / 200`、`stress >= 150`、`isAfflicted`、`isVirtuous`、`actedThisRound`、`stunned`、`dazed`、`guardedBy != null`、`guarding != null`、`aegisBlocks`、`stealthRounds > 0`、`riposteRounds > 0`、`markedRounds > 0`、`rank (1..4)`。
   - 有效属性（**每个单位只调用一次 `computeEffectiveStats`**）：`prot`、`dodge / 50`、`acc / 30`、`dmgPct`、`crit / 30`、`stressRes / 50`、`stunRes / 100`。
   - 当前站位下的**即时可用技能能力**（单次遍历 `equippedSkillDefs`）：
     - 最大直接伤害期望、对敌方死门/低血目标的最高致死加成 `maxDeathblowBonus`（仅当射程能打到敌方死门/残血站位时计入！）、最大压力伤害、最大眩晕概率、是否可立即直接治疗（区分直接抬血 vs 仅挂 Regen！）、最大直接治疗量、是否可守护/破守护/反击、当前 DOT 净伤/Regen 净回血。
2. **跨单位交互（DeepSets `sum + max` 池化）**：
   - 共享 Unit Encoder：`UnitInput (D) -> H1 (24 or 32, tanh/ReLU/clippedReLU)` 得到每个存活单位的嵌入 `h_u`。
   - 分别对己方和敌方存活单位做 **`sum` 池化**（团队总量）与 **`max` 池化**（捕捉全队最危急死门、最高斩杀威胁、最强可用治疗/控制）。
   - 拼接全局交互特征 `g`（如 `activeTeam`、存活人数差、`己方有死门 && 敌方未动完`、`敌方有死门 && 己方未动完`、`roundNum / maxRounds`、双方尸体数差），输入第二层小 MLP：
     `[my_sum - opp_sum, my_max, opp_max, g] -> H2 (24 or 32) -> 1`（输出胜率 logit `z`）。
   - 保证**反对称性**（交换双方视角时 `V(s, my) = -V(s, opp)`），或在训练/推理时对称化 `(f(my, opp) - f(opp, my)) / 2`。

### 3.2 与搜索框架结合
- **继续使用 `CircusSearchBot` 的迭代加深 Alpha-Beta + Expectimax + 置换表 TT + 死门静止搜索（Quiescence）**，不要退化回 1-ply 贪心 rollout。
- **底座必须严格对齐**：
  - 方案 A（推荐，最通用）：直接端到端拟合胜率 logit（`SCALE * logit`），**完全脱离 `strategicValue` 和各队 profile 权重**，让 `team/*.json` 全部队伍及任意玩家自定义阵容都用同一套通用神经估值。
  - 方案 B（残差）：如果作为残差，必须固定使用无特判的极简底座（如仅存活人数 + HP/Stress 基础项，且训练和推理完全一致），严禁叠加 `iron_wall` 的 `strategicValue`。
- **速度优化**：
  - 复用 `float[]` / `EffectiveStats` scratch buffer，避免在 `evaluateState()` 内 `new` 任何数组或集合；
  - 可用 `clippedReLU`（`clamp(x, 0, 1)` 或 `x > 0 ? x : 0`）代替 `Math.tanh` 提速（注意保持足够表达力，或用分段线性/快速 tanh）；
  - 可在 `CircusSearchBot` 节点数统计（`getLastNodesVisited()` / `getLastDepthReached()`）里实测对比吞吐：要求新 Bot 的搜索吞吐至少达到纯 `CircusSearchBot` 的 **50%~70% 以上**，不能把深度拖垮。

### 3.3 训练管线设计（充分利用高算力机器）
新建独立训练器（例如 `DeepSetsTrainer.java`）和权重文件（例如 `bot/neural-deepsets-v1.bin`）：
1. **高质量、多样的训练数据分布**：
   - **70% 强队与真实阵容**：通过 `TeamConfigStore.list()` 动态加载 `team/*.json` 全部队伍（覆盖所有流派），随机配对 + 镜像对局 + 对现有强队做轻微变体（换 1 个英雄/饰品/技能）。
   - **30% 随机合法阵容**：保证 18 个英雄、全部 126 个技能和 103 个饰品都有覆盖，确保面对任意未见过的新配队/玩家自定义队伍也能强泛化。
2. **多代迭代自博弈（Iterative Self-Play，建议 3~4 代）**：
   - **第 0 代**：用较强的 `CircusSearchBot`（`budget = 0.04s ~ 0.08s`，覆盖全部 profile）互打生成 3,000 ~ 6,000 局。
   - **第 1 ~ 3 代**：把上一代训出的新神经 Bot（开启 Alpha-Beta 搜索）加入对弈池，与 `CircusSearchBot` 以及自身镜像互打，每代补充新数据重训/微调，主动修复自身战术盲点（如死门漏奶、抗压失衡等）。
3. **训练目标与正则化（降低标签方差，防止过拟合）**：
   - 目标标签可采用 **胜负结果 + 搜索估值混合（或 TD(λ)）**：例如 `target = 0.7 * game_outcome + 0.3 * sigmoid(teacher_search_eval / SCALE)`，显著降低长局随机波动带来的噪声。
   - 使用 **Adam 或带 Momentum 的 mini-batch SGD** + **L2 weight decay（如 `1e-4 ~ 5e-4`）** + 学习率衰减 + 梯度裁剪。
   - **独立验证集**：固定一组全流派强队验证局（动态从 `TeamConfigStore.list()` 采样跨流派互打与镜像），按验证集 log-loss 保存最佳权重；同时每代结束后直接跑实际对局胜率验证，只保留**实战胜率提升**的 checkpoint。
4. **多线程加速**：充分吃满训练机 CPU 核心并发生成自博弈对局。

---

## 4. 工程集成与验证标准（必须全部达标）

1. **代码集成**：
   - 新 Bot 类注册到 `BotRegistry.java`（例如别名 `deepsets_neural` 或升级替换 `unit_neural_search`，保持向后兼容、不破坏已有测试），确保网页端「玩家 vs AI」「AI vs AI」「无头锦标赛」均可直接选择。
   - `./build.sh` 零错误编译通过。
2. **必须通过以下 4 项严格基准测试（使用 `./run_tournament.sh`，单步预算 `0.25s`，同时测 `0.10s`）**：
   - **测试 1（同阵容镜像，消除阵容克制偏差）**：
     - `iron_wall` vs `iron_wall@<新Bot>`（≥ 30 局）
     - `iron_hound` vs `iron_hound@<新Bot>`（≥ 30 局）
     - `direct_kill` vs `direct_kill@<新Bot>`（≥ 30 局）
     - **目标**：三组镜像平均胜率 **> 55%**（不能只赢一队、输另一队）。
   - **测试 2（跨阵容对照）**：
     - `iron_hound` vs `iron_wall@<新Bot>`（30 局，`--seed 42 --budget 0.25`）
     - **目标**：明显超越纯搜索 `iron_wall` 基线（`13-17`，43.33%），最好达到 **≥ 50%**。
   - **测试 3（全流派多阵容通用性检验）**：
     - 从 `team/*.json` 动态选取覆盖不同流派的多支代表队伍（直伤、强晕、压力、流血/腐蚀、乱位、铁桶、标记、反击等，或全量队伍）做对照（原版 `CircusSearchBot` vs 新 Bot），验证全流派及未见过的自定义阵容均稳定提升。
   - **测试 4（搜索速度与死门行为检验）**：
     - 打印平均搜索深度 `lastDepthReached` 与 `lastNodesVisited`，确认未出现深度崩塌；
     - 检查至少 2 局录像，确认面对死门队友 + 敌方高致死斩杀手时能合理救人/守护。

---

## 5. 输出要求
完成训练和验证后，请汇总以下内容：
1. 新架构特征维度、参数量、单次叶节点估值耗时与平均搜索深度；
2. 各代训练的验证集 log-loss 曲线与训练局数/耗时；
3. 上述 4 项基准测试的完整胜率对比表（对比 `CircusSearchBot`、原版 `UnitNeuralSearchBot` 和新 Bot）。
