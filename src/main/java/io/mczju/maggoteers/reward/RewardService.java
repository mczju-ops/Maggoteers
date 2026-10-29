package io.mczju.maggoteers.reward;

import com.github.mczjuops.mczjugamecore.game.AbstractGame;
import com.github.mczjuops.mczjugamecore.player.PlayerExt;
import io.mczju.maggoteers.MaggoteersPlugin;
import io.mczju.maggoteers.config.MessageService;
import io.mczju.maggoteers.effect.Effect;
import io.mczju.maggoteers.effect.EffectContext;
import io.mczju.maggoteers.effect.EffectKeys;
import io.mczju.maggoteers.effect.EffectService;
import io.mczju.maggoteers.effect.PlayerEffect;
import io.mczju.maggoteers.effect.Stack;
import io.mczju.maggoteers.game.MaggoteersGame;
import io.mczju.maggoteers.item.ItemService;
import io.mczju.maggoteers.state.PlayerState;
import io.mczju.maggoteers.state.PlayerStateManager;
import io.mczju.maggoteers.wave.RewardItem;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.*;
import java.util.logging.Logger;

/** 奖励池加载 + 清波发放 + 选项应用（测试版，Plan 6 扩展 GUI/解锁）。 */
public final class RewardService {
    private static final Logger LOG = Logger.getLogger("Maggoteers");
    private static final Map<String, RewardPool> POOLS = new HashMap<>();
    /** 3 选 1 抽签缓存（R10）：按 (玩家, 池) 缓存上次抽取，ESC 退出不刷新。 */
    private static final Map<UUID, Map<String, List<RewardOption>>> DRAW_CACHE = new HashMap<>();
    /**
     * 2026-10-28：bundle 子项 id → 父项 id。给"计数器/动态属性挂在 grant 上"的藏品找回护符归属
     * （例：诅咒·贫穷的计数器在 {@code curse_x2_poor_tax} 上，但护符只映射父项 {@code curse_x2_poor}）。
     */
    private static final Map<String, String> GRANT_PARENT = new HashMap<>();
    /**
     * 2026-10-28：{@code expiry: {trigger: ON_DEATH}} 的"易碎藏品"（霜晶树）。
     * sidebar 用它显示"完好 / 已破损"（见 {@code CounterDisplayService.addFragileStates}）。
     */
    private static final Set<String> DEATH_FRAGILE = new HashSet<>();

    private RewardService() {}

    public static void load(MaggoteersPlugin plugin) {
        POOLS.clear();
        DRAW_CACHE.clear();
        GRANT_PARENT.clear();
        DEATH_FRAGILE.clear();
        UpgradeLevelCaps.load(plugin);
        File f = new File(plugin.getDataFolder(), "rewards.yml");
        io.mczju.maggoteers.util.PluginFiles.saveResourceIfMissing(plugin, "rewards.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(f);
        ConfigurationSection root = yaml.getConfigurationSection("reward_pools");
        if (root == null) return;
        List<RewardOption> acceptedChestBound = new ArrayList<>();
        for (String poolId : root.getKeys(false)) {
            ConfigurationSection sec = root.getConfigurationSection(poolId);
            if (sec == null) continue;
            int cost = sec.getInt("cost", 1);
            int upgradeLevelCap = sec.getInt("upgrade_level_cap", 0);
            List<RewardOption> options = new ArrayList<>();
            for (var m : sec.getMapList("options")) {
                RewardOption opt = RewardOption.fromMap(m);
                if (opt.category() == RewardOption.Category.STAT && opt.effect() == Effect.BUFF_AREA) {
                    var norm = RewardLoadValidator.validateAndNormalizeBuffArea(opt);
                    if (norm.skipReason().isPresent()) {
                        LOG.warning("rewards.yml 池 " + poolId + " 选项 " + opt.id()
                                + " 已跳过：" + norm.skipReason().get());
                        continue;
                    }
                    opt = opt.withParams(norm.normalizedParams());
                }
                if (opt.category() == RewardOption.Category.STAT && opt.effect() == Effect.DISABLE_AI) {
                    var norm = RewardLoadValidator.validateAndNormalizeDisableAi(opt);
                    if (norm.skipReason().isPresent()) {
                        LOG.warning("rewards.yml 池 " + poolId + " 选项 " + opt.id()
                                + " 已跳过：" + norm.skipReason().get());
                        continue;
                    }
                    opt = opt.withParams(norm.normalizedParams());
                }
                var err = RewardLoadValidator.validateStatOption(opt);
                if (err.isPresent()) {
                    LOG.warning("rewards.yml 池 " + poolId + " 选项 " + opt.id() + " 已跳过：" + err.get());
                    continue;
                }
                var chestConflict = RewardLoadValidator.chestBoundEquipConflict(acceptedChestBound, opt);
                if (chestConflict.isPresent()) {
                    LOG.warning("rewards.yml 池 " + poolId + " 选项 " + opt.id() + " 已跳过：" + chestConflict.get());
                    continue;
                }
                if (opt.effect() == Effect.BOUND_EQUIP) {
                    acceptedChestBound.add(opt);
                }
                if (opt.category() == RewardOption.Category.STAT && !opt.unique()) {
                    LOG.warning("rewards.yml 池 " + poolId + " STAT 选项 " + opt.id()
                            + " 建议声明 unique: true");
                }
                options.add(opt);
            }
            POOLS.put(poolId, new RewardPool(poolId, cost, upgradeLevelCap, options));
        }
        LOG.info("RewardService 已加载 " + POOLS.size() + " 个奖励池。");

        // bundle 子项 → 父项（护符归属/名字回落用，见 GRANT_PARENT）；同时登记"死亡即失效"的易碎藏品
        for (var pool : POOLS.values()) {
            for (var opt : pool.options()) {
                if (opt.id() != null && opt.expiryTrigger() == io.mczju.maggoteers.effect.Trigger.ON_DEATH) {
                    registerDeathFragile(opt.id());
                }
                for (var grant : opt.grants()) {
                    if (grant.id() != null && opt.id() != null) {
                        registerGrantParent(grant.id(), opt.id());
                    }
                }
            }
        }

        for (var pool : POOLS.values()) {
            for (var opt : pool.options()) {
                if (opt.item() != null && !opt.item().isBlank()
                        && !ItemService.hasItem(opt.item())
                        && opt.category() != RewardOption.Category.STAT) {
                    plugin.getLogger().warning("rewards.yml: 池 " + pool.id() + " 的选项 " + opt.id()
                        + " 引用了未知物品: " + opt.item() + "（可能 ItemCreator 未装/物品未定义）");
                }
            }
        }
    }

    /**
     * 2026-10-28：把"效果 id"折算成**有护符映射的那个 id**。
     * <p>bundle 的计数器/动态属性挂在子 grant 上（如 {@code curse_x2_poor_tax}），而
     * {@code collectibles.yml} 只映射父项（{@code curse_x2_poor}）——不折算就会在 sidebar 里
     * 显示原始 id（"curse x2 poor tax"）。没有映射时原样返回。
     */
    public static String collectibleOwnerId(String effectId) {
        if (effectId == null || effectId.isBlank()) return effectId;
        if (CollectibleRegistry.hasMapping(effectId)) return effectId;
        String parent = GRANT_PARENT.get(effectId);
        return parent != null ? parent : effectId;
    }

    /** 登记 bundle 子项 → 父项（加载期用；单测也可直接喂）。 */
    public static void registerGrantParent(String grantId, String parentId) {
        if (grantId == null || parentId == null || grantId.isBlank() || parentId.isBlank()) return;
        GRANT_PARENT.put(grantId, parentId);
    }

    /** 该 option 是否"玩家死亡即失效"（{@code expiry: {trigger: ON_DEATH}}，如霜晶树）。 */
    public static boolean isDeathFragile(String optionId) {
        return optionId != null && DEATH_FRAGILE.contains(optionId);
    }

    /** 登记易碎藏品（加载期用；单测也可直接喂）。 */
    public static void registerDeathFragile(String optionId) {
        if (optionId == null || optionId.isBlank()) return;
        DEATH_FRAGILE.add(optionId);
    }

    public static RewardPool pool(String id) { return POOLS.get(id); }

    /** 按 option id 取当前池内定义（含 bundle 子 grant；reload 后含已解析的属性/药水）。 */
    public static Optional<RewardOption> findOptionById(String optionId) {
        if (optionId == null || optionId.isBlank()) return Optional.empty();
        for (RewardPool pool : POOLS.values()) {
            for (RewardOption opt : pool.options()) {
                if (optionId.equals(opt.id())) return Optional.of(opt);
                for (RewardOption grant : opt.grants()) {
                    if (optionId.equals(grant.id())) return Optional.of(grant);
                }
            }
        }
        return Optional.empty();
    }

    /** 应用 STAT 时用池内最新 params（深拷贝），避免旧 pick 引用缺 grant 字段。 */
    public static EffectContext paramsForApply(RewardOption picked) {
        RewardOption canon = findOptionById(picked.id()).orElse(picked);
        if (canon.params() == null) return new EffectContext();
        return canon.params().copyDeep();
    }

    public static java.util.Set<String> allPoolIds() { return java.util.Collections.unmodifiableSet(POOLS.keySet()); }

    public static void grantClearRewards(Collection<? extends Player> players, List<RewardItem> rewards) {
        if (rewards == null || rewards.isEmpty()) return;
        for (Player p : players) {
            for (RewardItem r : rewards) {
                ItemService.give(p, r.itemId(), r.amount());
            }
        }
    }

    public static List<RewardOption> draw(String poolId, int n, Random rng, Player viewer) {
        RewardPool pool = POOLS.get(poolId);
        if (pool == null || pool.options().isEmpty()) return List.of();
        Set<String> acquired = Set.of();
        PlayerState ps = null;
        if (viewer != null) {
            var pe = new PlayerExt(viewer);
            ps = pe.isInGame() ? PlayerStateManager.get(pe.getGame(), viewer.getUniqueId()) : null;
            if (ps != null) acquired = ps.acquiredUnique();
        }
        java.util.Set<String> unlocked = viewer == null ? java.util.Set.of()
                : io.mczju.maggoteers.unlock.UnlockRegistry.unlocksOf(viewer);
        List<RewardOption> visible = new ArrayList<>();
        for (RewardOption o : pool.options()) {
            if (!RewardDrawVisibility.isVisible(o, ps, acquired, pool)) continue;
            if (o.requiresUnlock() && !unlocked.contains(o.id())) continue;
            visible.add(o);
        }
        Collections.shuffle(visible, rng);
        return visible.subList(0, Math.min(n, visible.size()));
    }

    public static List<RewardOption> draw(String poolId, int n, Random rng) {
        return draw(poolId, n, rng, null);
    }

    /** 抽签（带缓存，R10）：同一玩家同一池重开返回上次选项；购买成功后由 {@link #invalidateDraw} 刷新。 */
    public static List<RewardOption> drawCached(UUID player, String poolId, int n, Random rng, Player viewer) {
        if (player != null && poolId != null) {
            Map<String, List<RewardOption>> byPool = DRAW_CACHE.get(player);
            if (byPool != null) {
                List<RewardOption> cached = byPool.get(poolId);
                if (cached != null) return cached;
            }
        }
        List<RewardOption> drawn = draw(poolId, n, rng, viewer);
        if (player != null && poolId != null) {
            DRAW_CACHE.computeIfAbsent(player, k -> new HashMap<>()).put(poolId, drawn);
        }
        return drawn;
    }

    /** 购买成功后调用：使该玩家该池的抽签缓存失效，下次重开重新抽。 */
    public static void invalidateDraw(UUID player, String poolId) {
        if (player == null || poolId == null) return;
        Map<String, List<RewardOption>> byPool = DRAW_CACHE.get(player);
        if (byPool != null) byPool.remove(poolId);
    }

    /** 玩家退出/对局结束清理其全部抽签缓存。 */
    public static void clearDraws(UUID player) {
        if (player != null) DRAW_CACHE.remove(player);
    }

    public static boolean apply(Player player, RewardOption opt) {
        var pe = new PlayerExt(player);
        if (!pe.isInGame()) return false;
        return apply(player, opt, pe.getGame());
    }

    public static boolean apply(Player player, RewardOption opt, AbstractGame game) {
        return apply(player, opt, game, null);
    }

    public static boolean apply(Player player, RewardOption opt, AbstractGame game, String poolId) {
        if (opt == null || game == null) return false;
        LOG.info("[Reward] apply " + player.getName() + " (" + player.getUniqueId()
                + ") option=" + opt.id() + " pool=" + poolId + " bundle=" + opt.isBundle());
        PlayerState ps = PlayerStateManager.get(game, player.getUniqueId());
        if (ps == null) {
            LOG.warning("RewardService.apply: 无 PlayerState option=" + opt.id() + " uuid=" + player.getUniqueId());
            player.sendMessage(MessageService.component("reward.apply_no_state", Map.of()));
            return false;
        }
        RewardPool pool = poolId == null ? null : POOLS.get(poolId);
        try {
            if (opt.isBundle()) {
                if (!applyBundle(player, opt, game, pool)) {
                    player.sendMessage(MessageService.component("reward.apply_bad_bundle", Map.of()));
                    return false;
                }
            } else if (!applySingle(player, opt, game, pool)) {
                player.sendMessage(MessageService.component("reward.apply_failed", Map.of()));
                return false;
            }
        } catch (Exception ex) {
            LOG.warning("RewardService.apply 失败 " + opt.id() + ": " + ex.getMessage());
            player.sendMessage(MessageService.component("reward.apply_error", Map.of("id", opt.id())));
            return false;
        }
        ps.acquiredUnique().add(opt.id());
        if (opt.isBundle() && game instanceof MaggoteersGame mg) {
            CollectibleService.resync(player, mg);
        }
        // 图鉴（2026-10-30）：拿到即记「已获得」（账号级，跨局累积）。武器与藏品分开记两本账。
        recordCodex(opt, player);
        player.sendMessage(MessageService.component("reward.gained", Map.of("display", opt.displayPlain())));
        return true;
    }

    /** 图鉴记账：武器 → obtainedWeapons；有护符映射的藏品（STAT / bundle）→ obtainedCollectibles。 */
    private static void recordCodex(RewardOption opt, Player player) {
        try {
            if (opt.category() == RewardOption.Category.WEAPON) {
                io.mczju.maggoteers.codex.CodexProgress.markWeapon(player, opt.id());
            } else if (CollectibleRegistry.hasMapping(opt.id())) {
                io.mczju.maggoteers.codex.CodexProgress.markCollectible(player, opt.id());
            }
        } catch (RuntimeException ignored) {
            // 记账失败不影响发奖
        }
    }

    private static boolean applyBundle(Player player, RewardOption bundle, AbstractGame game, RewardPool pool) {
        if (!bundle.isApplicable()) {
            LOG.warning("RewardService: bundle 配置不完整 id=" + bundle.id());
            return false;
        }
        for (RewardOption grant : bundle.grants()) {
            if (!applySingle(player, grant, game, pool, true)) {
                LOG.warning("RewardService: bundle 子项失败 parent=" + bundle.id() + " grant=" + grant.id());
                return false;
            }
        }
        if (CollectibleRegistry.hasMapping(bundle.id())) {
            CollectibleService.grant(player, bundle.id(), 1);
        }
        return true;
    }

    private static boolean applySingle(Player player, RewardOption opt, AbstractGame game, RewardPool pool) {
        return applySingle(player, opt, game, pool, false);
    }

    private static boolean applySingle(Player player, RewardOption opt, AbstractGame game, RewardPool pool,
                                       boolean bundleGrant) {
        switch (opt.category()) {
            case SUPPLY, WEAPON -> {
                if (opt.item() == null || opt.item().isBlank()) {
                    LOG.warning("RewardService: WEAPON/SUPPLY 缺 item id=" + opt.id());
                    return false;
                }
                int n = Math.max(1, opt.amount());
                if (!ItemService.give(player, opt.item(), n)) {
                    LOG.warning("RewardService: 发放物品失败 id=" + opt.id() + " item=" + opt.item());
                    return false;
                }
                return true;
            }
            case STAT -> {
                // Instant GRANT_REVIVE (no fireTrigger) does not leave a PlayerEffect; triggered
                // GRANT_REVIVE (e.g. ON_ACT_ENTER 增生藤甲) must go through applyStat so visibility works.
                if (opt.effect() == Effect.GRANT_REVIVE && opt.trigger() == null) {
                    // 2026-10-28（魔王的旗帜）：min_revives 门槛——剩余复活次数 ≥ min 才发放。
                    int min = opt.params() == null ? 0
                            : opt.params().getOrDefault(EffectKeys.MIN_REVIVES, 0);
                    PlayerState st = PlayerStateManager.get(game, player.getUniqueId());
                    int revives = st == null ? -1 : st.getReviveCount();
                    if (min > 0 && revives >= 0 && revives < min) {
                        LOG.info("RewardService: " + opt.id() + " 未达 min_revives=" + min
                                + "（当前 " + revives + "），本次不发放复活次数");
                        return true;
                    }
                    int count = opt.params() == null ? 1
                            : opt.params().getOrDefault(EffectKeys.COUNT, 1);
                    PlayerStateManager.addReviveCount(game, player.getUniqueId(), count);
                    if (CollectibleRegistry.hasMapping(opt.id())) {
                        CollectibleService.grant(player, opt.id(), 1);
                    }
                    return true;
                }
                // 2026-10-28（诅咒·贫穷）：拾取即清空金币（被夺走 ≠ 花掉，不发 ON_SPEND）。
                if (opt.effect() == Effect.LOSE_COINS && opt.trigger() == null) {
                    int amount = opt.params() == null ? 0
                            : opt.params().getOrDefault(EffectKeys.AMOUNT, 0.0).intValue();
                    ItemService.loseNormalCurrency(player, amount);
                    if (CollectibleRegistry.hasMapping(opt.id())) {
                        CollectibleService.grant(player, opt.id(), 1);
                    }
                    return true;
                }
                return applyStat(player, opt, game, pool, bundleGrant);
            }
            case BUNDLE -> {
                LOG.warning("RewardService: 嵌套 bundle 不支持 id=" + opt.id());
                return false;
            }
        }
        return false;
    }

    private static boolean applyStat(Player player, RewardOption opt, AbstractGame game, RewardPool pool,
                                     boolean bundleGrant) {
        if (!opt.isSingleRewardValid()) {
            LOG.warning("RewardService: STAT 配置不完整 id=" + opt.id());
            return false;
        }
        if (!(game instanceof MaggoteersGame mg)) return false;
        PlayerState ps = PlayerStateManager.get(game, player.getUniqueId());
        if (ps == null) return false;
        int upgradeMax = UpgradeLevelCaps.resolve(pool, opt);
        if (opt.stack() == Stack.UPGRADE_LEVEL) {
            int cur = ps.effects().stream().filter(e -> e.id().equals(opt.id()))
                    .mapToInt(PlayerEffect::level).max().orElse(0);
            if (cur >= upgradeMax) return false;
        }
        int level = 1;
        io.mczju.maggoteers.effect.EffectContext params = RewardService.paramsForApply(opt);
        // 国王的铠甲（MAX_HEALTH_PER_REVIVE）：一次性转化——失去全部剩余复活次数，每失去一次获得 value 点生命上限。
        // params 是 copyDeep 副本，写入总生命安全；之后 resync/applyDerived 按该总价值以 ADD_ATTRIBUTE(MAX_HEALTH) 施加。
        if (opt.effect() == io.mczju.maggoteers.effect.Effect.MAX_HEALTH_PER_REVIVE) {
            int revives = ps.getReviveCount();
            if (revives > 0) {
                ps.setReviveCount(0);
                double perRevive = params.getOrDefault(io.mczju.maggoteers.effect.EffectKeys.VALUE, 10.0);
                params.put(io.mczju.maggoteers.effect.EffectKeys.VALUE, perRevive * revives);
                if (player.isOnline()) {
                    player.sendMessage(io.mczju.maggoteers.config.MessageService.component(
                            "reward.revive_trade", java.util.Map.of(
                                    "count", String.valueOf(revives),
                                    "hp", String.valueOf((int) Math.round(perRevive * revives)))));
                }
            }
        }
        PlayerEffect pe;
        if (opt.stack() == Stack.UPGRADE_LEVEL) {
            int curLevel = ps.effects().stream().filter(e -> e.id().equals(opt.id()))
                    .mapToInt(PlayerEffect::level).max().orElse(0);
            level = Math.min(curLevel + 1, upgradeMax);
            Integer baseAmp = params.get(EffectKeys.AMP);
            if (baseAmp != null) params.put(EffectKeys.AMP, baseAmp + (level - 1));
            pe = new PlayerEffect(
                    opt.id(), opt.effect(), params, opt.trigger(),
                    opt.expiryTrigger(), opt.expiryCharges(), 0, null, opt.stack(), upgradeMax, 0);
        } else {
            pe = new PlayerEffect(
                    opt.id(), opt.effect(), params, opt.trigger(),
                    opt.expiryTrigger(), opt.expiryCharges(), 0, null, opt.stack(), 0, 0);
        }
        pe.setLevel(level);
        EffectService.apply(player, pe, mg);
        PlayerEffect merged = ps.effects().stream().filter(e -> e.id().equals(opt.id())).findFirst().orElse(null);
        // ⚠️ BOUND_EQUIP（防弹胸甲）的"藏品"就是身上那件绑定装备本身，绝不能再去 CollectibleService.grant
        //    发一件护符——否则背包里会多出一件胸甲。它的 collectibles.yml 映射只服务图鉴/预览图标。
        if (!bundleGrant && merged != null && opt.effect() != Effect.BOUND_EQUIP
                && CollectibleRegistry.hasMapping(opt.id())) {
            CollectibleService.grant(player, opt.id(), merged.level());
        }
        return true;
    }

    /** 池 id = 层 id + "_" + 档位（层 id 由剧本来，不再由层序号现推 "act"+(i+1)）。 */
    public static String poolForWave(String actId, String tier) {
        if (actId == null || actId.isBlank() || tier == null || tier.isBlank()) return "";
        return actId + "_" + tier;
    }

    /**
     * 普通商店抽池档位：该层若在 {@code config.yml act_rules.shop_always_strong} 里 → 恒 strong；
     * 否则 weak 波→weak，强怪/Boss 波→strong（历史行为，逐条等价）。
     */
    public static String normalShopTier(io.mczju.maggoteers.config.ActRules rules, String actId, String lastTier) {
        if (rules != null && rules.alwaysStrongShop(actId)) return "strong";
        return "weak".equals(lastTier) ? "weak" : "strong";
    }
}
