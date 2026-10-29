package io.mczju.maggoteers.command;

import com.github.mczjuops.mczjugamecore.MCZJUGameCore;
import com.github.mczjuops.mczjugamecore.game.GameState;
import com.github.mczjuops.mczjugamecore.menu.MenuFacade;
import com.github.mczjuops.mczjugamecore.player.PlayerExt;
import com.github.mczjuops.mczjugamecore.score.leaderboard.LeaderboardEntry;
import io.mczju.maggoteers.MaggoteersPlugin;
import io.mczju.maggoteers.config.MessageService;
import io.mczju.maggoteers.game.MaggoteersGame;
import io.mczju.maggoteers.persist.MaggoteersPlayerData;
import io.mczju.maggoteers.persist.MaggoteersRunTimeLeaderboard;
import io.mczju.maggoteers.item.ItemKind;
import io.mczju.maggoteers.item.ItemService;
import io.mczju.maggoteers.plan.Compose;
import io.mczju.maggoteers.config.ScalingConfig;
import io.mczju.maggoteers.util.GameDifficulty;
import io.mczju.maggoteers.effect.Effect;
import io.mczju.maggoteers.effect.PlayerEffect;
import io.mczju.maggoteers.mob.MobFactory;
import io.mczju.maggoteers.plan.ActPlan;
import io.mczju.maggoteers.plan.RunConfig;
import io.mczju.maggoteers.plan.RunPlanner;
import io.mczju.maggoteers.state.PlayerState;
import io.mczju.maggoteers.state.PlayerStateManager;
import io.mczju.maggoteers.reward.RewardOption;
import io.mczju.maggoteers.reward.RewardService;
import io.mczju.maggoteers.wave.WaveEngine;
import io.mczju.maggoteers.wave.WaveScheduler;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * /maggoteers 命令。
 * <ul>
 *   <li>{@code shop} —— 局外解锁商店（所有人）。</li>
 *   <li>{@code time} —— 个人最佳通关时间 + 排行榜前 10（所有人）。</li>
 *   <li>{@code reload} —— 热重载 YAML 配置（需 {@code maggoteers.admin}；控制台可用）。</li>
 *   <li>{@code debug ...} —— 运维/调试。{@code config debug.enabled=true} 时任意玩家可用；否则需 {@code maggoteers.admin}。</li>
 * </ul>
 */
public class MaggoteersCommand implements CommandExecutor, org.bukkit.command.TabCompleter {

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, @NotNull String[] args) {
        if (args.length > 0 && "reload".equalsIgnoreCase(args[0])) {
            return reload(sender);
        }
        if (!(sender instanceof Player p)) {
            sender.sendMessage(MessageService.component("command.players_only", Map.of()));
            return true;
        }
        if (args.length == 0) { usage(p); return true; }

        switch (args[0].toLowerCase()) {
            case "shop" -> { MenuFacade.open("maggoteers-shop", p); return true; }
            // 图鉴：正名 codex（`index` 是历史写法，留个别名免得老习惯敲不出来）
            case "codex", "index" -> { MenuFacade.open("maggoteers-codex", p); return true; }
            // 成就：MGC 的 /mgc achievements 不带游戏 id 会过滤成空列表，这里替玩家把 gameId 带上
            case "achievements", "achieve" -> {
                if (!io.mczju.maggoteers.achievement.AchievementService.openMenu(p)) {
                    msg(p, "打开成就界面失败（看控制台日志）。");
                }
                return true;
            }
            case "difficulty" -> { return difficulty(p, args); }
            case "debug" -> { return debug(p, args); }
            case "time" -> { return time(p); }
            default -> { usage(p); return true; }
        }
    }

    /** /maggoteers time —— 个人最佳通关时间 + 排行榜前 10。 */
    private boolean time(Player p) {
        var pdm = MCZJUGameCore.getPlayerDataManager();
        var data = pdm.getPlayerData(p.getUniqueId().toString(), MaggoteersPlayerData.class);
        if (data != null && data.bestRunMs != Long.MAX_VALUE) {
            p.sendMessage(MessageService.component("command.time_self", Map.of(
                    "time", MaggoteersRunTimeLeaderboard.formatMs(data.bestRunMs))));
        } else {
            p.sendMessage(MessageService.component("command.time_none", Map.of()));
        }
        var lb = new MaggoteersRunTimeLeaderboard();
        List<LeaderboardEntry> entries = lb.fetchEntries();   // 已按 ASCENDING 排序（时间短在前）
        List<LeaderboardEntry> top = entries.stream()
                .filter(e -> e.value() < MaggoteersRunTimeLeaderboard.NO_RECORD)
                .limit(10)
                .toList();
        if (top.isEmpty()) {
            p.sendMessage(MessageService.component("command.time_empty", Map.of()));
            return true;
        }
        p.sendMessage(MessageService.component("command.time_list_title", Map.of(
                "count", String.valueOf(top.size()))));
        int i = 1;
        for (LeaderboardEntry e : top) {
            p.sendMessage(MessageService.component("command.time_list_line", Map.of(
                    "index", String.valueOf(i++),
                    "name", e.playerName(),
                    "time", MaggoteersRunTimeLeaderboard.formatMs((long) e.value()))));
        }
        return true;
    }

    /**
     * /maggoteers difficulty [normal|hard|expert|0|1|2] —— 等待阶段房主选择当局难度（1.3.3）。
     * 无参显示当前；难度在开局规划时生效（影响 scaling 倍率 + 负面奖励 + 技能过滤）。
     */
    private boolean difficulty(Player p, String[] args) {
        var pe = new PlayerExt(p);
        if (!(pe.getGame() instanceof MaggoteersGame mg)) {
            p.sendMessage(MessageService.component("command.difficulty_not_in_game", Map.of()));
            return true;
        }
        var party = pe.getParty();
        if (party != null && !party.isLeader(pe)) {
            p.sendMessage(MessageService.component("command.difficulty_not_host", Map.of()));
            return true;
        }
        if (mg.getState() != GameState.WAITING) {
            p.sendMessage(MessageService.component("command.difficulty_only_waiting", Map.of()));
            return true;
        }
        if (args.length < 2) {
            p.sendMessage(MessageService.component("command.difficulty_current", Map.of(
                    "difficulty", mg.getDifficulty().name())));
            return true;
        }
        var parsed = GameDifficulty.parse(args[1]);
        if (parsed.isEmpty()) {
            p.sendMessage(MessageService.component("command.difficulty_unknown", Map.of("value", args[1])));
            return true;
        }
        GameDifficulty d = parsed.get();
        mg.setWaitingDifficulty(d);
        String name = p.getName();
        p.sendMessage(MessageService.component("command.difficulty_set", Map.of(
                "difficulty", d.name(), "host", name)));
        for (var other : mg.getPlayers()) {
            if (!other.player().getUniqueId().equals(p.getUniqueId())) {
                other.player().sendMessage(MessageService.component("command.difficulty_broadcast", Map.of(
                        "difficulty", d.name(), "host", name)));
            }
        }
        return true;
    }

    private boolean reload(CommandSender sender) {
        if (!sender.hasPermission("maggoteers.admin")) {
            sender.sendMessage(MessageService.component("command.reload_no_perm", Map.of()));
            return true;
        }
        boolean ok = MaggoteersPlugin.getInstance().reloadConfigs();
        sender.sendMessage(MessageService.component(ok ? "command.reload_ok" : "command.reload_fail", Map.of()));
        return true;
    }

    private boolean debug(Player p, String[] args) {
        if (!debugAllowed(p)) {
            p.sendMessage(Component.text("无权限（需 maggoteers.admin，或 config debug.enabled=true）。", NamedTextColor.RED));
            return true;
        }
        if (args.length < 2) {
            p.sendMessage(Component.text(
                    "用法：/maggoteers debug <state|plan|wave|freeze|aura|act|give|coin|balance|unlock|jumpto|jump|spawnmob|effects|codex> [...]",
                    NamedTextColor.GRAY));
            return true;
        }
        var pe = new PlayerExt(p);
        MaggoteersGame game = PlayerStateManager.gameForPlayer(p.getUniqueId());
        if (game == null && pe.isInGame() && pe.getGame() instanceof MaggoteersGame mg) game = mg;
        switch (args[1].toLowerCase()) {
            case "state" -> {
                if (game == null) { msg(p, "不在局内。"); return true; }
                PlayerState st = PlayerStateManager.get(game, p.getUniqueId());
                if (st == null) { msg(p, "无 PlayerState。"); return true; }
                msg(p, "reviveCount=" + st.getReviveCount() + " alive=" + st.isAlive()
                        + " gamemode=" + p.getGameMode().name()
                        + " effects=" + st.effects().size() + " acquiredUnique=" + st.acquiredUnique().size());
            }
            case "plan" -> {
                if (game == null) { msg(p, "不在局内。"); return true; }
                int[] prog = WaveScheduler.progress(game);
                msg(p, "actIndex=" + prog[0] + " waveIndex=" + prog[1]);
                List<ActPlan> acts = game.getPlannedActs();
                if (acts == null) { msg(p, "剧本尚未就绪。"); return true; }
                RunConfig rc = RunPlanner.loadRunConfig(io.mczju.maggoteers.MaggoteersPlugin.getInstance());
                for (int ai = 0; ai < acts.size(); ai++) {
                    ActPlan act = acts.get(ai);
                    String actId = act.actId();
                    int weak = rc.weak(actId);
                    int strong = rc.strong(actId);
                    StringBuilder line = new StringBuilder("层").append(ai + 1).append(" ").append(act.mapId()).append("：");
                    for (int wi = 0; wi < act.waves().size(); wi++) {
                        String tier = wi < weak ? "weak" : (wi < weak + strong ? "strong" : "boss");
                        line.append(" [").append(wi + 1).append(":").append(tier).append("=")
                                .append(act.waves().get(wi).strategyId()).append("]");
                    }
                    msg(p, line.toString());
                }
            }
            // /maggoteers debug aura —— 打印自己身上的 AURA 解析结果 + 附近怪身上的药水剩余时长
            case "aura" -> {
                if (!probeEnabled()) { msg(p, PROBE_OFF); return true; }
                var st = String.valueOf(p.getUniqueId());
                int auras = 0;
                if (game != null) {
                    var ps = io.mczju.maggoteers.state.PlayerStateManager.get(game, p.getUniqueId());
                    if (ps != null) {
                        for (var e : ps.effects()) {
                            if (e.effect() != io.mczju.maggoteers.effect.Effect.AURA) continue;
                            var gp = io.mczju.maggoteers.effect.AuraParams.grantParams(e.params());
                            String line = "AURA " + e.id()
                                    + " radius=" + io.mczju.maggoteers.effect.AuraParams.radius(e.params())
                                    + " grant=" + io.mczju.maggoteers.effect.AuraParams.grantEffect(e.params())
                                    + " potion=" + gp.get(io.mczju.maggoteers.effect.EffectKeys.POTION_NAME)
                                    + " amp=" + gp.getOrDefault(io.mczju.maggoteers.effect.EffectKeys.AMP, 0)
                                    + " duration_ticks=" + gp.getOrDefault(io.mczju.maggoteers.effect.EffectKeys.DURATION_TICKS, 0)
                                    + " ignite_ticks=" + gp.getOrDefault(io.mczju.maggoteers.effect.EffectKeys.IGNITE_TICKS, 0);
                            msg(p, line);
                            io.mczju.maggoteers.MaggoteersPlugin.getInstance().getLogger().info("[debug aura] " + st + " " + line);
                            auras++;
                        }
                    }
                }
                if (auras == 0) msg(p, "你身上没有 AURA 效果。");
                int mobs = 0;
                for (org.bukkit.entity.Entity en : p.getWorld().getNearbyEntities(p.getLocation(), 12, 12, 12)) {
                    if (!(en instanceof org.bukkit.entity.LivingEntity le) || le instanceof org.bukkit.entity.Player) continue;
                    StringBuilder pots = new StringBuilder();
                    for (var pot : le.getActivePotionEffects()) {
                        pots.append(pot.getType().getKey().getKey()).append(" ").append(pot.getDuration()).append("t ");
                    }
                    String line = le.getType() + " dist=" + String.format("%.1f", le.getLocation().distance(p.getLocation()))
                            + " potions=[" + pots.toString().trim() + "]";
                    msg(p, line);
                    io.mczju.maggoteers.MaggoteersPlugin.getInstance().getLogger().info("[debug aura] " + st + " " + line);
                    if (++mobs >= 8) break;
                }
                return true;
            }
            // /maggoteers debug freeze [秒] —— 对身边 8 格的怪续冻，并回报 freezeTicks（排查寒冷看不到时用）
            case "freeze" -> {
                if (!probeEnabled()) { msg(p, PROBE_OFF); return true; }
                int seconds = args.length >= 3 ? Math.max(1, Integer.parseInt(args[2])) : 10;
                int ticks = seconds * 20;
                double radius = 8.0;
                int n = 0;
                for (org.bukkit.entity.Entity e : p.getWorld().getNearbyEntities(p.getLocation(), radius, radius, radius)) {
                    if (!(e instanceof org.bukkit.entity.LivingEntity le) || le instanceof org.bukkit.entity.Player) continue;
                    if (le.isDead() || !le.isValid()) continue;
                    int before = le.getFreezeTicks();
                    io.mczju.maggoteers.effect.FreezeService.apply(le, ticks);
                    n++;
                    String line = le.getType() + " freezeTicks " + before + " -> " + le.getFreezeTicks()
                            + " (max " + le.getMaxFreezeTicks() + ")";
                    msg(p, line);
                    io.mczju.maggoteers.MaggoteersPlugin.getInstance().getLogger().info("[debug freeze] " + line);
                }
                if (n == 0) msg(p, "半径 " + radius + " 内没有可冻的实体。");
                else msg(p, "已续冻 " + n + " 个实体 " + seconds + " 秒（满冻线 >= maxFreezeTicks 才会显示白霜/发抖）。");
                return true;
            }
            case "wave" -> {
                if (game == null) { msg(p, "不在局内。"); return true; }
                // /maggoteers debug wave <strategyId> → 把当前波换成同一池里的指定 strategy 并重开这一波
                if (args.length >= 3) {
                    String err = WaveScheduler.debugReplaceWave(game, args[2]);
                    if (err != null) { msg(p, err); return true; }
                    var snap = WaveScheduler.snapshot(game);
                    msg(p, "已换波并重开：" + args[2]
                            + "（" + (snap == null ? "?" : snap.actId() + "/" + snap.lastTier())
                            + " 第 " + (snap == null ? "?" : String.valueOf(snap.waveIndex() + 1)) + " 波）");
                    return true;
                }
                var snap = WaveScheduler.snapshot(game);
                int living = WaveEngine.livingCount(game);
                msg(p, "act=" + (snap == null ? "?" : snap.actIndex()) + " tier=" + (snap == null ? "?" : snap.lastTier())
                        + " rest=" + WaveScheduler.isRestPhase(game) + " livingMobs=" + living);
                msg(p, "换波：/maggoteers debug wave <strategyId>　可用=" 
                        + String.join(", ", WaveScheduler.currentPoolStrategyIds(game)));
            }
            case "act" -> {
                if (game == null) { msg(p, "不在局内。"); return true; }
                int[] prog = WaveScheduler.progress(game);
                List<ActPlan> planned = game.getPlannedActs();
                String actName = planned != null && prog[0] >= 0 && prog[0] < planned.size()
                        ? planned.get(prog[0]).actId() : ("#" + (prog[0] + 1));
                int total = planned == null ? 0 : planned.size();
                msg(p, "当前层=" + actName + "（" + (prog[0] + 1) + "/" + total + "）  当前层波=" + (prog[1] + 1));
            }
            case "give" -> {
                // /maggoteers debug give <reward_id|item_id> [amount]
                // reward_id → 局内应用奖励（STAT 效果+护符 / WEAPON·SUPPLY 物品）
                // item_id → 仅发 ItemCreator 物品（可省略 maggoteers: 前缀）
                if (args.length < 3) {
                    msg(p, "用法：/maggoteers debug give <reward_id|item_id> [amount]");
                    return true;
                }
                String id = args[2];
                int amt = args.length > 3 ? safeInt(args[3], 1) : 1;
                var reward = RewardService.findOptionById(id);
                if (reward.isPresent()) {
                    if (game == null) {
                        msg(p, "奖励需在局内发放：" + id);
                        return true;
                    }
                    RewardOption opt = reward.get();
                    boolean ok = RewardService.apply(p, opt, game);
                    if (!ok) msg(p, "奖励应用失败：" + id);
                    // 成功时 RewardService.apply 已发「获得：…」
                    return true;
                }
                if (ItemService.give(p, id, amt)) {
                    msg(p, "已给物品 " + id + " ×" + amt);
                } else {
                    msg(p, "失败：未知奖励/物品 id（" + id + "）。奖励例 a1b_atk150；物品例 maggoteers:herafinger");
                }
            }
            case "coin" -> {
                // /maggoteers debug coin [normal|boss] [amount]
                // 2026-10-26：支持星石（Boss 货币 currency_boss / NETHER_STAR），此前只能发普通币
                ItemKind kind = ItemKind.CURRENCY_NORMAL;
                int amt = 5;
                String a2 = args.length > 2 ? args[2].toLowerCase(java.util.Locale.ROOT) : "";
                if ("boss".equals(a2) || "star".equals(a2) || "星石".equals(a2)) {
                    kind = ItemKind.CURRENCY_BOSS;
                    amt = args.length > 3 ? safeInt(args[3], 5) : 5;
                } else if ("normal".equals(a2) || "gold".equals(a2) || "金币".equals(a2)) {
                    amt = args.length > 3 ? safeInt(args[3], 5) : 5;
                } else if (!a2.isEmpty()) {
                    amt = safeInt(args[2], 5);
                }
                String label = kind == ItemKind.CURRENCY_BOSS ? "星石（Boss 货币）" : "普通卫戍币";
                if (!ItemService.giveKind(p, kind, amt)) {
                    msg(p, "发放" + label + "失败（物品未定义？检查 config.yml items）");
                } else {
                    msg(p, "已给" + label + " ×" + amt);
                }
            }
            case "balance" -> {
                // /maggoteers debug balance [amount] —— 局外商店账户货币（默认设为 500）
                int amt = args.length > 2 ? safeInt(args[2], 500) : 500;
                MaggoteersPlayerData data = pe.getData(MaggoteersPlayerData.class);
                if (data == null) { msg(p, "无持久化玩家数据。"); return true; }
                data.setBalance(amt);
                msg(p, "账户余额已设为 " + data.balance + "（可用 /maggoteers shop）");
            }
            case "unlock" -> {
                // /maggoteers debug unlock <option_id|all>
                if (args.length < 3) { msg(p, "用法：/maggoteers debug unlock <option_id|all>"); return true; }
                MaggoteersPlayerData data = pe.getData(MaggoteersPlayerData.class);
                if (data == null) { msg(p, "无持久化玩家数据。"); return true; }
                String key = args[2];
                if ("all".equalsIgnoreCase(key)) {
                    int n = 0;
                    for (String poolId : RewardService.allPoolIds()) {
                        var pool = RewardService.pool(poolId);
                        if (pool == null) continue;
                        for (RewardOption o : pool.options()) {
                            if (o.requiresUnlock() && data.unlock(o.id())) n++;
                        }
                    }
                    msg(p, "已解锁商店商品 ×" + n + "（已有不重复计）");
                } else if (data.unlock(key)) {
                    msg(p, "已解锁：" + key);
                } else {
                    msg(p, "解锁失败（已拥有或 id 空）：" + key);
                }
            }
            case "jumpto" -> {
                if (game == null) { msg(p, "不在局内。"); return true; }
                if (args.length < 4) { msg(p, "用法：/maggoteers debug jumpto <层1-3> <波1-n>"); return true; }
                int act = safeInt(args[2], 1) - 1;
                int wave = safeInt(args[3], 1) - 1;
                if (!WaveScheduler.debugSeekWave(game, act, wave)) msg(p, "跳转失败（层/波越界或未开战）。");
                else msg(p, "已跳到 层" + (act + 1) + " 波" + (wave + 1));
            }
            case "jump" -> {
                if (game == null) { msg(p, "不在局内。"); return true; }
                int delta = args.length > 2 ? safeInt(args[2], 1) : 1;
                if (!WaveScheduler.debugShiftWave(game, delta)) msg(p, "快进/快退失败。");
                else {
                    int[] prog = WaveScheduler.progress(game);
                    msg(p, "当前 层" + (prog[0] + 1) + " 波" + (prog[1] + 1));
                }
            }
            case "spawnmob" -> {
                if (game == null) { msg(p, "不在局内。"); return true; }
                if (args.length < 3) { msg(p, "用法：/maggoteers debug spawnmob <实体> [技能...]"); return true; }
                EntityType type;
                try {
                    type = io.mczju.maggoteers.util.GameRegistries.entityType(args[2]);
                } catch (Exception e) {
                    msg(p, "未知实体：" + args[2]);
                    return true;
                }
                List<String> skills = new ArrayList<>();
                for (int i = 3; i < args.length; i++) skills.add(args[i]);
                // 1.3.3：debug 生成怪叠加当局难度倍率（普通=恒等），供难度冒烟观测
                var snap = ScalingConfig.getInstance().scaleFor(game.getPlayers().size(), game.getDifficulty());
                var ms = Compose.compose(new io.mczju.maggoteers.config.CoeffCfg(1, 1, 1), snap);
                var le = MobFactory.spawnDebugMob(p.getLocation(), type, ms.hp(), ms.dmg(), ms.speed(), skills);
                if (le != null) WaveEngine.trackDebugMob(game, le);
                msg(p, le == null ? "生成失败。" : "已生成 " + type.name() + "（技能=" + skills + "）");
            }
            case "effects" -> {
                if (game == null) { msg(p, "不在局内。"); return true; }
                Player target = p;
                if (args.length >= 3) {
                    target = org.bukkit.Bukkit.getPlayerExact(args[2]);
                    if (target == null) { msg(p, "找不到玩家：" + args[2]); return true; }
                }
                PlayerState st = PlayerStateManager.get(game, target.getUniqueId());
                if (st == null) { msg(p, "无 PlayerState。"); return true; }
                if (st.effects().isEmpty()) { msg(p, target.getName() + " 无增益。"); return true; }
                msg(p, target.getName() + " 增益 ×" + st.effects().size() + "：");
                for (PlayerEffect ef : st.effects()) {
                    Effect kind = ef.effect();
                    String trig = ef.fireTrigger() == null ? "常驻" : ef.fireTrigger().name();
                    msg(p, " - " + ef.id() + " " + kind.name() + " lv" + ef.level() + " 触发=" + trig);
                }
            }
            case "counter" -> {
                // 计数器 → 物品冷却遮罩探针（2026-10-28）：看计数器进度/归属物品/组件 group
                if (game == null) { msg(p, "不在局内。"); return true; }
                Player target = p;
                if (args.length >= 3) {
                    target = org.bukkit.Bukkit.getPlayerExact(args[2]);
                    if (target == null) { msg(p, "找不到玩家：" + args[2]); return true; }
                }
                PlayerState st = PlayerStateManager.get(game, target.getUniqueId());
                if (st == null) { msg(p, "无 PlayerState。"); return true; }
                String line = io.mczju.maggoteers.effect.CounterDisplayService.describe(st);
                msg(p, target.getName() + " " + line);
                MaggoteersPlugin.getInstance().getLogger().info("[debug counter] " + target.getName() + " " + line);
            }
            case "boss" -> {
                if (game == null) { msg(p, "不在局内。"); return true; }
                java.util.List<String> lines = new java.util.ArrayList<>();
                for (org.bukkit.entity.LivingEntity le : io.mczju.maggoteers.wave.WaveEngine.trackedBosses(game)) {
                    int phase = io.mczju.maggoteers.mob.BossPhaseTracker.phaseOf(le.getUniqueId());
                    lines.add(le.getType() + " hp=" + String.format("%.1f", le.getHealth()) + "/"
                            + String.format("%.1f", le.getMaxHealth())
                            + " phase=" + phase + " tracked=" + io.mczju.maggoteers.wave.WaveEngine.isTracked(le.getUniqueId()));
                    for (var spec : io.mczju.maggoteers.mob.MobSkillService.skillsOf(le.getUniqueId())) {
                        lines.add("  - " + spec.id() + " trigger=" + spec.trigger()
                                + " phases=" + spec.phases()
                                + (spec.damageGate() == null ? "" : " " + spec.damageGate().describe()));
                    }
                }
                if (lines.isEmpty()) { msg(p, "场上没有被追踪的 Boss（先 debug spawnmob 或进 Boss 波）。"); return true; }
                var log = MaggoteersPlugin.getInstance().getLogger();
                lines.forEach(l -> { msg(p, l); log.info("[debug boss] " + l); });
            }
            case "hp" -> {
                // 血量/属性上限探针：spigot.yml settings.attribute.maxHealth.max 到底有没有生效？
                var hpAttr = p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
                msg(p, "你：MAX_HEALTH base=" + (hpAttr == null ? "?" : hpAttr.getBaseValue())
                        + " value=" + (hpAttr == null ? "?" : hpAttr.getValue())
                        + " hp=" + p.getHealth());
                org.bukkit.entity.LivingEntity probe = null;
                try {
                    probe = p.getWorld().spawn(p.getLocation(), org.bukkit.entity.Bat.class);
                    var pa = probe.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
                    if (pa != null) {
                        pa.setBaseValue(100_000.0);
                        double got = pa.getValue();
                        msg(p, "上限探针（临时蝙蝠）：want=100000 → got=" + got
                                + (got > 1024.0 ? "　⇒ 上限已放开 ✅"
                                : "　⇒ 仍被钳在 1024 ⚠ 检查 spigot.yml settings.attribute.maxHealth.max（改完必须重启）"));
                    }
                } catch (RuntimeException ex) {
                    msg(p, "上限探针失败：" + ex.getMessage());
                } finally {
                    if (probe != null) probe.remove();
                }
            }
            case "codex" -> {
                // /maggoteers debug codex [reset] —— 看图鉴进度账本 / 清空重测三态
                MaggoteersPlayerData data = pe.getData(MaggoteersPlayerData.class);
                if (data == null) { msg(p, "无持久化玩家数据。"); return true; }
                if (args.length >= 3 && "reset".equalsIgnoreCase(args[2])) {
                    io.mczju.maggoteers.codex.CodexProgress.reset(data);
                    msg(p, "图鉴进度已清空（武器/藏品/首领/层）。");
                    return true;
                }
                msg(p, "图鉴：武器 " + data.obtainedWeapons.size()
                        + " / 藏品 " + data.obtainedCollectibles.size()
                        + " / 首领 " + data.metBosses.size()
                        + " / 层 " + data.visitedActs.size());
                msg(p, "obtainedWeapons=" + data.obtainedWeapons);
                msg(p, "obtainedCollectibles=" + data.obtainedCollectibles);
                msg(p, "metBosses=" + data.metBosses);
                msg(p, "visitedActs=" + data.visitedActs);
                msg(p, "清空重测：/maggoteers debug codex reset");
            }
            case "achievements" -> {
                // /maggoteers debug achievements [unlock <id>] —— 看注册情况/解锁状态，或手动解锁一条做验证
                var mgr = com.github.mczjuops.mczjugamecore.MCZJUGameCore.getAchievementManager();
                if (mgr == null) { msg(p, "MGC 没有成就管理器（版本低于 1.5.0？）。"); return true; }
                var defs = io.mczju.maggoteers.achievement.AchievementService.defs();
                if (args.length >= 4 && "unlock".equalsIgnoreCase(args[2])) {
                    String id = args[3];
                    boolean ok = io.mczju.maggoteers.achievement.AchievementService.unlock(p, id);
                    msg(p, ok ? ("已解锁并已发提示：" + id)
                            : ("没解锁：" + id + "（id 不存在 / 已解锁过 / 看控制台日志）"));
                    return true;
                }
                msg(p, "成就定义：" + defs.size() + " 个（配置里写的）");
                int unlocked = 0;
                for (var d : defs.values()) {
                    boolean got;
                    try {
                        var prog = mgr.getProgress(p.getUniqueId(),
                                new com.github.mczjuops.mczjugamecore.achievement.AchievementKey(
                                        io.mczju.maggoteers.achievement.AchievementService.GAME_ID, d.id()));
                        got = prog != null && prog.unlocked();
                    } catch (RuntimeException ex) {
                        msg(p, "  " + d.id() + " 查询失败：" + ex.getMessage());
                        continue;
                    }
                    if (got) unlocked++;
                    msg(p, (got ? "  [已解锁] " : "  [  未解锁] ") + d.id() + "  " + d.name()
                            + "  (" + d.rule() + ")");
                }
                msg(p, "已解锁 " + unlocked + " / " + defs.size());
                try {
                    long wins = com.github.mczjuops.mczjugamecore.MCZJUGameCore.getGameStatisticsManager()
                            .get(p.getUniqueId(),
                                    io.mczju.maggoteers.achievement.AchievementService.GAME_ID,
                                    com.github.mczjuops.mczjugamecore.statistics.GameStatistic.WINS);
                    msg(p, "MGC 统计 WINS=" + wins + "（区区之星：通关 100 局）");
                } catch (RuntimeException ex) {
                    msg(p, "读 WINS 统计失败：" + ex.getMessage());
                }
            }
            default -> msg(p, "未知 debug 子命令：" + args[1]);
        }
        return true;
    }

    /** 正式服仅 admin；内部测试 config debug.enabled=true 时 play 即可。 */
    private static boolean debugAllowed(Player p) {
        if (p.hasPermission("maggoteers.admin")) return true;
        return MaggoteersPlugin.getInstance().getConfig().getBoolean("debug.enabled", false)
                && p.hasPermission("maggoteers.play");
    }

    // ==================== Tab 补全 ====================

    /** 一级子命令（与 {@link #onCommand} 的分派一致）。 */
    /** 2026-10-29：调试探针开关（config.yml {@code debug.probe}，默认关）。 */
    private static final String PROBE_OFF =
            "调试探针已关闭：把 config.yml 的 debug.probe 改成 true 再用（会往日志里刷逐条信息）。";

    private static boolean probeEnabled() {
        var plugin = io.mczju.maggoteers.MaggoteersPlugin.getInstance();
        return plugin != null && plugin.getConfig().getBoolean("debug.probe", false);
    }

    static final List<String> TOP_LEVEL = List.of("shop", "codex", "achievements", "difficulty", "time", "reload", "debug");

    /** `debug` 二级子命令（与 {@link #debug} 的 switch 一致）。 */
    static final List<String> DEBUG_SUBCOMMANDS = List.of(
            "state", "plan", "wave", "act", "give", "coin", "balance", "unlock",
            "jumpto", "jump", "spawnmob", "effects", "hp", "codex", "achievements");

    /**
     * 前缀过滤（大小写不敏感，结果稳定排序）。抽成静态包内方法便于单测。
     *
     * @param current 玩家已经打了一半的参数（null/空 = 列出全部）
     */
    static List<String> filterCompletion(List<String> pool, String current) {
        if (pool == null || pool.isEmpty()) return List.of();
        String p = current == null ? "" : current.toLowerCase(java.util.Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String s : pool) {
            if (s != null && s.toLowerCase(java.util.Locale.ROOT).startsWith(p)) out.add(s);
        }
        java.util.Collections.sort(out);
        return out.size() > 400 ? new ArrayList<>(out.subList(0, 400)) : out;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd,
                                      @NotNull String alias, @NotNull String[] args) {
        try {
            if (args.length <= 1) {                                  // /maggoteers <一级>
                return filterCompletion(TOP_LEVEL, args.length == 0 ? "" : args[0]);
            }
            String first = args[0].toLowerCase(java.util.Locale.ROOT);
            if ("difficulty".equals(first)) {
                if (args.length == 2) {
                    return filterCompletion(List.of("normal", "hard", "expert"), args[1]);
                }
                return List.of();
            }
            if ("reload".equals(first)) return List.of();
            if (!"debug".equals(first)) return List.of();

            if (args.length == 2) return filterCompletion(DEBUG_SUBCOMMANDS, args[1]);
            String sub = args[1].toLowerCase(java.util.Locale.ROOT);
            String cur = args[args.length - 1];
            MaggoteersGame game = sender instanceof Player p
                    ? PlayerStateManager.gameForPlayer(p.getUniqueId()) : null;
            return switch (sub) {
                // 当前波所在池的 strategy id（这就是"能换的波"全部集合）
                case "wave" -> args.length == 3
                        ? filterCompletion(game == null ? List.of() : WaveScheduler.currentPoolStrategyIds(game), cur)
                        : List.of();
                case "jumpto" -> args.length == 3
                        ? filterCompletion(List.of("1", "2", "3", "4"), cur)
                        : args.length == 4 ? filterCompletion(List.of("1", "2", "3", "4", "5", "6"), cur) : List.of();
                case "jump" -> args.length == 3
                        ? filterCompletion(List.of("-3", "-2", "-1", "1", "2", "3"), cur) : List.of();
                case "spawnmob" -> args.length == 3
                        ? filterCompletion(livingEntityTypes(), cur)
                        : filterCompletion(new ArrayList<>(io.mczju.maggoteers.mob.MobSkillRegistry.all().keySet()), cur);
                case "give" -> args.length == 3 ? filterCompletion(rewardIds(), cur) : List.of();
                case "coin" -> args.length == 3
                        ? filterCompletion(List.of("normal", "boss"), cur) : List.of();
                case "unlock" -> {
                    if (args.length != 3) yield List.of();
                    List<String> ids = new ArrayList<>();
                    ids.add("all");
                    ids.addAll(unlockableIds());
                    yield filterCompletion(ids, cur);
                }
                case "effects" -> args.length == 3
                        ? filterCompletion(onlinePlayerNames(), cur) : List.of();
                case "counter" -> args.length == 3
                        ? filterCompletion(onlinePlayerNames(), cur) : List.of();
                default -> List.of();
            };
        } catch (RuntimeException ex) {
            return List.of();     // 补全永不抛（异常会让客户端报"补全失败"）
        }
    }

    /** 可被 `debug spawnmob` 生成的活体实体类型名（升序）。 */
    private static List<String> livingEntityTypes() {
        List<String> out = new ArrayList<>();
        for (EntityType t : EntityType.values()) {
            if (t.isAlive() && t.isSpawnable()) out.add(t.name());
        }
        return out;
    }

    /**
     * 所有奖励池里的 option id（含 bundle 子项），用于 `debug give` 补全。
     * <p>2026-10-28：**排除难度诅咒池**（{@code curse_*}）——诅咒不是"能给的藏品"，
     * 补全里塞一堆 {@code curse_x1_thirst_c1} 既碍眼又容易误发。
     */
    private static List<String> rewardIds() {
        List<String> out = new ArrayList<>();
        for (String poolId : RewardService.allPoolIds()) {
            if (poolId.startsWith("curse")) continue;
            var pool = RewardService.pool(poolId);
            if (pool == null) continue;
            for (RewardOption o : pool.options()) {
                addOptionIds(out, o);
            }
        }
        return out;
    }

    private static void addOptionIds(List<String> out, RewardOption o) {
        if (o == null) return;
        if (o.id() != null && !o.id().isBlank()) out.add(o.id());
        for (RewardOption g : o.grants()) addOptionIds(out, g);
    }

    /** 需要局外解锁的 option id（`debug unlock` 补全）。 */
    private static List<String> unlockableIds() {
        List<String> out = new ArrayList<>();
        for (String poolId : RewardService.allPoolIds()) {
            var pool = RewardService.pool(poolId);
            if (pool == null) continue;
            for (RewardOption o : pool.options()) {
                if (o.requiresUnlock() && o.id() != null) out.add(o.id());
            }
        }
        return out;
    }

    private static List<String> onlinePlayerNames() {
        List<String> out = new ArrayList<>();
        for (Player p : org.bukkit.Bukkit.getOnlinePlayers()) out.add(p.getName());
        return out;
    }

    private static void usage(Player p) {
        p.sendMessage(MessageService.component("command.usage", Map.of()));
    }

    private static void msg(Player p, String text) {
        p.sendMessage(Component.text(text, NamedTextColor.AQUA));
    }

    private static int safeInt(String s, int def) {
        try { return Integer.parseInt(s); } catch (Exception e) { return def; }
    }
}
