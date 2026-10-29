package io.mczju.maggoteers.wave;

import com.github.mczjuops.mczjugamecore.game.AbstractGame;
import io.mczju.maggoteers.MaggoteersPlugin;
import io.mczju.maggoteers.config.MessageService;
import io.mczju.maggoteers.game.MaggoteersGame;
import io.mczju.maggoteers.item.ItemKind;
import io.mczju.maggoteers.item.ItemService;
import io.mczju.maggoteers.plan.ActPlan;
import io.mczju.maggoteers.plan.RunConfig;
import io.mczju.maggoteers.reward.RewardService;
import io.mczju.maggoteers.world.ActSpawnHelper;
import io.mczju.maggoteers.world.WorldService;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * 单一可暂停状态机心跳，驱动 {@code SPAWNING→ACTIVE→REST→下一波/下一层/VICTORY}。
 */
public final class WaveScheduler {

    public enum Phase { PREP, SPAWNING, ACTIVE, REST, DONE, DEFEATED }

    public record RunSnapshot(
            int actIndex, String actId, String mapId, int waveIndex, int waveCount,
            Phase phase, String lastTier, int restSecondsLeft,
            String strategyDisplayName
    ) {
        public String phaseName() {
            return WaveScheduler.phaseName(phase);
        }
    }

    /** Plain phase label for scoreboard / UI (no MiniMessage tags). */
    public static String phaseName(Phase phase) {
        return MessageService.raw("wave.phase_" + phase.name().toLowerCase(Locale.ROOT), Map.of());
    }

    static final class Cursor {
        /** ⚠️ 非 final：调试换波（{@link #debugReplaceWave}）会整体替换成一份新 list。 */
        List<ActPlan> acts;
        final RunConfig runCfg;
        int actIndex = 0;
        int waveIndex = 0;
        Phase phase = Phase.SPAWNING;
        long waveStartTicks;
        long restEndTicks;
        long prepEndTicks;
        int stepCursor = 0;
        List<SpawnStep> currentExpanded = List.of();
        WaveRuntime runtime;
        BukkitTask task;
        String lastTier = "weak";
        /** 最近击杀 Boss 所在层（Boss 池粘滞，§9.2）。 */
        int lastBossKillAct = 0;
        /** 2026-10-26：已结算过的波次（"层:波"），防同一波重复发奖励（实测奖杯会变 2 个）。 */
        final Set<String> clearedWaves = new HashSet<>();
        /** 隐藏结局层（Act4）是否已用钥匙开启（2026-10-26 阶段 5）。 */
        boolean hiddenEntered = false;
        /** 本休整期已投跳过票的玩家（全员同意才跳过）。 */
        final Set<UUID> skipVotes = new HashSet<>();

        Cursor(List<ActPlan> acts, RunConfig runCfg) {
            this.acts = acts;
            this.runCfg = runCfg;
        }
    }

    private static final Map<AbstractGame, Cursor> CURSORS = new IdentityHashMap<>();

    public static void start(AbstractGame game, List<ActPlan> acts, RunConfig runCfg) {
        Cursor c = new Cursor(acts, runCfg);
        CURSORS.put(game, c);
        enterAct(game, c, 0);
        scheduleHeartbeat(game, c);
    }

    public static void start(AbstractGame game, List<ActPlan> acts) {
        start(game, acts, loadRunConfig());
    }

    public static void stop(AbstractGame game) {
        Cursor c = CURSORS.remove(game);
        io.mczju.maggoteers.wave.ArenaPoints.clear(game);
        if (c == null) return;
        if (c.task != null) c.task.cancel();
        WaveEngine.stop(game);
    }

    /** 败局停摆：停心跳 + 清怪 + 置 DEFEATED（计分板显示失败），但保留 cursor 供结算读取进度（≠ stop 会移除 cursor）。 */
    public static void defeat(AbstractGame game) {
        Cursor c = CURSORS.get(game);
        if (c == null) return;
        if (c.task != null) { c.task.cancel(); c.task = null; }
        c.phase = Phase.DEFEATED;
        WaveEngine.stop(game);
        // 1.3.2 清场兜底：stop 只删追踪怪，未追踪残留（分裂子体/恼鬼等）仍存活——
        // 延迟 5 tick 再补一次全量清场，与 onWaveCleared 同语义（2026-08-27）。
        Bukkit.getScheduler().runTaskLater(MaggoteersPlugin.getInstance(),
                () -> WaveEngine.clearWorld((MaggoteersGame) game), 5L);
    }

    /** 波次状态机仍跟踪的对局（不依赖 MGC gameList）。 */
    public static java.util.Set<AbstractGame> activeGames() {
        return java.util.Collections.unmodifiableSet(CURSORS.keySet());
    }

    /** 结算用：返回 {actIndex, wavesClearedInCurrentAct}（无 cursor 返回 {0,0}）。 */
    public static int[] progress(AbstractGame game) {
        Cursor c = CURSORS.get(game);
        if (c == null) return new int[]{0, 0};
        return new int[]{c.actIndex, c.waveIndex};
    }

    public static RunSnapshot snapshot(AbstractGame game) {
        Cursor c = CURSORS.get(game);
        if (c == null) return null;
        ActPlan act = c.acts.get(c.actIndex);
        int restLeft = c.phase == Phase.REST
                ? Math.max(0, (int) ((c.restEndTicks - Bukkit.getCurrentTick()) / 20L)) : 0;
        if (c.phase == Phase.PREP) {
            restLeft = Math.max(0, (int) ((c.prepEndTicks - Bukkit.getCurrentTick()) / 20L));
        }
        String strategyName = "";
        if (!act.waves().isEmpty() && c.waveIndex >= 0 && c.waveIndex < act.waves().size()) {
            strategyName = act.waves().get(c.waveIndex).displayNameOrId();
        }
        return new RunSnapshot(c.actIndex, act.actId(), act.mapId(), c.waveIndex, act.waves().size(),
                c.phase, c.lastTier, restLeft, strategyName);
    }

    /** 已投跳过票人数。 */
    public static int skipVoteCount(AbstractGame game) {
        Cursor c = CURSORS.get(game);
        return c == null ? 0 : c.skipVotes.size();
    }

    /** 休整期可投票的玩家总数（冒险模式玩家）。 */
    public static int skipVoteTotal(AbstractGame game) {
        int total = 0;
        for (UUID uuid : io.mczju.maggoteers.state.PlayerStateManager.uuidsInGame(game)) {
            Player pl = Bukkit.getPlayer(uuid);
            if (pl != null && io.mczju.maggoteers.state.PlayerStateManager.isRunParticipant(game, pl)) {
                total++;
            }
        }
        return total;
    }

    /**
     * 休整跳过投票（§9.3）：记录该玩家赞成票；全员同意才 advance。
     * @return true 表示本票促成跳过（已 advance），false 表示仍需更多票。
     */
    public static boolean voteSkip(AbstractGame game, UUID uuid) {
        Cursor c = CURSORS.get(game);
        if (c == null || c.phase != Phase.REST || uuid == null) return false;
        Player voter = Bukkit.getPlayer(uuid);
        if (voter == null || !io.mczju.maggoteers.state.PlayerStateManager.isRunParticipant(game, voter)) return false;
        if (c.skipVotes.contains(uuid)) return false;
        c.skipVotes.add(uuid);
        int total = skipVoteTotal(game);
        int voted = c.skipVotes.size();
        String voterName = voter.getName();
        broadcast(game, MessageService.component("wave.skip_vote", Map.of(
                "voted", String.valueOf(voted),
                "total", String.valueOf(total),
                "player", voterName)));
        if (voted >= total) {
            broadcast(game, MessageService.component("wave.skip_passed", Map.of()));
            advance(game, c);
            return true;
        }
        return false;
    }

    /** Boss 池粘滞层索引（最近击杀 Boss 所在层，§9.2）。 */
    public static int bossPoolActIndex(AbstractGame game) {
        Cursor c = CURSORS.get(game);
        return c == null ? 0 : c.lastBossKillAct;
    }

    /** Boss 池粘滞层 id（无 cursor 时回落 fallback）。 */
    public static String bossPoolActId(AbstractGame game, String fallback) {
        Cursor c = CURSORS.get(game);
        if (c == null || c.acts.isEmpty()) return fallback;
        int idx = Math.max(0, Math.min(c.lastBossKillAct, c.acts.size() - 1));
        return c.acts.get(idx).actId();
    }

    /** 返回当前层的 playerSpawn 绝对坐标（已叠 act_origins），供死亡复活等场景传送。null 表示无 cursor / 无世界。 */
    public static org.bukkit.Location currentSpawnLocation(AbstractGame game) {
        Cursor c = CURSORS.get(game);
        if (c == null) return null;
        World w = WorldService.get(game);
        if (w == null) return null;
        var spawn = c.acts.get(c.actIndex).playerSpawn();
        // 与其它出生点一致：落在方块中心（复活/进层传送都用这里）
        return new org.bukkit.Location(w,
                io.mczju.maggoteers.util.Coords.blockCenter(spawn.x()), spawn.y(),
                io.mczju.maggoteers.util.Coords.blockCenter(spawn.z()));
    }

    /** 是否处于波次休整期（可右键货币打开升级菜单）。 */
    public static boolean isRestPhase(AbstractGame game) {
        Cursor c = CURSORS.get(game);
        return c != null && c.phase == Phase.REST;
    }

    /**
     * 调试：跳到指定层/波（0-based）。清当前怪、跳过休整；跨层时会重新粘贴结构并传送。
     */
    public static boolean debugSeekWave(AbstractGame game, int actIndex, int waveIndex) {
        Cursor c = CURSORS.get(game);
        if (c == null || c.phase == Phase.DONE || c.phase == Phase.DEFEATED) return false;
        if (actIndex < 0 || actIndex >= c.acts.size()) return false;
        if (waveIndex < 0 || waveIndex >= c.acts.get(actIndex).waves().size()) return false;
        WaveEngine.killAllTracked(game);
        if (actIndex != c.actIndex) {
            ActSpawnHelper.pasteAndTeleport(game, c.acts.get(actIndex));
            c.actIndex = actIndex;
        }
        beginWave(game, c, waveIndex);
        return true;
    }

    /**
     * 当前波所在**池**的 strategy id（基础池 ∪ 该图 special_waves）。无 cursor 时返回空表。
     * 调试换波的白名单与 Tab 补全都用它——"能点名换的"恒等于"这一波本可能抽到的"。
     */
    public static List<String> currentPoolStrategyIds(AbstractGame game) {
        Cursor c = CURSORS.get(game);
        if (c == null || c.actIndex < 0 || c.actIndex >= c.acts.size()) return List.of();
        ActPlan act = c.acts.get(c.actIndex);
        return io.mczju.maggoteers.plan.RunPlanner.poolStrategyIds(
                act.actId(), tierForWave(c, Math.max(0, c.waveIndex)), act.mapId());
    }

    /** 当前波所在的池档位（weak / strong / boss）；无 cursor 时返回空串。 */
    public static String currentTier(AbstractGame game) {
        Cursor c = CURSORS.get(game);
        if (c == null) return "";
        return tierForWave(c, Math.max(0, c.waveIndex));
    }

    /**
     * 调试：把**当前波**替换成同一池里的指定 strategy，清掉当前怪并**立刻重开这一波**。
     *
     * <p>用途：剧本已经抽好了改不动，但今天想测另一条（例：Act3 Boss 抽到「圣城」，
     * 现在要测「迈入永恒」）。可选范围严格等于 {@link #currentPoolStrategyIds}，
     * 所以换出来的波在数值/点位上与"真抽到它"完全一致（同一个 {@code RunPlanner.expand}）。
     *
     * @return {@code null} = 成功；否则是给玩家看的中文失败原因
     */
    public static String debugReplaceWave(AbstractGame game, String strategyId) {
        Cursor c = CURSORS.get(game);
        if (c == null) return "不在局内（没有波次状态机）。";
        if (c.phase == Phase.DONE || c.phase == Phase.DEFEATED) return "本局已结束/已判负，不能换波。";
        if (strategyId == null || strategyId.isBlank()) return "用法：/maggoteers debug wave <strategyId>";
        ActPlan act = c.acts.get(c.actIndex);
        int waveIndex = Math.max(0, c.waveIndex);
        String tier = tierForWave(c, waveIndex);
        List<String> pool = currentPoolStrategyIds(game);
        if (!pool.contains(strategyId)) {
            return "「" + strategyId + "」不在 " + act.actId() + "/" + tier + " 池里。可用：" + String.join(", ", pool);
        }
        MaggoteersGame mg = game instanceof MaggoteersGame g ? g : null;
        int players = mg == null ? 1 : Math.max(1, mg.getRunPlayerCount());
        var difficulty = mg == null ? io.mczju.maggoteers.util.GameDifficulty.NORMAL : mg.getDifficulty();
        WaveSpec replacement;
        try {
            replacement = io.mczju.maggoteers.plan.RunPlanner.buildWave(
                    act.actId(), strategyId, act.mapId(), players, difficulty);
        } catch (RuntimeException ex) {
            return "展开这一波失败：" + ex.getMessage();
        }
        List<WaveSpec> waves = new ArrayList<>(act.waves());
        waves.set(waveIndex, replacement);
        List<ActPlan> acts = new ArrayList<>(c.acts);
        acts.set(c.actIndex, new ActPlan(act.actId(), act.mapId(), act.playerSpawn(), waves));
        c.acts = acts;
        // 清掉当前波的怪 + 追踪 + Boss 条，并丢弃旧 runtime（beginWave 会新建一份干净的）
        WaveEngine.killAllTracked(game);
        c.runtime = null;
        if (c.task != null) { c.task.cancel(); c.task = null; }
        beginWave(game, c, waveIndex);
        scheduleHeartbeat(game, c);
        return null;
    }

    /** 调试：相对当前层波次偏移（可跨层，waveIndex 钳制在目标层范围内）。 */
    public static boolean debugShiftWave(AbstractGame game, int delta) {
        Cursor c = CURSORS.get(game);
        if (c == null || c.phase == Phase.DONE || c.phase == Phase.DEFEATED || delta == 0) return false;
        int act = c.actIndex;
        int wave = c.waveIndex + delta;
        while (wave < 0 && act > 0) {
            act--;
            wave += c.acts.get(act).waves().size();
        }
        while (wave >= c.acts.get(act).waves().size() && act + 1 < c.acts.size()) {
            wave -= c.acts.get(act).waves().size();
            act++;
        }
        if (act < 0 || act >= c.acts.size()) return false;
        int max = c.acts.get(act).waves().size();
        if (wave < 0 || wave >= max) return false;
        return debugSeekWave(game, act, wave);
    }

    /**
     * 隐藏结局层（Act4）进门（2026-10-26 阶段 5）：由钥匙物品右键调用。
     *
     * <p>校验：配置了 hidden_act 且它在 acts 里 → 当前层 == hidden_gate_after → 处于**休整期**（刚清完该层 Boss）
     * → 尚未进过。全部通过则置 {@code hiddenEntered} 并 {@link #enterAct} 到隐藏层（粘贴+传送+准备倒计时+开波）。
     *
     * @return {@code null} = 成功进入；否则是给玩家看的失败原因
     */
    public static String enterHiddenAct(AbstractGame game) {
        Cursor c = CURSORS.get(game);
        if (c == null) return "不在局内（没有波次状态机）。";
        if (c.phase == Phase.DONE || c.phase == Phase.DEFEATED) return "本局已结束/已判负。";
        HiddenActGate.Gate gate = HiddenActGate.gate();
        if (!gate.active()) return "本服没有配置隐藏层（config.yml 的 hidden_act）。";
        if (c.hiddenEntered) return "隐藏层已经开启了。";
        int hiddenIndex = gate.hiddenIndex(c.acts.stream().map(ActPlan::actId).toList());
        if (hiddenIndex < 0) return "config.yml acts: 里没有 " + gate.hiddenAct() + "。";
        int gateIndex = gate.gateIndex(c.acts.stream().map(ActPlan::actId).toList());
        if (c.actIndex != gateIndex) {
            return "只能在第 " + (gateIndex + 1) + " 层（" + gate.gateAfter() + "）清完 Boss 后的终局休整期使用钥匙。";
        }
        if (c.phase != Phase.REST) return "必须在终局休整期使用钥匙（现在不是休整期）。";
        c.hiddenEntered = true;
        broadcast(game, MessageService.component("wave.hidden_enter", Map.of()));
        enterAct(game, c, hiddenIndex);
        return null;
    }

    /**
     * 是不是**隐藏层（门扉缔造者）的最终 Boss 波**——只有这一波允许"Boss 一死立刻算通关"。
     *
     * <p>2026-10-28 用户口径：门扉缔造者死亡时，不管场上还剩多少杂兵都直接算胜利。
     * 2026-10-31 用户口径：**这条特殊规则只对隐藏层生效**；act1–act3 的 Boss 死了也要把本波剩余杂兵清完
     * 才算过波（否则"打死 Boss 就通关"会让前三层完全失去收尾压力）。
     *
     * <p>判定 = 当前层 **就是隐藏层**（按 act id 比，`debug jumpto` 直接跳过去也认，此时
     * {@code hiddenEntered} 可能是 false）且走到本层最后一波。
     *
     * <p>⚠️ 旧方法 {@code isFinalBossWave}（"终局层最后一波"）已删除：它曾被直接当成即时胜利用，
     * 于是 act1–act3 的 Boss 本体一死就静默清场判通关。终局播报/氛围音效需要那个判断时，
     * 直接用 {@code isFinalWaveCursor(c) && inFinalAct(c)}。
     */
    public static boolean isHiddenFinalBossWave(AbstractGame game) {
        Cursor c = CURSORS.get(game);
        if (c == null) return false;
        if (c.phase != Phase.ACTIVE && c.phase != Phase.SPAWNING) return false;
        if (!isFinalWaveCursor(c)) return false;
        String hiddenAct = HiddenActGate.gate().hiddenAct();
        if (hiddenAct == null || hiddenAct.isBlank()) return false;
        return hiddenAct.equals(c.acts.get(c.actIndex).actId());
    }

    /** 当前游标是否已经走到"这一层的最后一波"。 */
    private static boolean isFinalWaveCursor(Cursor c) {
        if (c == null || c.acts == null || c.actIndex < 0 || c.actIndex >= c.acts.size()) return false;
        return c.waveIndex == c.acts.get(c.actIndex).waves().size() - 1;
    }

    /**
     * 当前层是否"算终局层"：有效终局层（进了隐藏层=隐藏层、没进=隐藏层前一层）**或** 剧本最后一层。
     * 后者覆盖"调试直接跳进隐藏层、没走钥匙门控"的情况。
     */
    private static boolean inFinalAct(Cursor c) {
        if (c.acts == null || c.acts.isEmpty()) return false;
        if (c.actIndex == effectiveFinalActIndex(c)) return true;
        return c.actIndex == c.acts.size() - 1;
    }

    /**
     * 隐藏层最终 Boss（门扉缔造者）死亡 → 立即判清波（{@code MobDeathListener} 在死亡事件里调用）。
     * 剩余杂兵静默移除（不掉落、不触发亡语），随后走正常清波结算：胜利字幕 + 奖杯 + 休整期。
     *
     * <p>⚠️ **只对隐藏层生效**（2026-10-31 用户口径）：act1–act3 的 Boss 死了也要清完本波剩余杂兵，
     * 否则"杀掉 Boss 就通关"会让前三层失去收尾压力、严重偏离设计。
     */
    public static void finalBossDefeated(AbstractGame game) {
        Cursor c = CURSORS.get(game);
        if (c == null || (c.phase != Phase.ACTIVE && c.phase != Phase.SPAWNING)) return;
        if (!isHiddenFinalBossWave(game)) return;
        // 成就「击败隐藏boss」：走到这里必然是隐藏层
        try {
            io.mczju.maggoteers.achievement.AchievementService.onHiddenBossDefeated(game);
        } catch (RuntimeException ex) {
            io.mczju.maggoteers.MaggoteersPlugin.getInstance().getLogger()
                    .warning("成就：隐藏 Boss 判定异常 " + ex.getMessage());
        }
        // 剩余杂兵静默移除（不掉落/不触发亡语），立刻走清波结算（onWaveCleared 自带幂等门）。
        WaveEngine.killAllTracked(game);
        c.phase = Phase.ACTIVE;
        onWaveCleared(game, c);
    }

    private static void enterAct(AbstractGame game, Cursor c, int actIndex) {
        c.actIndex = actIndex;
        ActPlan act = c.acts.get(actIndex);
        ActSpawnHelper.pasteAndTeleport(game, act);
        broadcast(game, MessageService.component("wave.enter_act", Map.of(
                "act", String.valueOf(actIndex + 1),
                "map", act.mapId())));
        io.mczju.maggoteers.ambience.WaveAmbienceService.playEnterAct(game);   // 氛围音效 #1：进新层
        // 图鉴（2026-10-30）：全员记「进过这层」——隐藏层 Act4 只有进过之后才会在图鉴里露出来。
        io.mczju.maggoteers.codex.CodexProgress.markActForAll(game, act.actId());
        // DYNAMIC_ATTRIBUTE source=acts_cleared：进入第 N 层（0 基 actIndex）即"已过 actIndex 层"——
        // 直接赋值（幂等），调试跳层/重进同层不会重复累计。放在 ON_ACT_ENTER 之前。
        for (UUID uuid : io.mczju.maggoteers.state.PlayerStateManager.uuidsInGame(game)) {
            io.mczju.maggoteers.state.PlayerState ps =
                    io.mczju.maggoteers.state.PlayerStateManager.get(game, uuid);
            if (ps != null) ps.setActsCleared(actIndex);
        }
        io.mczju.maggoteers.effect.EffectService.fireTrigger((MaggoteersGame) game, io.mczju.maggoteers.effect.Trigger.ON_ACT_ENTER);
        io.mczju.maggoteers.effect.SummonRegistry.onActEnter((MaggoteersGame) game);
        // 1.3.3 难度负面奖励：哪一层发哪一池由 config.yml act_rules.curse_* 决定（原写死"困难仅 Act2、专家每层"）；
        // 放在 ON_ACT_ENTER 之后，避免"每进层扣血"当 tick 即触发
        io.mczju.maggoteers.reward.CurseService.onActEnter((MaggoteersGame) game, act.actId(), actIndex);
        beginActPrep(game, c);
    }

    private static void beginActPrep(AbstractGame game, Cursor c) {
        int prepSec = MaggoteersPlugin.getInstance().getConfig().getInt("act_enter.prep_sec", 10);
        if (prepSec <= 0) {
            beginWave(game, c, 0);
            return;
        }
        c.phase = Phase.PREP;
        c.prepEndTicks = Bukkit.getCurrentTick() + prepSec * 20L;
        broadcast(game, MessageService.component("wave.prep", Map.of("prep", String.valueOf(prepSec))));
    }

    private static void beginWave(AbstractGame game, Cursor c, int waveIndex) {
        c.waveIndex = waveIndex;
        c.phase = Phase.SPAWNING;
        c.stepCursor = 0;
        c.lastTier = tierForWave(c, waveIndex);
        WaveSpec spec = c.acts.get(c.actIndex).waves().get(waveIndex);
        c.currentExpanded = spec.expand();
        c.waveStartTicks = Bukkit.getCurrentTick();
        c.runtime = (c.runtime == null) ? WaveEngine.start(game) : c.runtime;
        c.runtime.phases = spec.phases();   // 2026-10-26：Boss 阶段表随波下发
        // 氛围音效 + 大字广播：非 boss 波 #0 / boss 波（最后一种 + 黑体大字）/ 最终 boss（#5 连播 3 次 + 白色黑体大字）
        String levelName = spec.displayNameOrId();
        if (inFinalAct(c) && "boss".equals(c.lastTier)) {
            io.mczju.maggoteers.ambience.WaveAmbienceService.playFinalBoss(game, levelName);
        } else if ("boss".equals(c.lastTier)) {
            io.mczju.maggoteers.ambience.WaveAmbienceService.playBoss(game, levelName);
        } else {
            io.mczju.maggoteers.ambience.WaveAmbienceService.playWaveStart(game);
        }
        String strategyLabel = spec.displayNameOrId();
        // 图鉴（2026-10-30 修正）：**不在波次开始时记**——boss 波前面可能还有好几批杂兵，
        // 玩家几十秒后才见到本体。这里只是把 strategy 挂到 runtime 上，
        // 由 WaveEngine 在「带血条的 Boss 真的刷出来」那一刻结算（见 WaveEngine.trackSpawned）。
        if ("boss".equals(c.lastTier)) {
            c.runtime.bossCodexStrategy = spec.strategyId();
        }
        String strategyClause = strategyLabel.isBlank()
                ? ""
                : MessageService.raw("wave.strategy_clause", Map.of("strategy", strategyLabel));
        broadcast(game, MessageService.component("wave.begin", Map.of(
                "wave", String.valueOf(waveIndex + 1),
                "wave_count", String.valueOf(c.acts.get(c.actIndex).waves().size()),
                "tier", c.lastTier,
                "strategy_clause", strategyClause)));
    }

    private static String tierForWave(Cursor c, int waveIndex) {
        String act = c.acts.get(c.actIndex).actId();
        int weak = c.runCfg.weak(act);
        int strong = c.runCfg.strong(act);
        if (waveIndex < weak) return "weak";
        if (waveIndex < weak + strong) return "strong";
        return "boss";
    }

    private static void scheduleHeartbeat(AbstractGame game, Cursor c) {
        c.task = Bukkit.getScheduler().runTaskTimer(MaggoteersPlugin.getInstance(), () -> tick(game, c), 5L, 5L);
    }

    private static void tick(AbstractGame game, Cursor c) {
        if (c.phase == Phase.DONE || c.phase == Phase.DEFEATED) return;
        if (c.runtime != null) WaveEngine.tick(c.runtime);
        World tw = WorldService.get(game);
        if (tw != null) WorldService.applyTimeLock(tw);

        switch (c.phase) {
            case PREP -> {
                if (Bukkit.getCurrentTick() >= c.prepEndTicks) beginWave(game, c, 0);
            }
            case SPAWNING -> {
                long elapsed = Bukkit.getCurrentTick() - c.waveStartTicks;
                World w = WorldService.get(game);
                while (c.stepCursor < c.currentExpanded.size()
                        && c.currentExpanded.get(c.stepCursor).delayTicks() <= elapsed) {
                    WaveEngine.spawnStep(game, c.runtime, c.currentExpanded.get(c.stepCursor), w);
                    c.stepCursor++;
                }
                if (c.stepCursor >= c.currentExpanded.size()) c.phase = Phase.ACTIVE;
            }
            case ACTIVE -> {
                if (WaveEngine.livingCount(game) == 0) {
                    onWaveCleared(game, c);
                }
            }
            case REST -> {
                if (Bukkit.getCurrentTick() >= c.restEndTicks) advance(game, c);
            }
            default -> {}
        }
    }

    private static void onWaveCleared(AbstractGame game, Cursor c) {
        // 幂等门（2026-10-26）：同一波只结算一次。历史上这里若抛异常会把相位留在 ACTIVE，
        // 心跳每 5 tick 重试 → "威胁已清除"刷屏 + 清波奖励（含奖杯/收藏品）重复发放。
        String waveKey = c.actIndex + ":" + c.waveIndex;
        if (!c.clearedWaves.add(waveKey)) {
            c.phase = Phase.REST;
            return;
        }
        // 1.3.2 一劳永逸：回合结束清场所有「非玩家/非玩家召唤物/非掉落物」实体（含末杀史莱姆分裂子体、恼鬼）。
        // 延迟 5 tick 再清：末位史莱姆的 SlimeSplitEvent 在死亡当 tick 之后才生成分裂子体，
        // 同步清场会漏掉刚生成/待生成的子体（2026-08-27 冒烟实测仍有残留）——延迟后子体已生成、一并清除。
        Bukkit.getScheduler().runTaskLater(MaggoteersPlugin.getInstance(),
                () -> WaveEngine.clearWorld((MaggoteersGame) game), 5L);
        // ⚠️ 2026-10-26 加固：清波流程里任何一步抛异常（曾经的例子：热替换 jar 后
        // NoClassDefFoundError: EffectStacker$1）都会让相位卡在 ACTIVE、每 5 tick 重试一次，
        // 表现为「威胁已清除」无限刷屏 + 波次永不结束。这里统一兜住，保证一定进入 REST。
        try {
        // 发放通关奖励 + 触发 ON_WAVE_CLEAR + 清理召唤物（终局波也需要，C5）
        WaveSpec spec = c.acts.get(c.actIndex).waves().get(c.waveIndex);
        List<io.mczju.maggoteers.wave.RewardItem> rewards = spec.clearReward();
        // 计时终点：奖杯物品 id 仅出现在终局（Act3 Boss）波 clearReward，发奖杯瞬间停表
        if (rewards.stream().anyMatch(r -> "maggoteers:trophy".equals(r.itemId()))) {
            ((MaggoteersGame) game).markTrophyReceived();
        }
        if (!rewards.isEmpty()) {
            RewardService.grantClearRewards(
                    game.getPlayers().stream().map(pe -> pe.player()).toList(), rewards);
            broadcast(game, MessageService.component("wave.cleared_rewarded", Map.of()));
        } else {
            broadcast(game, MessageService.component("wave.cleared", Map.of()));
        }
        // DYNAMIC_ATTRIBUTE source=waves_cleared：清波记账（在 ON_WAVE_CLEAR 分发之前，
        // 让本波清空触发的效果读到最新清波数；倒下玩家也记——他们仍在局内且可被复活）。
        for (UUID uuid : io.mczju.maggoteers.state.PlayerStateManager.uuidsInGame(game)) {
            io.mczju.maggoteers.state.PlayerState ps =
                    io.mczju.maggoteers.state.PlayerStateManager.get(game, uuid);
            if (ps != null) ps.incrementWavesCleared();
        }
        try {
            io.mczju.maggoteers.effect.EffectService.fireTrigger(
                    (MaggoteersGame) game, io.mczju.maggoteers.effect.Trigger.ON_WAVE_CLEAR);
        } catch (RuntimeException ex) {
            MaggoteersPlugin.getInstance().getLogger()
                    .warning("ON_WAVE_CLEAR failed: " + ex.getMessage());
        }
        try {
            io.mczju.maggoteers.effect.SummonRegistry.onWaveClear((MaggoteersGame) game);
        } catch (RuntimeException ex) {
            MaggoteersPlugin.getInstance().getLogger()
                    .warning("SummonRegistry.onWaveClear failed: " + ex.getMessage());
        }
        // Boss 池粘滞（§9.2）：击杀本层 Boss 后更新粘滞层；
        // 某些层（历史=Act3，现由 config.yml act_rules.boss_pool_early 决定）清 weak（奖励关）后提前切 Boss 池。
        io.mczju.maggoteers.config.ActRules rules = MaggoteersPlugin.getInstance().actRules();
        if ("boss".equals(c.lastTier)
                || (rules.bossPoolEarly(c.acts.get(c.actIndex).actId()) && "weak".equals(c.lastTier))) {
            c.lastBossKillAct = c.actIndex;
        }

        // 终局 Boss 也进休整：粘滞已切到 act3_boss，可花掉剩余 Boss 币；休整结束/跳过 → advance → win
        // 隐藏层（Act4）：终局休整期额外提示"可以用钥匙开门"；不用钥匙则休整结束直接正常通关。
        boolean isFinalWave = isFinalWaveCursor(c) && inFinalAct(c);
        if (isFinalWave) {
            // 2026-10-28（用户口径）：终局 Boss 清完**不再立刻 endGame**（那会把玩家直接踢回大厅）。
            // 改为：胜利字幕 + 奖杯（clearReward 已发放）+ 正常进入休整期；休整期结束后才结算通关。
            broadcast(game, MessageService.component("wave.victory", Map.of()));
            io.mczju.maggoteers.ambience.WaveAmbienceService.playVictory(game);
            broadcast(game, MessageService.component("wave.final_rest", Map.of()));
            HiddenActGate.Gate gateNow = HiddenActGate.gate();
            if (gateNow.active() && !c.hiddenEntered
                    && c.actIndex == gateNow.gateIndex(c.acts.stream().map(ActPlan::actId).toList())
                    // 2026-10-30（用户口径）：全员身上都没有星石时，一个字都别提"可以买深渊之钥"——
                    // 提示了也买不起，只会让人白翻一遍商店。
                    && anyoneHoldsBossCurrency(game)) {
                broadcast(game, MessageService.component("wave.hidden_key_hint", Map.of()));
            }
        }

        } catch (RuntimeException | Error ex) {
            MaggoteersPlugin.getInstance().getLogger().warning(
                    "onWaveCleared 异常（已兜住，仍进入休整）：" + ex);
        }
        // 2026-10-28：休整期预粘贴下一层，避免进层瞬间主线程卡 ~1s
        try {
            ActSpawnHelper.prepasteNext(game, c.acts, c.actIndex);
        } catch (RuntimeException ex) {
            MaggoteersPlugin.getInstance().getLogger().warning("prepasteNext 失败：" + ex.getMessage());
        }
        int restSec = MaggoteersPlugin.getInstance().getConfig().getInt("rest.duration_sec", 30);
        c.restEndTicks = Bukkit.getCurrentTick() + restSec * 20L;
        c.phase = Phase.REST;
        c.skipVotes.clear();
        // 2026-10-28（用户口径）：休整菜单**不再自动弹出**（含打完 Boss 后），只提示"可右键货币打开"。
        // 玩家要买东西/进隐藏结局，自己右键货币（OpenRestMenuHandler）或点商店绿宝石。
        broadcast(game, MessageService.component("wave.rest_hint", Map.of()));
    }

    private static void advance(AbstractGame game, Cursor c) {
        int waveCount = c.acts.get(c.actIndex).waves().size();
        if (c.waveIndex + 1 < waveCount) { beginWave(game, c, c.waveIndex + 1); return; }
        // 隐藏层（Act4）：没开门就跳过它，直接走正常通关；开了门（hiddenEntered）才会进
        List<String> actIds = c.acts.stream().map(ActPlan::actId).toList();
        int next = HiddenActGate.gate().nextIndexAfterClear(actIds, c.actIndex, c.hiddenEntered);
        if (next < c.acts.size()) { enterAct(game, c, next); return; }
        c.phase = Phase.DONE;
        if (c.task != null) c.task.cancel();
        WaveEngine.stop(game);
        broadcast(game, MessageService.component("wave.victory", Map.of()));
        ((MaggoteersGame) game).win();
    }

    /** 本局"有效终局层"下标：进了隐藏层 = 隐藏层；没进 = 隐藏层前一层（氛围音效/终局播报用）。 */
    private static int effectiveFinalActIndex(Cursor c) {
        return HiddenActGate.gate().effectiveFinalIndex(
                c.acts.stream().map(ActPlan::actId).toList(), c.hiddenEntered);
    }

    private static void broadcast(AbstractGame game, Component msg) {
        game.getPlayers().forEach(pe -> pe.player().sendMessage(msg));
    }

    /**
     * 本局是否还有人身上带着**星石**（Boss 货币）。
     *
     * <p>2026-10-30（用户口径）：第三层终局休整是唯一能用星石买「深渊之钥」的窗口；
     * 全员都没有星石时，"可以用星石买钥匙"的提示根本无从执行，故一个字都不提。
     * 只看背包里的实物（商店扣的是物品），不看任何累计统计。
     */
    private static boolean anyoneHoldsBossCurrency(AbstractGame game) {
        if (game == null) return false;
        for (var pe : game.getPlayers()) {
            Player p;
            try {
                p = pe.player();
            } catch (RuntimeException ex) {
                continue;
            }
            if (p == null || !p.isOnline()) continue;
            if (ItemService.countKind(p, ItemKind.CURRENCY_BOSS) > 0) return true;
        }
        return false;
    }

    private static RunConfig loadRunConfig() {
        var plugin = MaggoteersPlugin.getInstance();
        return io.mczju.maggoteers.plan.RunPlanner.loadRunConfig(plugin);
    }

    private WaveScheduler() {}
}
