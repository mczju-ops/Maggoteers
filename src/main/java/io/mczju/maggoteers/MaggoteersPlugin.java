package io.mczju.maggoteers;

import com.github.mczjuops.mczjugamecore.MCZJUGameCore;
import com.github.mczjuops.mczjugamecore.game.GameState;
import com.github.mczjuops.mczjugamecore.menu.MenuFacade;
import io.mczju.maggoteers.config.ActRules;
import io.mczju.maggoteers.config.ActsConfig;
import io.mczju.maggoteers.config.ScalingConfig;
import io.mczju.maggoteers.config.WavesConfig;
import io.mczju.maggoteers.game.MaggoteersGame;
import io.mczju.maggoteers.game.MaggoteersRoom;
import io.mczju.maggoteers.item.ItemInteractRouter;
import io.mczju.maggoteers.item.ItemService;
import io.mczju.maggoteers.listener.MobDeathListener;
import io.mczju.maggoteers.menu.ClassSelectMenu;
import io.mczju.maggoteers.menu.PickMenu;
import io.mczju.maggoteers.menu.RestMenu;
import io.mczju.maggoteers.reward.CollectibleRegistry;
import io.mczju.maggoteers.reward.RewardService;
import io.mczju.maggoteers.util.PluginFiles;
import io.mczju.maggoteers.world.MapRepository;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.logging.Level;

public final class MaggoteersPlugin extends JavaPlugin {
    private static MaggoteersPlugin instance;

    /** 层列表（config.yml {@code acts:}，顺序即推进顺序）——层数的唯一真相源。 */
    private volatile List<String> actIds = ActsConfig.DEFAULT_IDS;
    /** 层特例规则（恒 strong 商店 / 提前切 Boss 池 / 诅咒层）。 */
    private volatile ActRules actRules = ActRules.defaults();

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        PluginFiles.saveResourceIfMissing(this, "rewards.yml");
        PluginFiles.saveResourceIfMissing(this, "collectibles.yml");
        PluginFiles.saveResourceIfMissing(this, "codex.yml");
        io.mczju.maggoteers.world.WorldService.cleanupOrphansOnEnable();
        saveDefaultRooms();
        // MessageService must load before registerGame: MGC caches getGameMeta() at registration.
        loadAllConfigs();
        MCZJUGameCore.getGameManager().registerGame(MaggoteersGame.class, MaggoteersRoom.class);
        MCZJUGameCore.getPlayerDataManager().registerPlayerData("maggoteers", io.mczju.maggoteers.persist.MaggoteersPlayerData.class);
        MCZJUGameCore.getLeaderboardManager().registerLeaderboard("maggoteers_total", io.mczju.maggoteers.persist.MaggoteersTotalLeaderboard.class);
        MCZJUGameCore.getLeaderboardManager().registerLeaderboard("maggoteers_time", io.mczju.maggoteers.persist.MaggoteersRunTimeLeaderboard.class);
        // onEnable 瞬间 Registry 可能未就绪；下一 tick 再解析 rewards / held（药水与属性引用）
        getServer().getScheduler().runTask(this, this::loadAllConfigsSecondPass);
        MenuFacade.registerMenu("maggoteers-class", ClassSelectMenu.class);
        MenuFacade.registerMenu("maggoteers-rest", RestMenu.class);
        MenuFacade.registerMenu("maggoteers-pick", PickMenu.class);
        MenuFacade.registerMenu("maggoteers-revive", io.mczju.maggoteers.menu.ReviveMenu.class);
        MenuFacade.registerMenu("maggoteers-stats", io.mczju.maggoteers.menu.StatsMenu.class);
        MenuFacade.registerMenu("maggoteers-shop", io.mczju.maggoteers.menu.UnlockShopMenu.class);
        // 图鉴（/maggoteers index，2026-10-30）：一级 + 武器/藏品/首领/词条四个二级
        MenuFacade.registerMenu("maggoteers-codex", io.mczju.maggoteers.menu.CodexMenu.class);
        MenuFacade.registerMenu("maggoteers-codex-weapon", io.mczju.maggoteers.menu.CodexWeaponMenu.class);
        MenuFacade.registerMenu("maggoteers-codex-collectible", io.mczju.maggoteers.menu.CodexCollectibleMenu.class);
        MenuFacade.registerMenu("maggoteers-codex-boss", io.mczju.maggoteers.menu.CodexBossMenu.class);
        MenuFacade.registerMenu("maggoteers-codex-affix", io.mczju.maggoteers.menu.CodexAffixMenu.class);
        getServer().getPluginManager().registerEvents(new MobDeathListener(), this);
        getServer().getPluginManager().registerEvents(new io.mczju.maggoteers.listener.MobSkillListener(), this);
        getServer().getPluginManager().registerEvents(new io.mczju.maggoteers.listener.MobDamageReductionListener(), this);
        getServer().getPluginManager().registerEvents(new io.mczju.maggoteers.listener.PlayerDamageTakenListener(), this);
        getServer().getPluginManager().registerEvents(new ItemInteractRouter(), this);
        getServer().getPluginManager().registerEvents(new io.mczju.maggoteers.item.WeaponHeldListener(this), this);
        getServer().getPluginManager().registerEvents(new io.mczju.maggoteers.effect.EffectListener(), this);
        getServer().getPluginManager().registerEvents(new io.mczju.maggoteers.effect.SummonFriendlyFireListener(), this);
        getServer().getPluginManager().registerEvents(new io.mczju.maggoteers.effect.MobAiLockListener(), this);
        io.mczju.maggoteers.effect.MobAiLockRegistry.start(this);
        // 2026-10-29：寒冷续冻（单次 setFreezeTicks 会被 vanilla 每 tick 扣 2）
        io.mczju.maggoteers.effect.FreezeService.start(this);
        getServer().getPluginManager().registerEvents(new io.mczju.maggoteers.listener.PurifyListener(), this);
        getServer().getPluginManager().registerEvents(new io.mczju.maggoteers.listener.RunItemGuardListener(), this);
        getServer().getPluginManager().registerEvents(new io.mczju.maggoteers.listener.BoundEquipLockListener(), this);
        getServer().getPluginManager().registerEvents(new io.mczju.maggoteers.listener.GameplayTickListener(), this);
        getServer().getPluginManager().registerEvents(new io.mczju.maggoteers.listener.RunRulesListener(), this);
        // 2026-10-29：近战元素暴击（燃烧/寒冷/浮空/凋零 → mul 增伤 + 音效）
        getServer().getPluginManager().registerEvents(new io.mczju.maggoteers.listener.MeleeCritListener(), this);
        getServer().getPluginManager().registerEvents(
                new io.mczju.maggoteers.listener.ScriptedFangListener(), this);
        // 2026-10-29「巨人摇篮」：拦下本局刷出的潜影贝传送（屏障怪要钉在刷怪点上）
        getServer().getPluginManager().registerEvents(
                new io.mczju.maggoteers.listener.MobTeleportGuardListener(), this);
        // 2026-10-30 成就：伤害通道（会心一击 / 自伤标记）
        getServer().getPluginManager().registerEvents(
                new io.mczju.maggoteers.achievement.AchievementListener(), this);
        io.mczju.maggoteers.listener.GameplayTickListener.start();
        io.mczju.maggoteers.mob.MobSkillService.start(this);
        io.mczju.maggoteers.mob.SpiderClimbGuard.start(this);   // 1-tick：清本局蜘蛛的爬墙状态位
        io.mczju.maggoteers.effect.ProjectileHomingService.start(this);
        var mc = getCommand("maggoteers");
        if (mc != null) {
            var command = new io.mczju.maggoteers.command.MaggoteersCommand();
            mc.setExecutor(command);
            mc.setTabCompleter(command);   // 自动补全：子命令 / 池内 strategy / 技能 id / 实体 / 奖励 id
        }
        // 中文名带全角「？！」（2026-10-29 定名）：仅日志/控制台可见，控制台若为 GBK 也支持这两个全角标点
        getLogger().info("Maggoteers (区区恶敌？！) enabled, game 'maggoteers' registered.");
        if (getConfig().getBoolean("debug.enabled", false) && getConfig().getBoolean("debug.announce", true)) {
            getLogger().warning("=== 内部测试版：debug.enabled=true（任意玩家可用 /maggoteers debug）===");
            getLogger().warning("正式上线前请将 config.yml 中 debug.enabled 改为 false。");
        }
    }

    @Override
    public void onDisable() {
        io.mczju.maggoteers.listener.GameplayTickListener.stop();
        io.mczju.maggoteers.achievement.AchievementService.disable();
        io.mczju.maggoteers.effect.EffectListener.stopTick();
        io.mczju.maggoteers.effect.MobAiLockRegistry.restoreAll();
        io.mczju.maggoteers.effect.MobAiLockRegistry.stop();
        io.mczju.maggoteers.mob.SpiderClimbGuard.stop();
        // 关服/禁用前主动结束本插件所有游戏：MGC 1.0.7 的 onDisable 会遍历 getAllGames() 对非 WAITING 游戏调
        // abortGame；插件禁用是逆序（Maggoteers 先被禁用、类加载器已关闭），届时 MGC 再 abort 会让 cleanupRun
        // 首次访问未加载类 → IllegalStateException: The plugin classloader for Maggoteers has thrown a zip file error。
        // 提前走 MGC cancel/abort 结束游戏并移出 gameList，MGC 关服时便没有 maggoteers 游戏可 abort。
        // 拷贝列表遍历：abort/cancel 内部 solveGameEnd 会从 gameList remove，直接迭代会 CME。
        try {
            var games = new java.util.ArrayList<>(MCZJUGameCore.getGameManager().getAllGames());
            for (var g : games) {
                if (g instanceof MaggoteersGame) {
                    var gm = MCZJUGameCore.getGameManager();
                    if (g.getState() == GameState.WAITING) gm.cancelGame(g);
                    else gm.abortGame(g);
                }
            }
        } catch (Throwable t) {
            getLogger().log(Level.SEVERE, "禁用时主动结束本插件游戏失败", t);
        }
        getLogger().info("Maggoteers disabled.");
        io.mczju.maggoteers.world.StructureCache.clear();
    }

    public static MaggoteersPlugin getInstance() {
        return instance;
    }

    /** 本局层列表（顺序即推进顺序）。 */
    public List<String> actIds() {
        return actIds;
    }

    /** 层特例规则（恒 strong 商店 / 提前切 Boss 池 / 诅咒层）。 */
    public ActRules actRules() {
        return actRules;
    }

    /** 读层列表与层规则；非法配置只告警并回落默认（开局时 RunPlanner 会再按实际配置 fail-fast 校核）。 */
    private void loadActConfig() {
        try {
            actIds = ActsConfig.fromConfig(getConfig());
            actRules = ActRules.fromConfig(getConfig());
            getLogger().info("层配置：acts=" + actIds + "，规则=" + actRules);
        } catch (RuntimeException ex) {
            getLogger().log(Level.SEVERE, "config.yml 层配置非法（acts/act_rules），回落默认 act1..act3：" + ex.getMessage());
            actIds = ActsConfig.DEFAULT_IDS;
            actRules = ActRules.defaults();
        }
    }

    /** 热重载全部 YAML（config / waves / rewards / items / maps / mob_skills 等）。进行中对局的剧本与 PlayerState 不变。 */
    public boolean reloadConfigs() {
        try {
            reloadConfig();
            loadAllConfigs();
            loadAllConfigsSecondPass();
            getLogger().info("配置热重载完成。");
            return true;
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "配置热重载失败", e);
            return false;
        }
    }

    private void loadAllConfigs() {
        loadActConfig();
        io.mczju.maggoteers.config.MessageService.load(this);
        io.mczju.maggoteers.mob.MobSkillRegistry.load(this);
        WavesConfig.loadFromFile(this);
        MapRepository.load(this);
        ScalingConfig.load(this);
        ItemService.init(this);
        io.mczju.maggoteers.item.fx.MagicFxConfig.load(this);
        io.mczju.maggoteers.ambience.WaveAmbienceConfig.load(getConfig());
        io.mczju.maggoteers.effect.ItemAbilityRegistry.load(this);
        io.mczju.maggoteers.effect.WeaponHeldRegistry.load(this);
        RewardService.load(this);
        CollectibleRegistry.load(this);
        // 图鉴文案/白名单（依赖 rewards/waves/mob_skills 都已就绪）
        io.mczju.maggoteers.codex.CodexConfig.load(this);
        // 成就（依赖 rewards 已在上面加载：军需处商品清单要靠奖励池派生）
        io.mczju.maggoteers.achievement.AchievementService.load(this);
    }

    /** 第二遍解析（药水/属性交叉引用；onEnable 延后一 tick，reload 时同步执行）。 */
    private void loadAllConfigsSecondPass() {
        io.mczju.maggoteers.effect.WeaponHeldRegistry.load(this);
        io.mczju.maggoteers.effect.ItemAbilityRegistry.load(this);
        RewardService.load(this);
        CollectibleRegistry.load(this);
        io.mczju.maggoteers.codex.CodexConfig.load(this);
        io.mczju.maggoteers.achievement.AchievementService.load(this);
    }

    private void saveDefaultRooms() {
        var mgc = JavaPlugin.getPlugin(MCZJUGameCore.class);
        if (mgc == null) {
            getLogger().severe("MCZJUGameCore 未加载，无法释放默认房间！游戏将无法 join。");
            return;
        }
        File dir = new File(mgc.getDataFolder(), "rooms/maggoteers");
        File def = new File(dir, "default.json");
        if (def.exists()) return;
        if (!dir.exists() && !dir.mkdirs()) {
            getLogger().warning("无法创建目录 " + dir);
            return;
        }
        try {
            Files.writeString(def.toPath(), "{}");
            getLogger().info("已释放默认房间: " + def.getPath());
        } catch (Exception e) {
            getLogger().warning("写出默认房间失败: " + e.getMessage());
        }
    }
}
