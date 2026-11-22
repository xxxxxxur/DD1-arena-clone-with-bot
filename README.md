# Darkest Dungeon: The Butcher's Circus — 网页复刻版 & 无头 Bot 对战引擎

基于本地安装的《暗黑地牢》(Darkest Dungeon) 及屠夫马戏团免费 DLC (`1117860_arena_mp`) 原始游戏素材构建的**前后端严格分离**网页复刻版与纯 **Java 26** 无头竞技场引擎（零第三方运行时依赖）。

> 本仓库不包含任何原版游戏美术、骨骼或音频素材，运行前需在本地拥有正版《暗黑地牢》及屠夫马戏团 DLC。

---

## 核心特性

1. **100% 原始游戏素材直读与渲染（无需复制大体积资源）**
   - **Spine 2.1 实时骨骼动画**：Java 原生实现 Spine 2.1.27 二进制 `.skel` 解析与缓存，前端 Canvas2D 完整支持骨骼层级、Region / Mesh / SkinnedMesh 三角形变形、FFD、DrawOrder、Additive 混合与左右镜像。
   - **全部 18 位英雄 × A/B/C/D 4 套皮肤**：包含全部攻击动作 (`attack_*`)、受击防御 (`defend`)、美德/折磨 (`heroic`/`afflicted`)、屠夫马戏团专属尸体 (`dead`)，以及英雄技能专属 FX、血迹飞溅、掷币 (`coinflip`)、竞技场火把 (`arena_torch`) 与 5 档角斗场背景 (`novice` ~ `darkest`)。
   - **真实 FMOD 音效与音乐**：通过 Java 26 FFM (`Foreign Function & Memory API`) 直连游戏自带 `fmod.dll`，按需解码 `.bank` (FSB5 / FADPCM / Vorbis) 为 WAV 并缓存，播放竞技场背景音乐、观众暴击/致死欢呼、马戏团长语音及全部 18 位英雄的技能音效。
   - **中英双语本地化**：StAX 直读 `arena.string_table.xml` 与本体字符串表，随时切换中文 / English。

2. **纯 Java 26 屠夫马戏团核心规则引擎（零 JS 游戏逻辑）**
   - **无速度骰的交替点将机制 + 掷币决定首回合先攻**（每回合先攻权交替）。
   - **晕眩 (Daze) vs 眩晕 (Stun)**：被 Daze 的英雄必须等其他未晕眩队友行动完毕后才可被选中；Stun 跳过回合并在恢复后获得 `+50%` 眩晕抗性。
   - **竞技场专属死门与心脏骤停**：基础抗死门 75%（最高 90%），支持技能/饰品的 `+致死率` 与 `-抗死门`；**流血/腐蚀 DoT 在竞技场不会触发致死判定**；压力达 100 触发美德/折磨检定（苦修者固定狂喜），达 200 触发心脏骤停（血量归零且压力回落至 170；若已处于死门则无视抗死门直接即死；美德英雄同样会触发心脏骤停）。
   - **完整 18 英雄 × 7 技能 (126 技能) + 全部 103 件竞技场饰品**：涵盖守护/破守护、反击、潜行/破潜行、标记联动、破甲、神盾格挡、尸体生成与清尸、咒缚者人兽变形、苦修者低血量技、小丑谢幕充能等机制。

3. **三种运行模式**
   - **本地双开 PvP 对战**：创建房间后一键点击「打开玩家 2 窗口」，双标签页独立选人、配技能、带饰品并实时双视角交替对战。
   - **单人对战 Bot & 实时 Bot vs Bot 观战**：内置 `CircusSearchBot`、`FortressBot`、`TacticalBot`、`StressSpecialistBot`、`RandomBot` 以及 10 套可配置队伍预设 (`team/*.json`)。
   - **纯无头大规模 Bot 锦标赛（多核并行）**：脱离 Web UI 高速对打，输出 ELO、胜率、致死/心梗击杀统计与可视化战报，并导出 JSON 录像在网页中动画回放。

---

## 环境要求

- **JDK 26**（需要 `javac` 与 `java` 在 `PATH` 中，用于编译与 FFM 调用 `fmod.dll`）
- **《暗黑地牢》(Darkest Dungeon) + 屠夫马戏团 DLC (`1117860_arena_mp`)**（推荐 Steam 版）
- 现代浏览器（Chrome / Edge / Firefox）

---

## 快速开始

### 1. 首次启动与游戏目录自动检测

直接运行启动脚本（如未编译会自动执行 `build.ps1`）：

```powershell
# Windows 双击或命令行运行
.\run_server.bat
# 或使用 PowerShell
.\run_server.ps1
```

启动时程序会按以下优先级自动定位《暗黑地牢》安装目录（**优先 Steam 位置**）：
1. 环境变量 `DD_GAME_DIR`（及可选 `DD_DLC_DIR`）
2. 本地配置文件 `local.properties`（已加入 `.gitignore`，不会提交到 Git）
3. 自动扫描本机 Steam 注册表与 `libraryfolders.vdf` 中的所有 Steam 库目录
4. **若首次启动仍未读取到游戏素材目录**，将自动弹出目录选择窗口（默认指向 Steam 安装目录），供你直接选择或填入 `DarkestDungeon` 根目录，并自动保存至 `local.properties`。

如需随时重新选择游戏目录或手动指定路径：
```powershell
.\run_server.ps1 --select-game-dir
.\run_server.ps1 --game-dir "D:\Program Files\Steam\steamapps\common\DarkestDungeon"
```

启动成功后打开浏览器访问：<http://127.0.0.1:8765>

### 2. 纯无头大规模 Bot 锦标赛（CLI）

```powershell
# 运行内置队伍循环赛（每对 30 局）并保存 2 份录像
.\run_tournament.bat --bots direct_kill,stun_kill,iron_wall --matches 30 --workers 4 --replays 2

# 完成后自动打开可视化锦标赛报告（需服务器运行中）
.\run_tournament.bat --bots random,tactical,stress --matches 40 --workers 4 --open
```

生成的录像保存在 `replays/`，锦标赛报告保存在 `tournaments/`，均可在网页大厅中一键查看与回放。

### 3. 手动编译与运行测试

```powershell
# 编译主程序、自定义 Bot 与测试套件
.\build.bat

# 运行完整测试套件
java --enable-native-access=ALL-UNNAMED -cp "java/out;java/out-test" com.circus.CircusTestSuite

# 运行性能与防御 Bot 专项回归测试
java -Xmx512m -cp "java/out;java/out-test" com.circus.FortressBotTest
java -Xmx512m -cp "java/out;java/out-test" com.circus.TournamentPerformanceTest
```

### 4. 编写自定义 Bot 与队伍

详见 [BOT_GUIDE.md](BOT_GUIDE.md)：
- 在 `bot/` 目录下编写自定义 Java Bot（运行 `.\build.bat` 自动编译），或通过 `ExternalSubprocessBot` 以 JSON-Lines 协议接入 Python / C++ / Rust / Go 等外部进程 AI。
- 在 `team/` 目录下以 JSON 配置队伍阵容、技能、饰品与绑定的 Bot 类。

---

## 目录结构

```text
.
├── bot/                 # 用户自定义 Java Bot 目录（build.ps1 自动编译）
├── team/                # 10 套预设队伍与 Bot 参数配置 (JSON)
├── java/
│   ├── src/com/circus/  # Java 26 核心引擎、Bot、资源解析、FMOD 音频与 Web/WS 服务器
│   ├── resources/       # 游戏静态规则数据 (game_data.json)
│   └── test/            # 单元测试、性能基准与防御策略评测
├── web/                 # 纯静态前端（HTML/CSS/JS + Canvas2D Spine 2.1 渲染器）
├── benchmarks/          # 性能优化与防御策略调优文档及基准数据
├── replays/             # 运行时生成的对局录像（JSON 已 gitignore）
├── tournaments/         # 运行时生成的锦标赛战报（JSON 已 gitignore）
├── build.ps1 / .bat     # 编译脚本
├── run_server.ps1 / .bat      # 启动 Web & PvP 服务器
├── run_tournament.ps1 / .bat  # 启动无头 Bot 锦标赛
├── BOT_GUIDE.md         # Bot 开发指南与引擎 API 文档
└── LICENSE              # MIT License
```
