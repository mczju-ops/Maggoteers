# Maggoteers · 区区恶敌？！

一个 Paper 服务器的 **4 人合作 PvE 小游戏**：清波、打 Boss、层层推进，波间三选一拿强化，越打越强——打不过就换 build。

游戏内中文名 **区区恶敌？！**（曾用名「卫戍协议」），游戏 id `maggoteers`。本插件是 [MCZJUGameCore](https://github.com/mczju-ops/MCZJUGameCore)（下称 MGC）的子插件：大厅、房间、组队、玩家档案、排行榜、成就都由 MGC 负责，本插件只实现玩法本身。

> 当前版本：**1.4.0**（Paper 26.2 / Java 25 / MGC 1.5.0）

- 一局 1–4 人，默认三层（`act1 → act2 → act3`），每层清完「弱怪波 × M + 强怪波 × N + Boss × 1」后进入下一层；每层从该层的地图池里随机抽一张图。
- 每波之间有 **60 秒休整**，花局内货币抽 **3 选 1** 强化（属性 / 武器 / 补给 / 职业）。
- 三层通关即胜利；在第三层 Boss 后的终局休整里还能用一枚 **星石** 换「深渊之钥」，开启隐藏结局层。
- **图鉴**（`/maggoteers codex`）：武器 / 藏品 / Boss / 词条四本，按「未解锁 / 还没遇见 / 已获得」三态展示。
- **成就**（`/maggoteers achievements`）：18 条，其中 6 条传说级有专属解锁演出。
- 失败不踢人：全员倒下会保留房间继续观战，直到所有人 `/mgc leave` 才结算。

---

## 1. 环境要求

| 项 | 要求 |
|---|---|
| 服务端 | **Paper 26.2**（`api-version: 26.2`），其他版本未做兼容 |
| Java | **Java 25**（编译与运行） |
| 硬依赖 | **MCZJUGameCore 1.5.0**（缺少则不加载） |
| 推荐依赖 | **MCZJUItemCreator 1.1.0**（软依赖；缺失只告警，但货币、武器、护符等物品会缺失） |
| 客户端 | 原版客户端即可，无需资源包或模组 |

> MGC 的版本基线是 **1.5.0**（成就系统由它承载）。本插件按该版本的 API 编译，装 1.1.2 或更旧会出现类/方法找不到，启动直接失败。

---

## 2. 安装

1. 先把 **MCZJUGameCore 1.5.0** 与 **MCZJUItemCreator 1.1.0** 放进 `plugins/`，先启动一次服务器，确认大厅能正常初始化。
2. 把 **`Maggoteers-1.4.0.jar`** 放进 `plugins/`。
3. **完整重启服务器**（`/reload` 或 PlugMan 之类重载插件不够）。
4. 首次启动会在插件目录生成默认内容：

```text
plugins/Maggoteers/
├─ config.yml              # 层/难度/缩放/结算/文案
├─ waves.yml               # 波次池与刷怪策略
├─ mob_skills.yml          # 怪物技能（触发 + 效果）
├─ rewards.yml             # 奖励池（3 选 1 / 职业）
├─ collectibles.yml        # 藏品与护符映射
├─ codex.yml               # 图鉴内容（页序 / Boss 文案 / 词条白名单）
├─ achievements.yml        # 成就定义（18 条）
├─ items/                  # ItemCreator 物品定义
└─ maps/act1..act4/<map>/  # 地图（structure.nbt + points.yml + special_waves.yml）
```

5. 同时会在 MGC 目录写出房间定义 `plugins/MCZJUGameCore/rooms/maggoteers/default.json`——**没有它 `/mgc join maggoteers` 会提示无空闲房间**。如果被删了，重启本插件会自动补回，也可以手动 `/mgcop room create maggoteers default`。
6. 建议放开血量上限（Boss 血量会超过原版 1024 的钳制）：在 `spigot.yml` 里确认

   ```yaml
   settings:
     attribute:
       maxHealth:
         max: 2048   # 或更大；改完必须重启
   ```

   游戏内可用 `/maggoteers debug hp` 自检（会生成一只临时蝙蝠做上限探针）。

7. 启动日志出现下面这些就说明加载成功：

```text
[Maggoteers] Maggoteers (区区恶敌？！) enabled, game 'maggoteers' registered.
[Maggoteers] WavesConfig: ... strategies
[Maggoteers] MobSkillRegistry: loaded ... skills
[MCZJUGameCore] ... 加载房间 ...
```

如果看到 `rewards.yml 池 ... 已跳过`、`use_ability ... skipped`、`mob_skills ... unknown` 之类的告警，说明配置被改坏了，按行号修好再 `/maggoteers reload`。

---

## 3. 开一局

1. 玩家通过大厅加入：`/mgc join maggoteers`（或服务器大厅里的对应入口）。
2. 等待阶段**房主**可以选难度（默认普通）：

   ```text
   /maggoteers difficulty normal|hard|expert
   ```

3. 人数满足后由 MGC 开局（具体开局入口以你大厅的配置为准）。开局先选**职业**：每人从职业池里随机抽 3 个，选 1；45 秒不选会随机兜底。
4. 每层流程：准备倒计时 → 刷怪 → 清空 → 60 秒休整（可买东西 / 投票跳过）→ 下一波 → 本层 Boss → 进下一层。
5. 打完第三层 Boss → 终局休整 → 结算通关。中途全员倒下会进入失败观战，全员 `/mgc leave` 后才出结算。

**离开对局**一律用 `/mgc leave`；单人掉线只会把该玩家标记为倒下，其余人继续打。

---

## 4. 玩法说明

### 4.1 波次构成

每层的波数由 `config.yml` 的 `waves_per_act` 决定，总波数 = **弱怪波 M + 强怪波 N + Boss 波 1**：

| 层 | 弱怪 | 强怪 | Boss | 合计 |
|---|---|---|---|---|
| `act1` | 3 | 3 | 1 | 7 波 |
| `act2` | 3 | 3 | 1 | 7 波 |
| `act3` | 1 | 4 | 1 | 6 波 |
| `act4`（隐藏层） | — | — | 1 | 1 场 Boss 战 |

每波从一个「刷怪策略池」里按权重抽一条策略执行，策略里可以分多批、多个刷怪点、带延迟地出怪。地图自带专属波次，权重比通用波次更高。

### 4.2 怪物强度

> **怪物最终数值 = `waves.yml` 的 `coeff` × 人数缩放 `scaling` × 难度倍率**

- 人数缩放（`config.yml scaling`）：1–4 人分别对应 HP / 伤害 / 数量三组数组。
- 难度倍率（`config.yml difficulty`）：`normal` 恒等，`hard` / `expert` 在人数缩放之上再乘。
- 怪物技能写在 `mob_skills.yml`，可按难度过滤（`difficulty: hard` 表示只在困难及以上挂）。

### 4.3 货币

| 货币 | 外观 | 来源 | 用途 |
|---|---|---|---|
| 金币（普通货币） | 金粒 | 普通波清场奖励 | 普通 3 选 1（每次 1 枚） |
| 星石（Boss 货币） | 下界之星 | Boss 波掉落 | Boss 3 选 1（每次 1 枚）、买深渊之钥 |
| 点数（账户货币） | 持久化数字 | 每局结算 | 局外解锁商店（`/maggoteers shop`） |

局内货币是**每人独立、每局重置**的；点数跨局保留，用于永久解锁新的奖励项。

### 4.4 休整与 3 选 1

- 每波清空后进入 60 秒休整，**右键手持的金币/星石**（或休整菜单里的按钮）打开升级菜单。
- 菜单里花 1 枚对应货币抽 3 个选项，选 1 应用，可以连着抽到货币花完。
- 抽池跟着「刚清掉的那一波」走：弱怪波 → 本层弱池，强怪波 / Boss 波 → 本层强池；Boss 池是粘滞的（杀掉本层 Boss 后才更新到本层）。
- 补给可重复拿；武器、大部分属性每种只能拿一次；可升级的属性（如抗性）能重复拿到满级。
- 属性类奖励会同时给一个**护符**（追溯指针等底材），方便在背包里看到自己拿了什么。
- 休整**不会自动弹菜单**，只会在聊天栏提示；战斗中也可以打开菜单看数值。
- 全员点「跳过休整」可以立即开下一波。

### 4.5 职业

开局每人免费 3 选 1，共 6 个方向：**先锋 / 法师 / 重装 / 狙击 / 守卫 / 医疗**。职业决定开局武器与几个专属属性加成；职业池在 `rewards.yml` 的 `class`，加职业只需加配置。

### 4.6 死亡与复活

- 开局默认 **2 次自动复活**（`config.yml lives.default`）；还有次数时死亡会原地复活。
- 次数用完后转观察者，仍占着座位，可以被队友用**复活币**救回来。
- 复活币只从 Boss 池产出：右键打开玩家头像菜单，对倒下的玩家用 = 复活，对活着的玩家用 = 多给 1 次复活机会。
- **全员倒下 = 本局失败**：停止刷怪、清场、保留房间观战；等所有人 `/mgc leave` 才结算失败点数。

### 4.7 难度

| 难度 | 怪物 HP / 伤害 / 数量 | 额外内容 | 结算倍率 |
|---|---|---|---|
| `normal` | 1.0 / 1.0 / 1.0 | — | 1.0 |
| `hard` | 1.2 / 1.15 / 1.1 | 第 2 层起发诅咒 | 1.25 |
| `expert` | 1.3 / 1.25 / 1.2 | 每层都可能发诅咒 | 1.5 |

诅咒是负面奖励（会出现在 3 选 1 里，需要权衡取舍），具体条目在 `rewards.yml` 的 `curse_*` 池。

### 4.8 隐藏结局层

第三层 Boss 清掉后进入**终局休整**：

- 休整商店里会刷出「深渊之钥」（消耗 1 枚星石）。买下后**右键使用** → 传送进隐藏层 `act4`，单场 Boss 战。
- 不用钥匙 → 休整结束直接通关。
- 隐藏层打完后按通关结算。

### 4.9 结算与排行榜

- 通关：`settlement.win_flat`（默认 100 点）。
- 失败：`fail_per_act × 已过层数 + fail_per_wave × 本层已过波数`（默认 25 / 1），再乘当局难度倍率。
- 两个排行榜（展示实体用 `/mgcop leaderboard create <id> <实体名>` 放置）：
  - `maggoteers_total` —— 累计获得点数（积分榜）
  - `maggoteers_time` —— 最佳通关时间（用时越短越靠前）

### 4.10 图鉴

`/maggoteers codex`（别名 `/maggoteers index`）打开图鉴，四本书：

| 书 | 内容 | 状态 |
|---|---|---|
| 武器 | 按奖励池分页，每页 21 件 | ①需解锁且未解锁 → 灰混凝土「？？？」；②已解锁但没拿到过 → 未附魔形态 +「还没遇见过」；③拿到过 → 原样（附魔、光效） |
| 藏品 | 同上，只收**有护符映射**的属性类藏品 | 同上 |
| Boss | 按层列出，每只带血条的 Boss 一个刷怪蛋 | 见过 → 显示玩法 / 风味 / 最终血量；没见过 → 灰混凝土「？？？」。**隐藏层没进过时整行不画** |
| 词条 | `codex.yml` 白名单里的怪物技能 | 名字 / 图标 / 说明全在配置里 |

- 「遇到 Boss」的判定是**Boss 本体刷出来的那一刻**，不是波次开始——不然 boss 波前面的杂兵会提前剧透。
- 图鉴进度是**账号级持久化**（和点数存在一起），换对局不丢。
- 加页 / 加 Boss 文案 / 加词条 = 改 `codex.yml`，不用写代码。

### 4.11 成就

`/maggoteers achievements` 打开成就界面（MGC 成就系统，带 `gameId`）。

- 共 **18 条**，覆盖通关、难度、单人通关、花费、救助、解锁、隐藏 Boss 等口径。
- 名字和描述是**纯文本**（MGC 的成就界面不解析 MiniMessage）；成就**不隐藏**，"隐藏成就"靠把描述写得模糊。
- 其中 **6 条传说级**：解锁时会放音乐 + 挑战完成音效 + 屏幕大字，并且只向本局参与者播报。
- 跨局进度由 MGC 存档（如通关次数直接读 MGC 的 `WINS` 统计），本插件只负责打点时记一笔。
- 加成就 = 改 `achievements.yml`；但判定规则（`rule`）是代码里的固定枚举，想要新口径才需要写 Java。

---

## 5. 命令与权限

| 命令 | 说明 | 权限 |
|---|---|---|
| `/mgc join maggoteers` | 通过 MGC 加入对局 | `maggoteers.play` |
| `/mgc leave` | 离开对局（掉线/主动退出都走这条链） | `maggoteers.play` |
| `/maggoteers shop` | 打开局外解锁商店 | `maggoteers.play` |
| `/maggoteers codex` | 打开图鉴（武器 / 藏品 / Boss / 词条） | `maggoteers.play` |
| `/maggoteers achievements` | 打开成就界面 | `maggoteers.play` |
| `/maggoteers difficulty [normal\|hard\|expert]` | 查看/设置当局难度（房主、等待阶段） | `maggoteers.play` |
| `/maggoteers time` | 查看个人最佳通关时间与全服前十 | `maggoteers.play` |
| `/maggoteers reload` | 热重载配置（进行中的对局剧本不变） | `maggoteers.admin` |
| `/maggoteers debug ...` | 调试（换波 / 跳层 / 发奖励 / 发货币 / 生成怪 / 查属性…） | `maggoteers.admin`；`config.yml` 里 `debug.enabled: true` 时任意玩家可用 |

权限节点：

- `maggoteers.play` —— 默认所有人
- `maggoteers.admin` —— 默认 OP

常用 debug 子命令（`/maggoteers debug <子命令>`，全部支持 Tab 补全）：

```text
state | plan | wave [strategyId] | act | give <奖励/物品 id> [数量]
coin [normal|boss] [数量] | balance [点数] | unlock <id|all>
jumpto <层> <波> | jump <±n> | spawnmob <实体> [技能...]
effects [玩家] | counter [玩家] | boss | hp
codex [reset] | achievements [unlock <id>]
aura | freeze [秒]          # 探针：需要 config.yml 里 debug.probe: true
```

---

## 6. 目录与配置

### 6.1 运行时目录

```text
plugins/
├─ Maggoteers/
│  ├─ config.yml
│  ├─ waves.yml
│  ├─ mob_skills.yml
│  ├─ rewards.yml
│  ├─ collectibles.yml
│  ├─ codex.yml
│  ├─ achievements.yml
│  ├─ items/
│  │  ├─ maggoteers.yml
│  │  └─ collectibles.yml
│  └─ maps/
│     ├─ act1/{desert,jungle}/
│     ├─ act2/{badlands,graveyard}/
│     ├─ act3/{sky5,giantcity6}/
│     └─ act4/guardcity6/
├─ MCZJUGameCore/
│  ├─ rooms/maggoteers/default.json      # 房间定义（首次启动自动生成）
│  └─ player_data/maggoteers/<uuid>.json # 点数/解锁/最佳时间/图鉴/成就进度（30 分钟自动保存）
├─ MCZJUGameCore-1.5.0.jar
├─ MCZJUItemCreator-1.1.0.jar
└─ Maggoteers-1.4.0.jar
```

每张地图一个目录：

```text
maps/actN/<mapId>/
├─ structure.nbt     # 结构方块导出的整图，左下前角对齐层原点
├─ points.yml        # playerSpawn + spawnPoints（全部是相对坐标）
└─ special_waves.yml # 本图专属波次（可留空）
```

> jar 里自带的默认地图 / 物品只在**文件缺失时**释放，不会覆盖你在 `maps/`、`items/` 里改过的文件——可以放心换成自己的图。

### 6.2 `config.yml` 关键项

| 段 | 作用 |
|---|---|
| `wait.min_players` / 人数上限 | 最少几人开局；上限固定 4 |
| `lives.default` | 每人初始复活次数 |
| `acts` | **层的唯一真相源**：顺序即推进顺序，加层只改这里 + 下面几段 |
| `hidden_act` / `hidden_gate_after` | 隐藏层 id 与开放时机（留空 = 无隐藏层） |
| `act_origins` | 每层粘贴地图的原点（绝对坐标只写在这里） |
| `waves_per_act` | 每层弱怪/强怪波数 |
| `rest.duration_sec` | 休整时长 |
| `scaling` | 1–4 人的 HP / 伤害 / 数量缩放 |
| `difficulty.multipliers` | 三档难度在人数缩放之上的倍率 |
| `settlement` | 通关/失败结算点数与难度倍率 |
| `act_rules` | 层特例：恒抽强池 / 提前切 Boss 池 / 各难度诅咒层 |
| `mob_rules` | 本局怪物行为规则（禁蜘蛛爬墙、禁潜影贝传送） |
| `initial_equipment` | 开局全员发放的物品 |
| `class_select.timeout_sec` | 职业选择超时 |
| `world.time_lock` / `player.night_vision` | 局内时间锁定与夜视 |
| `act_enter.prep_sec` | 每层开打前的准备倒计时 |
| `ambience` | 进层/开波/Boss/通关的音效与大字标题 |
| `debug.probe` | 调试探针（`aura` / `freeze` 等逐条日志），默认关 |
| `messages` | 全部玩家面文案（MiniMessage），含图鉴与成就界面文案 |

> 图鉴的**内容**在 `codex.yml`（页序 / Boss 文案 / 词条白名单），成就的**内容**在 `achievements.yml`（18 条定义）；
> 两者都支持热重载，只有界面文案在 `config.yml`。

> **绝对坐标永远不写进配置**：地图内一律用相对坐标，运行时用「层原点 + 相对值」现算。

---

## 7. 自定义内容（绝大多数零代码）

| 想加… | 改哪里 |
|---|---|
| 新地图 | `maps/actN/<mapId>/` 放 `structure.nbt` + `points.yml`（+ `special_waves.yml`），并把该层 id 写进 `config.yml acts`、`act_origins`、`waves_per_act`，同时在 `waves.yml` 建对应 `pools.<层id>` |
| 新波次 | `waves.yml` 加一条 `strategies`，再挂进 `pools` |
| 新怪物技能 | `mob_skills.yml` 加一条技能，`waves.yml` 里按 id 引用 |
| 新奖励 / 新职业 | `rewards.yml` 加一个 option（武器/补给需要在 `items/` 里有对应 ItemCreator 物品） |
| 新图鉴页 / Boss 文案 / 词条 | `codex.yml` 的 `pages` / `bosses` / `affixes` |
| 新成就 | `achievements.yml` 加一段（`rule` 必须是已实现的规则；想要新判定口径才需要写 Java） |
| 新触发类型 / 新 Effect 类型 | 需要改 Java（`effect/Trigger`、`effect/Effect` + `EffectService`，有模板） |

刷怪策略示例（`waves.yml`）：

```yaml
strategies:
  w_zombie_swarm:
    repeat: 2
    clearReward: [ { item: maggoteers:currency_normal, amount: 2 } ]
    steps:
      - { point: 1, type: ZOMBIE, count: 5, coeff: { hp: 1.0 }, skills: [], delay: 0 }
      - { point: 9, type: ZOMBIE, count: 5, coeff: { hp: 1.0 }, delay: 5 }
```

奖励项示例（`rewards.yml`）：

```yaml
reward_pools:
  act1_weak:
    cost: 1
    options:
      - { id: dmg10, category: STAT, effect: ADD_ATTRIBUTE,
          params: { attr: ATTACK_DAMAGE, op: PERCENT, value: 0.10 },
          unique: true, display: "<red>+10% 伤害" }
      - { id: wrench, category: WEAPON, item: "maggoteers:wrench",
          requires_unlock: true, unlock_cost: 30, display: "<gold>扳手" }
```

改完配置用 `/maggoteers reload` 生效；**进行中的对局不会换剧本**，新开局才用新配置。

---

## 8. 构建

需要 **JDK 25** 与 **Maven 3.9+**：

```bash
mvn package
# 产物：target/Maggoteers-1.4.0.jar（provided 依赖不会打进 jar）
```

依赖来源：

- `io.papermc.paper:paper-api:26.2.build.62-beta`（Paper 仓库）
- `com.github.mczju-ops:MCZJUGameCore:1.5.0`（jitpack）
- `io.mczju:MCZJUItemCreator:1.1.0`（线上的 Maven 仓库没有这个坐标，需要先把它装进本地仓库）

`MCZJUItemCreator` 的装法（拿到 `MCZJUItemCreator-1.1.0.jar` 后二选一）：

```bash
# 方式一：install-file
mvn install:install-file -Dfile=MCZJUItemCreator-1.1.0.jar \
  -DgroupId=io.mczju -DartifactId=MCZJUItemCreator -Dversion=1.1.0 -Dpackaging=jar

# 方式二：直接放进本地仓库
#   ~/.m2/repository/io/mczju/MCZJUItemCreator/1.1.0/
#     ├─ MCZJUItemCreator-1.1.0.jar
#     └─ MCZJUItemCreator-1.1.0.pom
```

> 本仓库是**发布仓库**：只包含编译运行所需的源码与资源，不包含测试、设计文档和地图编辑器。
> 开发过程文档（设计定稿、开发日志、决策记录）、全量测试用例与波次编辑器都在开发仓库里。

---

## 9. 注意事项

- **必须装 MCZJUGameCore 1.5.0**：成就系统由 MGC 承载，本插件按 1.5.0 的 API 编译；旧版 MGC 会启动失败。
- **只能用完整重启加载本插件**。`/reload` / 热重载插件会让 MGC 的注册表与菜单状态错位。
- **每局会新建一个虚空世界**（`maggoteers_<seed>`），结束/失败结算时卸载并删除；服务器突然崩溃后，下次启动会自动清理遗留的 `maggoteers_*` 世界。
- 进行中对局的人数缩放是**开局锁定**的，中途掉线不会重算；也不允许中途加入。
- 玩家点数是持久化数据，落在 **MGC 目录**（`player_data/maggoteers/<uuid>.json`），每 30 分钟 + 退出时保存；清档要连这里一起清。
- Boss 血量可能超过 1024，务必确认 `spigot.yml` 的 `attribute.maxHealth.max` 已放开（见 §2）。
- 正式开服前保持 `config.yml` 的 `debug.enabled: false`，否则有 `maggoteers.play` 的玩家都能用调试命令。

---

## 10. 相关仓库与致谢

- 依赖框架：[MCZJUGameCore](https://github.com/mczju-ops/MCZJUGameCore) —— 大厅、房间、玩家数据、菜单、排行榜
- 物品系统：MCZJUItemCreator 1.1.0
- 服务端 API：[Paper](https://papermc.io/)
- 开发仓库（设计文档 / 开发日志 / 全量测试 / 波次编辑器）：[MayaToyama/MCZJUMaggoteers](https://github.com/MayaToyama/MCZJUMaggoteers)

安装、配置或平衡性上有问题，直接开 issue；附上服务器版本、MGC 版本和 `logs/latest.log` 里相关的报错行会快很多。
