package io.mczju.maggoteers.game;

import com.github.mczjuops.mczjugamecore.MCZJUGameCore;
import com.github.mczjuops.mczjugamecore.game.AbstractGame;
import com.github.mczjuops.mczjugamecore.game.GameMeta;
import com.github.mczjuops.mczjugamecore.game.GameState;
import com.github.mczjuops.mczjugamecore.game.strategy.wait.DefaultGameWaitStrategy;
import com.github.mczjuops.mczjugamecore.game.strategy.wait.GameWaitStrategy;
import com.github.mczjuops.mczjugamecore.menu.MenuFacade;
import com.github.mczjuops.mczjugamecore.player.PlayerExt;
import com.github.mczjuops.mczjugamecore.player.party.Party;
import com.github.mczjuops.mczjugamecore.player.strategy.AbstractPlayerDeathStrategy;
import com.github.mczjuops.mczjugamecore.player.strategy.AbstractPlayerQuitStrategy;
import io.mczju.maggoteers.MaggoteersPlugin;
import io.mczju.maggoteers.config.MessageService;
import io.mczju.maggoteers.item.ItemKind;
import io.mczju.maggoteers.item.ItemService;
import io.mczju.maggoteers.plan.ActPlan;
import io.mczju.maggoteers.plan.RunPlanner;
import io.mczju.maggoteers.state.PlayerStateManager;
import io.mczju.maggoteers.ui.RunScoreboard;
import io.mczju.maggoteers.util.GameDifficulty;
import io.mczju.maggoteers.wave.WaveScheduler;
import io.mczju.maggoteers.world.ActSpawnHelper;
import io.mczju.maggoteers.world.MapRepository;
import io.mczju.maggoteers.world.WorldService;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import io.mczju.maggoteers.effect.Effect;
import io.mczju.maggoteers.effect.EffectContext;
import io.mczju.maggoteers.effect.EffectKeys;
import io.mczju.maggoteers.effect.EffectService;
import io.mczju.maggoteers.effect.PlayerEffect;
import io.mczju.maggoteers.effect.Stack;
import org.bukkit.entity.Player;
import org.bukkit.attribute.Attribute;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

public class MaggoteersGame extends AbstractGame {

    private volatile boolean planReady = false;
    private volatile List<ActPlan> plannedActs;
    private volatile String planError;
    /** 本局难度（1.3.3）：房主在等待阶段选择，开局规划时读取。默认普通。 */
    private volatile GameDifficulty difficulty = GameDifficulty.NORMAL;
    /** 剧本代次：重规划时旧异步结果不得覆盖新结果（gen == planGen 才落盘）。 */
    private volatile int planGen = 0;

    private GameOutcome outcome = GameOutcome.IN_PROGRESS;
    private volatile boolean wavesStarted = false;
    /** 本局玩家受伤倍率（人数 × 难度 的 mob_damage 系数；1.0 = 不变）。由 onAllClassesChosen 快照写入。 */
    private volatile double damageTakenMultiplier = 1.0;
    /** 本局是否已走完 cleanupRun（幂等 + 异步/延迟启动任务的存活信号）。 */
    private volatile boolean cleanedUp = false;
    /**
     * 本局参与玩家 UUID（开局时记录）。结算遍历用——失败结算在「全员 leave 后」才触发（finishGame），
     * 此刻 getPlayers() 已被 leaveGame 清空，遍历它会把中途死亡/退出的玩家全漏掉、积分发不出去。
     */
    private final List<UUID> runPlayerUuids = new ArrayList<>();
    /** 玩家入局时的落点（大厅位置）。房间未配 return/wait 时作回程兜底（R：abort/清理后不再落主世界）。 */
    private final Map<UUID, Location> joinLocations = new HashMap<>();
    /** 对局计时起点：全员选职业完毕（onAllClassesChosen）。0=未开始。 */
    private long runStartEpochMs = 0;
    /** 对局计时终点：最终 Act3 Boss 波清完发奖杯瞬间。0=未走到。 */
    private long runEndEpochMs = 0;

    @Override public String getId() { return "maggoteers"; }

    @Override
    public GameMeta getGameMeta() {
        return GameMeta.builder()
                .displayName(MessageService.raw("game.meta_name", Map.of()))
                .icon(Material.NETHERITE_SWORD)
                .author(MessageService.raw("game.meta_author", Map.of()))
                .description(MessageService.rawList("game.meta_description"))
                .build();
    }

    @Override
    public GameWaitStrategy getGameWaitStrategy() {
        int min = MaggoteersPlugin.getInstance().getConfig().getInt("wait.min_players", 1);
        return new DefaultGameWaitStrategy(this, 4, min) {
            /** 清掉上局残留的 maggoteers profile 物品（joinGame 已把陈旧快照 apply 到身上）。 */
            @Override
            public boolean onPlayerJoin(PlayerExt player) {
                // 2026-10-28（安全）：只清本插件 PDC 物品，不再直接 clear() 整个背包（避免 MGC 顺序变化时清掉大厅物品）
                io.mczju.maggoteers.item.ItemService.clearRunItems(player.player());
                boolean ok = super.onPlayerJoin(player);
                if (ok) {
                    joinLocations.put(player.player().getUniqueId(), player.player().getLocation());
                    MaggoteersRoom.teleportWait(MaggoteersGame.this, player.player());
                }
                return ok;
            }
            @Override
            public boolean onPartyJoin(Party party) {
                party.getAllPlayer().forEach(p -> io.mczju.maggoteers.item.ItemService.clearRunItems(p.player()));
                boolean ok = super.onPartyJoin(party);
                if (ok) {
                    party.getAllPlayer().forEach(p -> {
                        joinLocations.put(p.player().getUniqueId(), p.player().getLocation());
                        MaggoteersRoom.teleportWait(MaggoteersGame.this, p.player());
                    });
                }
                return ok;
            }
        };
    }

    @Override
    public AbstractPlayerDeathStrategy getPlayerDeathStrategy() {
        return new MaggoteersDeathStrategy(this);
    }

    @Override
    public AbstractPlayerQuitStrategy getPlayerQuitStrategy() {
        return new MaggoteersPlayerQuitStrategy(this);
    }

    public GameOutcome getOutcome() { return outcome; }
    public void setOutcome(GameOutcome o) { this.outcome = o; }

    /** 开局选职业阶段（已进图、波次未开）。 */
    public boolean isInClassSelectPhase() {
        return outcome == GameOutcome.IN_PROGRESS && plannedActs != null && !wavesStarted;
    }

    /** 调试用：本局预生成剧本（波次未开或进行中均可读）。 */
    public List<ActPlan> getPlannedActs() { return plannedActs; }

    /** 开局锁定的人数快照（缩放开局锁定，D8）；调试换波复算缩放时要用同一个值。 */
    public int getRunPlayerCount() { return Math.max(1, runPlayerUuids.size()); }

    /** 开局玩家快照（结算与成就判定用；中途退出的人也在里面）。 */
    public java.util.List<UUID> getRunPlayerUuids() {
        return java.util.List.copyOf(runPlayerUuids);
    }

    /** 通关用时（毫秒）；计时不完整时返回 0。口径：职业选完 → 终局奖杯。 */
    public long runElapsedMs() {
        return (runStartEpochMs > 0 && runEndEpochMs > 0) ? runEndEpochMs - runStartEpochMs : 0L;
    }

    /** 本局难度（1.3.3）。 */
    public GameDifficulty getDifficulty() { return difficulty; }

    /** 本局玩家受伤倍率（1.0 = 不变；≥1 表示更难）。 */
    public double getDamageTakenMultiplier() { return damageTakenMultiplier; }

    /** 等待阶段房主设置难度（1.3.3）。规划尚未开始；防御分支处理已生成剧本时重规划。 */
    public void setWaitingDifficulty(GameDifficulty d) {
        if (d == null) return;
        this.difficulty = d;
        if (getState() == GameState.WAITING && plannedActs != null) {
            planReady = false;
            plannedActs = null;
            triggerPlanAsync();
        }
    }

    /** 玩家入局前的落点（大厅位置）；房间未配 return/wait 时作回程兜底。可空（如未记录）。 */
    public Location playerReturnLocation(UUID uuid) { return joinLocations.get(uuid); }

    /** 计时终点：最终 Act3 Boss 波清完发奖杯瞬间（WaveScheduler.onWaveCleared 调用）。幂等。 */
    public void markTrophyReceived() {
        if (runEndEpochMs == 0) runEndEpochMs = System.currentTimeMillis();
    }

    public void win() {
        if (cleanedUp || outcome != GameOutcome.IN_PROGRESS) return;
        outcome = GameOutcome.WIN;
        // 成就：通关类判定必须在这里（endGame 之后会切档案、清局内状态，属性/背包货币就都没了）
        try {
            io.mczju.maggoteers.achievement.AchievementService.onRunWin(this);
        } catch (RuntimeException ex) {
            MaggoteersPlugin.getInstance().getLogger().warning("成就通关判定异常：" + ex.getMessage());
        }
        MaggoteersPlugin.getInstance().getLogger().info("对局胜利 endGame identity="
                + System.identityHashCode(this));
        MCZJUGameCore.getGameManager().endGame(this);
    }

    public void fail() {
        if (cleanedUp || outcome != GameOutcome.IN_PROGRESS) return;
        outcome = GameOutcome.FAIL;
        MaggoteersPlugin.getInstance().getLogger().info("对局失败 endGame identity="
                + System.identityHashCode(this));
        MCZJUGameCore.getGameManager().endGame(this);
    }

    /** 全员倒下：标记失败 + 停波清怪，但保留房间观战；真正结束等全员 leave（{@link #finishGame()}）。 */
    public void markDefeated() {
        if (cleanedUp || outcome != GameOutcome.IN_PROGRESS) return;
        outcome = GameOutcome.FAIL;
        MaggoteersPlugin.getInstance().getLogger().info("对局失败(观战) markDefeated identity="
                + System.identityHashCode(this));
        WaveScheduler.defeat(this);
        sender().warn(MessageService.raw("game.defeat", Map.of()));
    }

    /** 全员 leave 后真正结束（中途全员退出视为失败）。 */
    public void finishGame() {
        if (cleanedUp) return;
        if (outcome == GameOutcome.IN_PROGRESS) {
            outcome = GameOutcome.FAIL;
        }
        MaggoteersPlugin.getInstance().getLogger().info("对局结束 finishGame identity="
                + System.identityHashCode(this));
        MCZJUGameCore.getGameManager().endGame(this);
    }

    @Override
    protected boolean onGameInit() {
        planReady = false;
        wavesStarted = false;
        runPlayerUuids.clear();   // 新局重置（防上一局快照串进本局结算）
        return true;
    }

    @Override
    protected void onGameStart() {
        runWhenReady(this::startInWorld);
    }

    private void startInWorld() {
        if (cleanedUp) return;   // 开局前已被取消/中止：勿再建世界
        World w = WorldService.create(this);
        if (w == null) {
            sender().error(MessageService.raw("game.world_create_failed", Map.of()));
            fail();
            return;
        }

        int lives = MaggoteersPlugin.getInstance().getConfig().getInt("lives.default", 2);
        ClassSelectGate.reset(this);
        for (PlayerExt pe : getPlayers()) {
            runPlayerUuids.add(pe.player().getUniqueId());   // 结算快照：开局玩家全量记录
            // ⚠️ 必须先 switchProfile 再清空（bug #1 跨局残留根因）：
            //  switchProfile 的 capture 捕获「切换前」的 lobby 存档（若先 clear 会清空 lobby 永久污染大厅物品），
            //  然后 apply 载入 maggoteers 快照——该快照对中途退出的玩家保留着退局时刻的物品栏/末影箱
            //  （含护符/货币/武器）。故在 switchProfile 之后 clear 主物品栏 + 末影箱，清掉上局残留再发本局装备。
            pe.switchProfile(this.getId());
            pe.player().getInventory().clear();
            pe.player().getEnderChest().clear();
            pe.player().setGameMode(GameMode.ADVENTURE);
            // 剥掉别处遗留在玩家实体上的「无敌」标记（Invulnerable=1 会让所有怪物都不把他当目标，
            // 且跨重启持久化——2026-10-26 实测被 InfernalClash 的索敌排除留下）
            PlayerStateManager.clearStaleInvulnerable(pe.player());
            PlayerStateManager.init(this, pe.player().getUniqueId(), lives);
            PlayerStateManager.applyFallSafety(pe.player());   // 局内摔落免疫（安全高度 40 格 + 倍率 0）
        }

        triggerPlanAsync();
        preloadAllStructures();
        new BukkitRunnable() {
            int ticks = 0;
            @Override public void run() {
                if (cleanedUp || outcome != GameOutcome.IN_PROGRESS) { cancel(); return; }
                // 剧本就绪 + Act1 结构已异步解析完 → 才进图（把 loadStructure 的开销挪出主线程）
                if (planReady && (act1StructureReady() || ticks > 200)) {
                    cancel();
                    beginClassSelect();
                } else if (++ticks > 20 * 30) {
                    cancel();
                    sender().warn(MessageService.raw("game.plan_timeout", Map.of()));
                    fail();
                }
            }
        }.runTaskTimer(MaggoteersPlugin.getInstance(), 1L, 1L);
    }

    /** 异步预解析全部候选地图的 structure.nbt（只有 5 个文件，几十 ms，开局前完成）。 */
    private void preloadAllStructures() {
        for (String act : MaggoteersPlugin.getInstance().actIds()) {
            for (var entry : MapRepository.getMaps(act)) {
                java.io.File nbt = new java.io.File(entry.dir(), "structure.nbt");
                if (nbt.isFile()) io.mczju.maggoteers.world.StructureCache.preload(nbt);
            }
        }
    }

    /** Act1 选中地图的结构是否已解析完（缺文件视为就绪 → 走玻璃平台兜底）。 */
    private boolean act1StructureReady() {
        if (plannedActs == null || plannedActs.isEmpty()) return false;
        var act1 = plannedActs.get(0);
        for (var entry : MapRepository.getMaps(act1.actId())) {
            if (!entry.mapId().equals(act1.mapId())) continue;
            java.io.File nbt = new java.io.File(entry.dir(), "structure.nbt");
            return io.mczju.maggoteers.world.StructureCache.isReady(nbt);
        }
        return true;
    }

    /** 异步生成剧本（1.3.3）：难度在等待阶段已由房主设定，此处快照进线程。planGen 防旧结果覆盖新结果。 */
    private void triggerPlanAsync() {
        final long seed = WorldService.getSeed(this);
        final int players = getPlayers().size();
        final GameDifficulty diff = this.difficulty;
        final int gen = ++planGen;
        planReady = false;
        sender().info(MessageService.raw("game.plan_generating", Map.of()));
        Bukkit.getScheduler().runTaskAsynchronously(MaggoteersPlugin.getInstance(), () -> {
            try {
                List<ActPlan> acts = RunPlanner.plan(seed, players, diff);
                if (gen == planGen) plannedActs = acts;
                // 计划确定后立刻预解析这三个地图的结构（异步；Act2/3 进层时直接命中缓存）
                if (acts != null) {
                    for (ActPlan act : acts) {
                        for (var entry : MapRepository.getMaps(act.actId())) {
                            if (!entry.mapId().equals(act.mapId())) continue;
                            java.io.File nbt = new java.io.File(entry.dir(), "structure.nbt");
                            if (nbt.isFile()) io.mczju.maggoteers.world.StructureCache.preload(nbt);
                        }
                    }
                }
            } catch (Exception e) {
                if (gen == planGen) {
                    planError = e.getMessage();
                    MaggoteersPlugin.getInstance().getLogger().severe("RunPlanner 失败：" + e.getMessage());
                }
            } finally {
                if (gen == planGen) planReady = true;
            }
        });
    }

    /** 进图 → 发职业券 → 选职业（右键券可重开菜单）；全员选完再开波。 */
    private void beginClassSelect() {
        if (plannedActs == null) {
            String detail = planError == null ? "" : "：" + planError;
            sender().error(MessageService.raw("game.plan_failed", Map.of("detail", detail)));
            fail();
            return;
        }
        ActPlan act1 = plannedActs.get(0);
        ActSpawnHelper.pasteAndTeleport(this, act1);
        RunScoreboard.start(this);
        if (MaggoteersPlugin.getInstance().getConfig().getBoolean("player.night_vision", true)) {
            for (PlayerExt pe : getPlayers()) {
                grantRunNightVision(pe.player());
            }
        }

        sender().info(MessageService.raw("game.enter_class_select", Map.of()));
        for (PlayerExt pe : getPlayers()) {
            ItemService.giveInitialEquipment(pe.player());
            ItemService.giveKind(pe.player(), ItemKind.CLASS_TICKET, 1);
            ItemService.giveKind(pe.player(), ItemKind.SHOP_EMERALD, 1);
            MenuFacade.open("maggoteers-class", pe.player(), this);
        }
        int timeout = MaggoteersPlugin.getInstance().getConfig().getInt("class_select.timeout_sec", 45);
        new BukkitRunnable() {
            int left = timeout;
            @Override public void run() {
                if (cleanedUp || outcome != GameOutcome.IN_PROGRESS) { cancel(); return; }
                if (ClassSelectGate.allChosen(MaggoteersGame.this)) { cancel(); onAllClassesChosen(); return; }
                if (--left <= 0) {
                    cancel();
                    ClassSelectGate.autoPickRemaining(MaggoteersGame.this, new Random());
                    onAllClassesChosen();
                }
            }
        }.runTaskTimer(MaggoteersPlugin.getInstance(), 20L, 20L);
    }

    /** 全员职业选完（或超时兜底）后启动波次。 */
    public void onAllClassesChosen() {
        if (plannedActs == null || cleanedUp || outcome != GameOutcome.IN_PROGRESS || wavesStarted) return;
        wavesStarted = true;
        runStartEpochMs = System.currentTimeMillis();   // 计时起点：全员职业选完
        // 玩家受伤倍率（2026-10-26）：人数 × 难度 的 mob_damage 系数改为"玩家受到的所有伤害"倍率，
        // 不再乘进怪物 ATTACK_DAMAGE（那样只影响近战）。
        int runPlayers = Math.max(1, getPlayers().size());
        this.damageTakenMultiplier = io.mczju.maggoteers.config.ScalingConfig.getInstance()
                .scaleFor(runPlayers, difficulty).mobDamage();
        // 职业 STAT（含 MAX_HEALTH）应用后统一 resync + 回满，避免切武器/二次粘贴打乱血量
        for (PlayerExt pe : getPlayers()) {
            Player pl = pe.player();
            EffectService.resync(pl);
            PlayerStateManager.restoreFullHealth(pl);
        }
        io.mczju.maggoteers.effect.EffectListener.startTick();
        WaveScheduler.start(this, plannedActs);
        int prep = MaggoteersPlugin.getInstance().getConfig().getInt("act_enter.prep_sec", 10);
        String prepClause = prep > 0
                ? MessageService.raw("game.prep_clause", Map.of("prep", String.valueOf(prep)))
                : "";
        sender().info(MessageService.raw("game.ready", Map.of(
                "prep_clause", prepClause,
                "waves", String.valueOf(plannedActs.stream().mapToInt(a -> a.waves().size()).sum()),
                "lives", String.valueOf(MaggoteersPlugin.getInstance().getConfig().getInt("lives.default", 2)))));
    }

    private static void grantRunNightVision(Player player) {
        EffectContext ctx = new EffectContext();
        ctx.put(EffectKeys.POTION, PotionEffectType.NIGHT_VISION);
        ctx.put(EffectKeys.AMP, 0);
        PlayerEffect pe = new PlayerEffect(
                "run_night_vision", Effect.ADD_POTION, ctx, null,
                null, 0, 0, null, Stack.REPLACE, 0, 0);
        EffectService.apply(player, pe);
    }

    private void cleanupRun() {
        if (cleanedUp) return;
        cleanedUp = true;

        // 结算：按 outcome 发账户货币（D4）。
        // ⚠️ 失败结算在「全员 leave 后」才触发（finishGame），此刻 getPlayers() 已被 leaveGame 清空——
        // 必须遍历开局快照 runPlayerUuids，否则中途死亡/退出的玩家（乃至整个败局）都拿不到积分。
        {
            int winFlat = MaggoteersPlugin.getInstance().getConfig().getInt("settlement.win_flat", 100);
            int failPerAct = MaggoteersPlugin.getInstance().getConfig().getInt("settlement.fail_per_act", 25);
            int failPerWave = MaggoteersPlugin.getInstance().getConfig().getInt("settlement.fail_per_wave", 5);
            // 结算积分按当局难度乘数缩放（普通 1.0 / 困难 1.25 / 专家 1.5，config.yml settlement.difficulty_multipliers）。
            double mult = MaggoteersPlugin.getInstance().getConfig().getDouble(
                    "settlement.difficulty_multipliers." + this.difficulty.name().toLowerCase(java.util.Locale.ROOT), 1.0);
            int[] prog = WaveScheduler.progress(this);
            var input = new io.mczju.maggoteers.persist.Settlement.Input(
                    outcome, prog[0], prog[1]);
            int grant = io.mczju.maggoteers.persist.Settlement.grant(input, winFlat, failPerAct, failPerWave, mult);
            if (grant > 0) {
                var pdm = MCZJUGameCore.getPlayerDataManager();
                // 通关计时：仅 WIN 且计时完整时记最佳通关时间 + 前十/刷新记录 ActionBar 通知。
                // 用时 = 全员选职业完毕 → 最终 Act3 Boss 波清完发奖杯。
                long elapsedMs = (runStartEpochMs > 0 && runEndEpochMs > 0)
                        ? runEndEpochMs - runStartEpochMs : 0L;
                boolean recordable = outcome == GameOutcome.WIN && elapsedMs > 0;
                List<io.mczju.maggoteers.persist.MaggoteersPlayerData> allData = recordable
                        ? pdm.getAllPlayerData(io.mczju.maggoteers.persist.MaggoteersPlayerData.class)
                        : java.util.Collections.emptyList();
                for (UUID uid : runPlayerUuids) {
                    var data = pdm.getPlayerData(uid.toString(), io.mczju.maggoteers.persist.MaggoteersPlayerData.class);
                    if (data == null) continue;
                    data.grant(grant);
                    boolean bestUpdated = false;
                    int rank = -1;
                    if (recordable) {
                        bestUpdated = data.updateBestRun(elapsedMs);
                        // 升序排名：严格快于本次的玩家数 + 1（并列同 rank，含自己）。
                        rank = 1;
                        for (var d : allData) {
                            if (d.bestRunMs != Long.MAX_VALUE && d.bestRunMs < elapsedMs) rank++;
                        }
                    }
                    // 已退玩家不在 getPlayers()，MGC 仅在退出/定时自动保存；这里显式保存确保 grant 落盘。
                    pdm.savePlayerDataAsync(uid.toString());
                    Player pl = Bukkit.getPlayer(uid);
                    if (pl != null) {
                        pl.sendMessage(MessageService.component("game.settlement_grant", Map.of(
                                "player", pl.getName(),
                                "grant", String.valueOf(grant))));
                        if (recordable) {
                            String timeStr = io.mczju.maggoteers.persist.MaggoteersRunTimeLeaderboard.formatMs(elapsedMs);
                            if (bestUpdated) {
                                pl.sendActionBar(MessageService.component(
                                        "scoreboard.time_record_best", Map.of("time", timeStr)));
                            } else if (rank >= 1 && rank <= 10) {
                                pl.sendActionBar(MessageService.component(
                                        "scoreboard.time_record_top",
                                        Map.of("rank", String.valueOf(rank), "time", timeStr)));
                            }
                        }
                    }
                }
            }
        }

        for (var pe : getPlayers()) {
            var pl = pe.player();
            pl.getInventory().clear();
            // 末影箱是玩家全局持久数据（局内可正常使用），对局结束必须清空，
            // 且在 switchProfile(null) 之前清——让 capture 把空末影箱写进 maggoteers profile 快照，根除跨局残留。
            pl.getEnderChest().clear();
            EffectService.removeAll(pl);
            io.mczju.maggoteers.item.CooldownService.clear(pl.getUniqueId());
            io.mczju.maggoteers.reward.RewardService.clearDraws(pl.getUniqueId());
            pe.switchProfile(null);
            pl.setGameMode(GameMode.SURVIVAL);
            // 收尾同样剥一次「无敌」标记：避免本局期间被别的插件写上后又带进大厅（下次进局还会被开局剥掉）
            PlayerStateManager.clearStaleInvulnerable(pl);
            resetLobbyVitality(pl);
            // 回退链：房间 return/wait → 入局位置 → 主世界 spawn（防删世界时落主世界）
            MaggoteersRoom.teleportReturn(this, pl, joinLocations.get(pl.getUniqueId()));
        }

        io.mczju.maggoteers.effect.SummonRegistry.clearAll(this);
        io.mczju.maggoteers.effect.MobAiLockRegistry.restoreAll(this);
        io.mczju.maggoteers.effect.EffectListener.stopTick();
        WaveScheduler.stop(this);
        RunScoreboard.stop(this);
        ClassSelectGate.clear(this);
        io.mczju.maggoteers.effect.RecurringClock.forgetGame(this);
        PlayerStateManager.destroyAll(this);
        WorldService.cleanup(this);
        io.mczju.maggoteers.effect.AuraService.clearGame(this);
        joinLocations.clear();
    }

    /** 剥除局内效果后再复位；勿写死 20 血（MAX_HEALTH 加成可能尚未还原）。quit 策略也会用。 */
    static void resetLobbyVitality(Player pl) {
        PlayerStateManager.healToMax(pl);
        pl.setFoodLevel(20);
        pl.setSaturation(5f);
        PlayerStateManager.clearFallFire(pl);
        PlayerStateManager.restoreFallSafety(pl);   // 回大厅恢复原版摔落判定
    }

    @Override protected void onGameCancel() { cleanupRun(); }
    @Override protected void onGameAbort()  { cleanupRun(); }
    @Override protected void onGameEnd()    { cleanupRun(); }

    private void runWhenReady(Runnable action) {
        new BukkitRunnable() {
            @Override public void run() { cancel(); action.run(); }
        }.runTask(MaggoteersPlugin.getInstance());
    }
}
