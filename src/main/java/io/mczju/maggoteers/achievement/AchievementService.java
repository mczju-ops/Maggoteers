package io.mczju.maggoteers.achievement;

import com.github.mczjuops.mczjugamecore.MCZJUGameCore;
import com.github.mczjuops.mczjugamecore.achievement.AchievementDefinition;
import com.github.mczjuops.mczjugamecore.achievement.AchievementKey;
import com.github.mczjuops.mczjugamecore.achievement.AchievementManager;
import com.github.mczjuops.mczjugamecore.achievement.AchievementUpdateResult;
import com.github.mczjuops.mczjugamecore.game.AbstractGame;
import com.github.mczjuops.mczjugamecore.player.PlayerExt;
import com.github.mczjuops.mczjugamecore.statistics.GameStatistic;
import io.mczju.maggoteers.MaggoteersPlugin;
import io.mczju.maggoteers.effect.PlayerCombatStats;
import io.mczju.maggoteers.game.MaggoteersGame;
import io.mczju.maggoteers.item.ItemKind;
import io.mczju.maggoteers.item.ItemService;
import io.mczju.maggoteers.persist.MaggoteersPlayerData;
import io.mczju.maggoteers.reward.RewardOption;
import io.mczju.maggoteers.reward.RewardService;
import io.mczju.maggoteers.state.PlayerState;
import io.mczju.maggoteers.state.PlayerStateManager;
import io.mczju.maggoteers.util.GameDifficulty;
import io.mczju.maggoteers.config.MessageService;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.logging.Logger;

/**
 * 成就系统的门面：**注册**（把 {@code achievements.yml} 变成 MGC 的 AchievementDefinition）、
 * **解锁**（幂等）、以及各个**挂点回调**。
 *
 * <p>判定逻辑本身在 {@link AchievementRules}（纯函数、可单测）；这里只做"取现值 → 问规则 → 解锁"。
 *
 * <p>⚠️ 解锁一律按 **uuid** 走 MGC，不广播、不遍历全服——玩家看不到别人的解锁。
 */
public final class AchievementService {

    private static final Logger LOG = Logger.getLogger("Maggoteers");

    /** MGC 成就的命名空间（= 本插件的游戏 id）。 */
    public static final String GAME_ID = "maggoteers";

    /** 自伤的有效窗：被打中后 10 秒内死亡算"被自己的效果打死"。 */
    private static final long SELF_DAMAGE_WINDOW_MS = 10_000L;

    private static Map<String, AchievementDef> defs = Map.of();
    private static MaggoteersPlugin plugin;
    /** 已经成功注册过（自愈逻辑用）。 */
    private static final java.util.concurrent.atomic.AtomicBoolean registered =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    /** 报过错的成就 id（同一 id 只喊一次，避免战斗里刷屏）。 */
    private static final Set<String> reportedFailures = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private AchievementService() {}

    // ================================================================ 生命周期
    public static void load(MaggoteersPlugin p) {
        plugin = p;
        defs = AchievementConfig.load(p).defs();
        register();
    }

    public static void disable() {
        if (plugin == null) return;
        try {
            AchievementManager mgr = MCZJUGameCore.getAchievementManager();
            if (mgr != null) mgr.unregister(plugin);
        } catch (RuntimeException ex) {
            LOG.warning("注销成就失败：" + ex.getMessage());
        }
        plugin = null;
    }

    public static Map<String, AchievementDef> defs() {
        return defs;
    }

    private static AchievementKey key(String id) {
        return new AchievementKey(GAME_ID, id);
    }

    private static AchievementManager manager() {
        try {
            return MCZJUGameCore.getAchievementManager();
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }

    private static void register() {
        AchievementManager mgr = manager();
        if (mgr == null) {
            LOG.warning("MGC 没有成就管理器（版本太旧？），成就系统已停用");
            return;
        }
        if (plugin == null) return;
        // MGC 要求「先 registerGame 再注册成就」（advancement 按游戏分命名空间）。
        // onEnable 的第一遍配置加载发生在 registerGame 之前 —— 那一遍安静跳过，
        // 由延后一 tick 的第二遍（此时游戏已注册）真正注册。
        if (!gameRegistered()) {
            LOG.fine("游戏尚未注册，成就注册延后到第二遍配置加载");
            return;
        }
        List<AchievementDefinition> list = new ArrayList<>();
        for (AchievementDef d : defs.values()) {
            Material icon = Material.matchMaterial(d.iconKey());
            if (icon == null) {
                LOG.warning("成就 " + d.id() + " 的图标不是合法材质：" + d.iconKey() + "，已跳过");
                continue;
            }
            AchievementDefinition def;
            if (AchievementRules.STATISTIC.equals(d.rule())) {
                GameStatistic stat;
                try {
                    stat = GameStatistic.valueOf(d.stat());
                } catch (IllegalArgumentException ex) {
                    LOG.warning("成就 " + d.id() + " 的 stat 不合法：" + d.stat() + "，已跳过");
                    continue;
                }
                def = AchievementDefinition.statistic(key(d.id()), d.name(), d.description(),
                        icon, stat, d.longTarget());
            } else {
                def = AchievementDefinition.challenge(key(d.id()), d.name(), d.description(), icon);
            }
            list.add(def);
        }
        try {
            mgr.register(plugin, list);
            registered.set(true);
            LOG.info("成就系统：已注册 " + list.size() + " 个成就（" + GAME_ID + "）");
        } catch (RuntimeException ex) {
            LOG.warning("注册成就失败：" + ex.getMessage());
        }
    }

    /**
     * 打开成就界面（MGC 的 `/mgc achievements` 不带游戏 id → 过滤出空列表，玩家会看到"暂无成就"）。
     * 所以本插件自己给一个入口：`/maggoteers achievements`。
     */
    public static boolean openMenu(Player p) {
        if (p == null) return false;
        try {
            new com.github.mczjuops.mczjugamecore.achievement.AchievementMenu(p, GAME_ID, false).open();
            return true;
        } catch (RuntimeException | LinkageError ex) {
            LOG.warning("打开成就界面失败：" + ex.getMessage());
            return false;
        }
    }

    // ================================================================ 解锁
    /** 解锁一个成就（幂等：已解锁的不会再触发一次 toast）。 */
    public static boolean unlock(Player p, String id) {
        if (p == null || id == null || !defs.containsKey(id)) return false;
        AchievementManager mgr = manager();
        if (mgr == null) return false;
        try {
            AchievementUpdateResult r = mgr.unlock(p.getUniqueId(), key(id));
            if (r != null && r.newlyUnlocked()) {
                LOG.info("成就解锁：" + p.getName() + " -> " + id);
                celebrate(p, defs.get(id));
                return true;
            }
        } catch (RuntimeException ex) {
            String msg = String.valueOf(ex.getMessage());
            // 自愈：万一定义还没进 MGC（注册时机不对/被 unregister），补注册一次再重试。
            if (msg.contains("Unregistered") && registered.compareAndSet(false, true)) {
                LOG.warning("成就未注册，补注册后重试：" + id);
                register();
                try {
                    AchievementUpdateResult r2 = mgr.unlock(p.getUniqueId(), key(id));
                    if (r2 != null && r2.newlyUnlocked()) {
                        LOG.info("成就解锁：" + p.getName() + " -> " + id);
                        celebrate(p, defs.get(id));
                        return true;
                    }
                } catch (RuntimeException ex2) {
                    msg = String.valueOf(ex2.getMessage());
                }
            }
            // 同一个 id 只喊一次，别在战斗里每条伤害刷一行
            if (reportedFailures.add(id)) {
                LOG.warning("解锁成就失败 " + id + "：" + msg);
            }
        }
        return false;
    }

    /**
     * **传说成就**的庆祝：放音乐 + 屏幕大字 + 只向本局参与者广播（不惊动全服）。
     *
     * <p>MGC 的成就界面没有"稀有度"概念（它按解锁率自动着色），所以稀有度的**表现**放在这里；
     * 配置项见 `config.yml` 的 `messages.menu.achievement.*`。
     */
    private static void celebrate(Player p, AchievementDef def) {
        if (def == null || !def.legendary()) return;
        try {
            p.showTitle(net.kyori.adventure.title.Title.title(
                    MessageService.component("menu.achievement.legendary_title", Map.of()),
                    MessageService.component("menu.achievement.legendary_subtitle",
                            Map.of("name", def.name())),
                    net.kyori.adventure.title.Title.Times.times(
                            java.time.Duration.ofMillis(400),
                            java.time.Duration.ofSeconds(3),
                            java.time.Duration.ofMillis(800))));
            // 音乐：优先用 config 里点的曲子，解析不到就退回 MGC 同款"挑战完成"音效
            playSound(p, MessageService.raw("menu.achievement.legendary_sound", Map.of()),
                    org.bukkit.SoundCategory.RECORDS);
            playSound(p, MessageService.raw("menu.achievement.legendary_stinger", Map.of()),
                    org.bukkit.SoundCategory.PLAYERS);
            // 广播只在**本局参与者**里发，没有对局就只提示本人
            var game = PlayerStateManager.gameOf(p);
            String line = MessageService.raw("menu.achievement.legendary_broadcast",
                    Map.of("player", p.getName(), "name", def.name()));
            if (game != null && line != null && !line.isBlank()) {
                for (PlayerExt pe : game.getPlayers()) {
                    Player q = pe.player();
                    if (q != null && !q.getUniqueId().equals(p.getUniqueId())) q.sendMessage(Component.text(line));
                }
            }
        } catch (RuntimeException ex) {
            LOG.warning("传说成就庆祝失败 " + def.id() + "：" + ex.getMessage());
        }
    }

    private static void playSound(Player p, String soundName, org.bukkit.SoundCategory category) {
        if (soundName == null || soundName.isBlank()) return;
        try {
            org.bukkit.Sound sound = org.bukkit.Sound.valueOf(
                    soundName.trim().toUpperCase(java.util.Locale.ROOT));
            p.playSound(p.getLocation(), sound, category, 1.0f, 1.0f);
        } catch (IllegalArgumentException ex) {
            LOG.fine("成就音效名不合法：" + soundName);
        }
    }

    /** 给一局里的**所有参与者**解锁（团队型成就，如击败隐藏 Boss）。 */
    public static void unlockForRun(AbstractGame game, String id) {
        if (game == null) return;
        for (PlayerExt pe : game.getPlayers()) {
            Player p = pe.player();
            if (p != null) unlock(p, id);
        }
    }

    /** 取该规则的所有成就定义，逐条问判定。 */
    private static void check(Player p, String rule, Predicate<AchievementDef> ok) {
        for (AchievementDef d : defs.values()) {
            if (!d.rule().equals(rule)) continue;
            boolean pass;
            try {
                pass = ok.test(d);
            } catch (RuntimeException ex) {
                LOG.warning("成就判定异常 " + d.id() + "：" + ex.getMessage());
                continue;
            }
            if (pass) unlock(p, d.id());
        }
    }

    // ================================================================ 挂点：通关
    /**
     * 通关瞬间的批量判定。
     *
     * <p>必须在**切档案/清理之前**调用（`MaggoteersGame.win()` 里、`endGame` 之前），
     * 否则属性、背包货币、局内状态都已经没了。
     */
    public static void onRunWin(MaggoteersGame game) {
        if (game == null || defs.isEmpty()) return;
        boolean expert = game.getDifficulty() == GameDifficulty.EXPERT;
        int started = game.getRunPlayerCount();
        long elapsedMs = game.runElapsedMs();
        Set<String> unlockable = unlockableIds();

        List<Player> participants = new ArrayList<>();
        int aliveNow = 0;
        for (PlayerExt pe : game.getPlayers()) {
            Player p = pe.player();
            if (p == null) continue;
            participants.add(p);
            PlayerState st = PlayerStateManager.get(game, p.getUniqueId());
            if (st != null && st.isAlive()) aliveNow++;
        }
        final int aliveCount = aliveNow;

        for (Player p : participants) {
            PlayerState st = PlayerStateManager.get(game, p.getUniqueId());
            if (st == null) continue;
            UUID id = p.getUniqueId();

            check(p, AchievementRules.REVIVES_AT_WIN,
                    d -> AchievementRules.revivesAtLeast(st.getReviveCount(), d.value()));
            check(p, AchievementRules.TRIPLE_MULTIPLIER, d -> AchievementRules.tripleMultiplier(
                    PlayerCombatStats.attackMultiplier(game, id),
                    PlayerCombatStats.magicDamageMultiplier(game, id),
                    PlayerCombatStats.reloadMultiplier(game, id),
                    d.value()));
            check(p, AchievementRules.FOUR_ALIVE_EXPERT,
                    d -> AchievementRules.fourAliveExpert(expert, started, aliveCount));
            check(p, AchievementRules.ALL_KING_COLLECTIBLES,
                    d -> AchievementRules.kingsCollected(ownedCollectibles(st), d.required()));
            check(p, AchievementRules.RUN_TIME_UNDER_SEC,
                    d -> AchievementRules.runTimeUnderSec(elapsedMs, d.value()));
            check(p, AchievementRules.MAX_HEALTH_AT_LEAST,
                    d -> AchievementRules.maxHealthAtLeast(attribute(p, Attribute.MAX_HEALTH), d.value()));
            check(p, AchievementRules.SPEED_AT_LEAST,
                    d -> AchievementRules.speedAtLeast(attribute(p, Attribute.MOVEMENT_SPEED), d.value()));
            check(p, AchievementRules.SPECTATOR_WIN, d -> !st.isAlive());
            check(p, AchievementRules.COINS_AT_WIN,
                    d -> AchievementRules.coinsAtLeast(
                            ItemService.countKind(p, ItemKind.CURRENCY_NORMAL), d.value()));
            check(p, AchievementRules.UNLOCK_ALL,
                    d -> AchievementRules.allUnlocked(unlocks(p), unlockable));
            check(p, AchievementRules.SOLO_EXPERT, d -> {
                int othersDownedInAct1 = 0;
                int others = 0;
                int aliveOthers = 0;
                for (Player other : participants) {
                    if (other.getUniqueId().equals(id)) continue;
                    others++;
                    PlayerState os = PlayerStateManager.get(game, other.getUniqueId());
                    if (os == null) continue;
                    if (os.isAlive()) aliveOthers++;
                    if (os.firstDownedAct() == 0) othersDownedInAct1++;
                }
                // 只有"其余 3 人全在、全都一层倒下、且都还是旁观"才成立
                return others == 3 && aliveOthers == 0
                        && AchievementRules.soloExpert(expert, started, aliveCount, true,
                        othersDownedInAct1, others);
            });
        }
    }

    // ================================================================ 挂点：事件
    /** 花掉 1 枚普通货币（3 选 1 商店）。 */
    public static void onCoinsSpent(Player p) {
        PlayerState st = stateOf(p);
        if (st == null) return;
        st.incrementCoinsSpent();
        check(p, AchievementRules.SPEND_COINS,
                d -> AchievementRules.spendCoins(st.coinsSpent(), d.value()));
    }

    /** 用复活币救起一名死者。 */
    public static void onTeammateSaved(Player p) {
        PlayerState st = stateOf(p);
        if (st == null) return;
        st.incrementRevivesGiven();
        check(p, AchievementRules.SAVE_TEAMMATES,
                d -> AchievementRules.savesAtLeast(st.revivesGiven(), d.value()));
    }

    /** 对敌人造成伤害（物理事件与魔法伤害两条通道都会走到这里）。 */
    public static void onDamageDealt(Player p, double damage) {
        if (p == null || damage <= 0) return;
        PlayerState st = stateOf(p);
        // 「一次打出 N 点」跟本局状态无关：没有 PlayerState（比如大厅/调试刷怪）也照样判定，
        // 否则玩家会看到"打了 600 点却没成就"。
        if (st != null) st.recordHit(damage);
        double best = st != null ? st.maxHitDealt() : damage;
        check(p, AchievementRules.HIT_AT_LEAST,
                d -> AchievementRules.hitAtLeast(best, d.value()));
    }

    /** 玩家被自己的效果打中（用于"被自伤打死"）。 */
    public static void onSelfDamage(Player p) {
        PlayerState st = stateOf(p);
        if (st != null) st.markSelfDamage();
    }

    /** 玩家复活次数耗尽、转旁观的那一刻。 */
    public static void onPlayerDowned(AbstractGame game, Player p) {
        if (game == null || p == null) return;
        PlayerState st = PlayerStateManager.get(game, p.getUniqueId());
        if (st == null) return;
        st.markFirstDownedAt(st.actsCleared());
        check(p, AchievementRules.SELF_KILL_NO_REVIVE,
                d -> st.selfDamageFresh(SELF_DAMAGE_WINDOW_MS));
    }

    /** 每秒心跳（状态效果数量这类"持续态"成就）。 */
    public static void onSecondTick(Player p) {
        if (p == null || defs.isEmpty() || p.isDead() || !p.isOnline()) return;
        int count = p.getActivePotionEffects().size();
        check(p, AchievementRules.POTION_COUNT,
                d -> AchievementRules.potionCountAtLeast(count, d.value()));
    }

    /** 军需处购买成功。 */
    public static void onShopPurchase(Player p) {
        if (p == null) return;
        Set<String> unlockable = unlockableIds();
        check(p, AchievementRules.UNLOCK_ALL, d -> AchievementRules.allUnlocked(unlocks(p), unlockable));
    }

    /** 隐藏层 Boss 被击败（团队成就）。 */
    public static void onHiddenBossDefeated(AbstractGame game) {
        if (game == null) return;
        for (AchievementDef d : defs.values()) {
            if (AchievementRules.DEFEAT_HIDDEN_BOSS.equals(d.rule())) unlockForRun(game, d.id());
        }
    }

    // ================================================================ 取值helper
    /**
     * 本插件的游戏是否已经 {@code registerGame}（MGC 注册成就的前置）。
     *
     * <p>⚠️ 必须是**已注册的游戏 id**，不能用 {@code getAllGames()}（那是"正在跑的对局实例"）——
     * 2026-10-30 踩过：用实例判会恒为 false，导致成就永远没注册、解锁时报
     * {@code Unregistered achievement}。
     */
    private static boolean gameRegistered() {
        try {
            return MCZJUGameCore.getGameManager().getRegisteredGameIds().contains(GAME_ID);
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    private static PlayerState stateOf(Player p) {
        if (p == null) return null;
        MaggoteersGame game = PlayerStateManager.gameForPlayer(p.getUniqueId());
        return game == null ? null : PlayerStateManager.get(game, p.getUniqueId());
    }

    private static double attribute(Player p, Attribute attr) {
        AttributeInstance inst = p.getAttribute(attr);
        return inst == null ? 0.0 : inst.getValue();
    }

    /** 本局拿到的藏品 id（组合包折回父项，与图鉴/侧边栏同一套口径）。 */
    private static Set<String> ownedCollectibles(PlayerState st) {
        Set<String> owned = new HashSet<>();
        for (var e : st.effects()) {
            String owner = RewardService.collectibleOwnerId(e.id());
            owned.add(owner == null ? e.id() : owner);
        }
        return owned;
    }

    private static Set<String> unlocks(Player p) {
        try {
            MaggoteersPlayerData data = new PlayerExt(p).getData(MaggoteersPlayerData.class);
            return data == null ? Set.of() : new LinkedHashSet<>(data.unlocks);
        } catch (RuntimeException ex) {
            return Set.of();
        }
    }

    /** 军需处全部可买项（= 所有池子里 requires_unlock 的 id，去重）。 */
    private static Set<String> unlockableIds() {
        Set<String> out = new LinkedHashSet<>();
        try {
            for (String poolId : RewardService.allPoolIds()) {
                var pool = RewardService.pool(poolId);
                if (pool == null) continue;
                for (RewardOption o : pool.options()) {
                    if (o.requiresUnlock()) out.add(o.id());
                }
            }
        } catch (RuntimeException ex) {
            LOG.warning("读取军需处商品失败：" + ex.getMessage());
        }
        return out;
    }
}
