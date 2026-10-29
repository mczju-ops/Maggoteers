package io.mczju.maggoteers.wave;

import com.github.mczjuops.mczjugamecore.game.AbstractGame;
import io.mczju.maggoteers.game.MaggoteersGame;
import io.mczju.maggoteers.mob.MobDisplayNames;
import io.mczju.maggoteers.mob.MobFactory;
import io.mczju.maggoteers.mob.MobSkillService;
import io.mczju.maggoteers.mob.MountedSquadAiService;
import io.mczju.maggoteers.mob.MountedSquadRegistry;
import io.mczju.maggoteers.world.WorldService;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;

import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 波次执行器：生成、追踪、死亡处理、5-tick 安全扫描（照搬前代 VampireSurvivor WaveManager 核心）。
 * <p><b>只管"本波怪物的生灭"，不管"波次/相位调度"</b>——后者由 {@link WaveScheduler} 驱动。
 */
public final class WaveEngine {
    private static final Map<AbstractGame, WaveRuntime> RUNTIMES = new IdentityHashMap<>();
    /** UUID → runtime，供死亡监听器/扫描 O(1) 反查。 */
    private static final Map<UUID, WaveRuntime> BY_ENTITY = new HashMap<>();
    /** 死亡 untrack 后 SlimeSplitEvent 才触发；先标记再消费。 */
    private static final Set<UUID> PENDING_SPLIT_CANCEL = ConcurrentHashMap.newKeySet();

    public static WaveRuntime start(AbstractGame game) {
        WaveRuntime rt = new WaveRuntime(game);
        RUNTIMES.put(game, rt);
        return rt;
    }

    public static WaveRuntime runtime(AbstractGame game) { return RUNTIMES.get(game); }

    /** 2026-10-28：玩家退出时隐藏本局所有 Boss 血条（否则会在客户端残留到退出）。 */
    public static void hideBossBarsFor(org.bukkit.entity.Player p) {
        if (p == null) return;
        for (WaveRuntime rt : RUNTIMES.values()) {
            for (net.kyori.adventure.bossbar.BossBar bar : rt.bossBars.values()) {
                p.hideBossBar(bar);
            }
        }
    }

    /**
     * 2026-10-27（霜晶树 / ON_BOSS_KILL）：该实体是否是本局**带血条的 Boss**。
     * 判据 = 它在本局 runtime 里挂过 BossBar（{@code boss_bar: true} 的怪必挂，见 BossContentSchemaTest）。
     */
    public static boolean isTrackedBoss(AbstractGame game, UUID uuid) {
        if (game == null || uuid == null) return false;
        WaveRuntime rt = RUNTIMES.get(game);
        return rt != null && rt.bossBars.containsKey(uuid);
    }

    public static int livingCount(AbstractGame game) {
        WaveRuntime rt = RUNTIMES.get(game);
        return rt == null ? 0 : rt.livingMobs.size();
    }

    public static boolean isTracked(UUID uuid) { return BY_ENTITY.containsKey(uuid); }

    /** UUID → 本局（供 MobSkillListener/MobSkillService 反查）。 */
    public static AbstractGame gameOfEntity(UUID uuid) {
        WaveRuntime rt = BY_ENTITY.get(uuid);
        return rt == null ? null : rt.game;
    }

    /** 以 loc 为圆心、radius 内最近的本局追踪怪（供 homing 目标选择）。 */
    public static LivingEntity nearestTrackedMob(Location loc, MaggoteersGame game, double radius) {
        if (loc == null || game == null || loc.getWorld() == null) return null;
        WaveRuntime rt = RUNTIMES.get(game);
        if (rt == null) return null;
        LivingEntity best = null;
        double bestSq = radius * radius;
        for (UUID uuid : rt.livingMobs) {
            Entity raw = Bukkit.getEntity(uuid);
            if (!(raw instanceof LivingEntity le) || le.isDead() || !le.isValid()) continue;
            double d = le.getLocation().distanceSquared(loc);
            if (d < bestSq) {
                bestSq = d;
                best = le;
            }
        }
        return best;
    }

    /** {@link #handleMobDeath} 移除追踪前对史莱姆/岩浆怪调用；供 {@code SlimeSplitEvent} 取消分裂。 */
    public static boolean consumeSplitCancel(UUID uuid) {
        return PENDING_SPLIT_CANCEL.remove(uuid);
    }

    /**
     * 波次结束/败局时清空分裂取消标记。分裂事件只在死亡当 tick 触发，进入休整期/结算后无残留意义；
     * 清空可防止「SlimeSplitEvent 先于 EntityDeathEvent / 被第三方取消」导致的集合无界增长（R2）。
     */
    public static void clearPendingSplitCancel() {
        PENDING_SPLIT_CANCEL.clear();
    }

    /**
     * 回合结束统一清场（1.3.2，一劳永逸替代 T1 延迟清空 + VEX 清理）：移除场上所有
     * 「非玩家、非玩家召唤物、非掉落物品」实体——残留怪 / 史莱姆分裂子体 / 恼鬼 / 弹射物等，
     * 并清空分裂取消标记（场上已无史莱姆，同步清空不再有竞态）。
     * <p>保留 {@code Item}：战斗期可能有背包溢出/货币掉落，误清会吞奖励。
     */
    public static void clearWorld(MaggoteersGame game) {
        World w = WorldService.get(game);
        if (w == null) return;
        // 2026-10-28（安全）：只允许清本局自建的虚空世界（maggoteers_*）。
        // 万一世界句柄被错配成主世界，这里的广域清理会连玩家布景/别的插件实体一起删。
        if (!w.getName().startsWith("maggoteers_")) {
            io.mczju.maggoteers.MaggoteersPlugin.getInstance().getLogger().warning(
                    "[clearWorld] 拒绝清理非本局世界：" + w.getName());
            return;
        }
        int removed = 0;
        for (Entity e : new java.util.ArrayList<>(w.getEntities())) {
            if (e instanceof org.bukkit.entity.Player) continue;
            if (e instanceof org.bukkit.entity.Item) continue;
            if (io.mczju.maggoteers.effect.SummonRegistry.isTrackedSummon(e)) continue;
            e.remove();
            removed++;
        }
        // 冒烟诊断（2026-08-27）：记录清掉数量 + 清后剩余的非玩家/非掉落实体类型，定位波末残留来源
        java.util.Map<String, Integer> remaining = new java.util.HashMap<>();
        for (Entity e : w.getEntities()) {
            if (e instanceof org.bukkit.entity.Player) continue;
            if (e instanceof org.bukkit.entity.Item) continue;
            remaining.merge(e.getType().name(), 1, Integer::sum);
        }
        if (removed > 0 || !remaining.isEmpty()) {
            io.mczju.maggoteers.MaggoteersPlugin.getInstance().getLogger()
                    .info("[clearWorld] removed=" + removed + " remaining=" + remaining);
        }
        PENDING_SPLIT_CANCEL.clear();
    }

    /** 调试 spawnmob：登记进本局 livingMobs。 */
    public static void trackDebugMob(AbstractGame game, LivingEntity le) {
        WaveRuntime rt = RUNTIMES.get(game);
        if (rt == null || le == null) return;
        trackSpawned(game, rt, null, le, new MobSpawnProfile(List.of()));
    }

    /**
     * D4（2026-10-26）：把**技能召唤**出来的怪登记进本波追踪。
     *
     * <p>以前 {@code SUMMON} 直接 {@code spawnEntity} 不登记 ⇒ 召唤物不计入 {@code livingMobs}：
     * ① 波次可能"怪还在场上"就被判定清空；② 波末 {@code clearWorld} 又把它们当残留清掉
     * （表现为"召唤了一堆小怪但什么也没发生"）。现在它们与其他怪一样受追踪、计死亡、参与清场。
     *
     * <p>不挂技能（{@code MobSpawnProfile} 空表）：召唤物默认不带 skill，避免递归召唤爆炸。
     */
    public static void trackSummoned(AbstractGame game, LivingEntity le) {
        WaveRuntime rt = RUNTIMES.get(game);
        if (rt == null || le == null || le.isDead()) return;
        if (rt.livingMobs.contains(le.getUniqueId())) return;   // 已是追踪怪（如 debug 刷的）不重复登记
        trackSpawned(game, rt, null, le, new MobSpawnProfile(List.of()));
    }

    /**
     * 生成一个 SpawnStep 的全部 count 只怪于其绝对点，登记进追踪。
     * 仅当配置 {@code boss_bar: true} 时挂 Adventure BossBar。
     */
    public static void spawnStep(AbstractGame game, WaveRuntime rt, SpawnStep step, World world) {
        // 点位是"方块坐标"（points.yml 写整数），生成时必须落到**方块中心**：
        // 直接用整数坐标会让实体站在方块角上、整体偏半格（配合九宫格偏移看起来更偏）。
        Location base = new Location(world, io.mczju.maggoteers.util.Coords.blockCenter(step.point().x()),
                step.point().y(), io.mczju.maggoteers.util.Coords.blockCenter(step.point().z()));
        MobFactory.spawnStepGroup(game, base, step, (le, profile) -> trackSpawned(game, rt, step, le, profile));
    }

    private static void trackSpawned(AbstractGame game, WaveRuntime rt, SpawnStep step,
                                     LivingEntity le, MobSpawnProfile profile) {
        UUID uuid = le.getUniqueId();
        rt.livingMobs.add(uuid);
        rt.mobProfiles.put(uuid, profile);
        BY_ENTITY.put(uuid, rt);
        MobSkillService.attach(le, profile.skills());
        // 血条判定：step 与实体自身任一声明 boss_bar 即挂（修 2026-10：乘客/亡语实体的 boss_bar 曾被 step 覆盖而丢失，
        // b1_bandit_chief / b2_silent_guard 实际没有血条）。见 BossBarPolicy。
        if (profile != null && profile.bossBar() && !rt.phases.isEmpty()) {
            io.mczju.maggoteers.mob.BossPhaseTracker.register(uuid, rt.phases, le.getWorld() == null
                    ? 0L : Bukkit.getCurrentTick());
        }
        boolean wantBar = BossBarPolicy.wantBar(
                step != null && step.bossBar(),
                profile != null && profile.bossBar());
        if (wantBar) {
            // D11（2026-10-26）：血条外观取 step/profile 的 bar_color / bar_overlay（缺省红 + 普通进度条）
            io.mczju.maggoteers.mob.BossBarStyle style = step != null ? step.barStyle()
                    : (profile != null ? profile.barStyle() : io.mczju.maggoteers.mob.BossBarStyle.DEFAULT);
            attachBossBar(game, rt, le, style);
        }
        // 图鉴（2026-10-30）：**Boss 本体刷出来的这一刻**才算"遭遇过"（而不是波次开始就记）。
        // 一局只记一次；乘客骑乘型 Boss（卫士不语功的猪灵将军）同样会走到这里。
        if (wantBar && rt.bossCodexStrategy != null) {
            io.mczju.maggoteers.codex.CodexProgress.markBossForAll(game, rt.bossCodexStrategy);
            rt.bossCodexStrategy = null;
        }
    }

    private static void attachBossBar(AbstractGame game, WaveRuntime rt, LivingEntity le,
                                       io.mczju.maggoteers.mob.BossBarStyle style) {
        Component title = MobDisplayNames.resolveTitle(
                MobDisplayNames.lockedDisplayName(le).orElse(null), le.getType());
        io.mczju.maggoteers.mob.BossBarStyle s = style == null
                ? io.mczju.maggoteers.mob.BossBarStyle.DEFAULT : style;
        BossBar bar = BossBar.bossBar(title, 1f, s.color(), s.overlay());
        for (var pe : game.getPlayers()) pe.player().showBossBar(bar);
        rt.bossBars.put(le.getUniqueId(), bar);
    }

    private static void hideBossBar(AbstractGame game, BossBar bar) {
        if (bar == null) return;
        for (var pe : game.getPlayers()) pe.player().hideBossBar(bar);
    }

    private static void clearBossBars(WaveRuntime rt) {
        for (BossBar bar : rt.bossBars.values()) hideBossBar(rt.game, bar);
        rt.bossBars.clear();
    }

    /**
     * 本波**带 {@code boss_bar: true} 的被追踪怪**（读时过滤已死/无效）。
     *
     * <p>用途：{@code DELAYED_AREA origin: other_boss} 只能在这批里挑圆心——旧实现挑"最近的另一个
     * 被追踪怪"，于是 Boss 波里的疣猪兽/凋灵骷髅/猪灵蛮兵会把读条环抢走（实测：迈入永恒的环落在
     * 凋灵骷髅小怪脚下，玩家永远不在圈里）。
     */
    public static java.util.List<LivingEntity> trackedBosses(AbstractGame game) {
        WaveRuntime rt = RUNTIMES.get(game);
        if (rt == null) return java.util.List.of();
        java.util.List<LivingEntity> out = new java.util.ArrayList<>();
        for (UUID uuid : new java.util.ArrayList<>(rt.livingMobs)) {
            MobSpawnProfile profile = rt.mobProfiles.get(uuid);
            if (profile == null || !profile.bossBar()) continue;
            var e = Bukkit.getEntity(uuid);
            if (e instanceof LivingEntity le && le.isValid() && !le.isDead()) out.add(le);
        }
        return out;
    }

    /** 清追踪表但不 remove 实体（调试跳波前由调用方清怪）。 */
    public static void clearTracking(AbstractGame game) {
        WaveRuntime rt = RUNTIMES.get(game);
        if (rt == null) return;
        for (UUID uuid : new HashSet<>(rt.livingMobs)) {
            BY_ENTITY.remove(uuid);
            MobSkillService.detach(uuid);
        }
        rt.livingMobs.clear();
        rt.mobProfiles.clear();
        clearBossBars(rt);
        MountedSquadRegistry.removeGame(game);
    }

    /** 移除当前波次追踪的所有实体。 */
    public static void killAllTracked(AbstractGame game) {
        WaveRuntime rt = RUNTIMES.get(game);
        if (rt == null) return;
        for (UUID uuid : new HashSet<>(rt.livingMobs)) {
            var e = Bukkit.getEntity(uuid);
            if (e != null) e.remove();
            MobSkillService.detach(uuid);
        }
        clearTracking(game);
    }

    /** 停止并清理本局所有追踪怪 + Boss 条。 */
    public static void stop(AbstractGame game) {
        WaveRuntime rt = RUNTIMES.remove(game);
        if (rt == null) return;
        var ourMobs = new HashSet<>(rt.livingMobs);
        for (UUID uuid : ourMobs) {
            var e = Bukkit.getEntity(uuid);
            if (e != null) e.remove();
            BY_ENTITY.remove(uuid);
            MobSkillService.detach(uuid);
        }
        rt.livingMobs.clear();
        rt.mobProfiles.clear();
        clearBossBars(rt);
        PENDING_SPLIT_CANCEL.clear();
        MountedSquadRegistry.removeGame(game);
    }

    /** 死亡处理：移除追踪；死亡召唤计入本波 livingMobs。返回 true 表示是我们的怪。 */
    public static boolean handleMobDeath(UUID uuid, Location loc) {
        WaveRuntime rt = BY_ENTITY.get(uuid);
        if (rt == null) return false;
        var dead = Bukkit.getEntity(uuid);
        // 26.2 起 MagmaCube 不再继承 Slime；AbstractCubeMob 统一史莱姆/岩浆怪（否则岩浆怪分裂不再被取消）
        if (dead instanceof org.bukkit.entity.AbstractCubeMob) {
            PENDING_SPLIT_CANCEL.add(uuid);
        }
        MobSpawnProfile profile = rt.mobProfiles.remove(uuid);
        rt.livingMobs.remove(uuid);
        BY_ENTITY.remove(uuid);
        MobSkillService.detach(uuid);
        io.mczju.maggoteers.mob.BossPhaseTracker.forget(uuid);
        // L3：坐骑死 → squad 移除；其乘客随根实体死亡/删除弹飞，成独立追踪怪继续战斗（原版索敌）
        MountedSquadRegistry.removeByRoot(rt.game, uuid);
        BossBar bar = rt.bossBars.remove(uuid);
        hideBossBar(rt.game, bar);
        if (loc != null && profile != null && !profile.onDeath().isEmpty()) {
            spawnOnDeath(rt, loc, profile.onDeath());
        }
        return true;
    }

    /**
     * 被吞噬：静默移除一个被追踪怪（不掉落、不触发亡语、不计击杀）。
     * 用于 {@code Effect.DEVOUR}（伊祖米克吃史莱姆长力量）。
     */
    public static void devourTracked(AbstractGame game, LivingEntity le) {
        WaveRuntime rt = le == null ? null : BY_ENTITY.get(le.getUniqueId());
        if (rt != null) {
            UUID uuid = le.getUniqueId();
            rt.mobProfiles.remove(uuid);
            rt.livingMobs.remove(uuid);
            BY_ENTITY.remove(uuid);
            MobSkillService.detach(uuid);
            hideBossBar(rt.game, rt.bossBars.remove(uuid));
        }
        if (le != null) le.remove();
    }

    private static void spawnOnDeath(WaveRuntime rt, Location loc, List<DeathSpawn> specs) {
        int spread = 0;
        for (DeathSpawn ds : specs) {
            Location at = MobFactory.spawnPadLocation(loc, spread).add(0, 0.15, 0);
            spread++;
            LivingEntity le = MobFactory.spawnDeathMob(at, ds);
            if (le != null) {
                trackSpawned(rt.game, rt, null, le, MobSpawnProfile.fromDeathSpawn(ds));
                if (!ds.passengers().isEmpty()) {
                    MobFactory.mountPassengersDelayed(rt.game, le, ds.passengers(),
                            (passenger, profile) -> trackSpawned(rt.game, rt, null, passenger, profile));
                }
            }
        }
    }

    /**
     * 5-tick 安全扫描（由 WaveScheduler 心跳调用）：把已消失/爆炸实体强制计入死亡，刷新 Boss 条。
     * 照搬 VampireSurvivor WaveManager.tickContext。
     */
    public static void tick(WaveRuntime rt) {
        MountedSquadAiService.tick(rt.game);
        // 2026-10-28：血条只该给本局参与者看。中途 {@code /mgc leave} 的玩家已不在 getPlayers()，
        // 但客户端仍会保留之前 showBossBar 的条（直到退出客户端）。这里做幂等収尾。
        if (!rt.bossBars.isEmpty()) {
            for (org.bukkit.entity.Player online : org.bukkit.Bukkit.getOnlinePlayers()) {
                if (io.mczju.maggoteers.state.PlayerStateManager.gameOf(online) == rt.game) continue;
                for (net.kyori.adventure.bossbar.BossBar bar : rt.bossBars.values()) {
                    online.hideBossBar(bar);
                }
            }
        }
        for (UUID uuid : new HashSet<>(rt.livingMobs)) {
            var raw = Bukkit.getEntity(uuid);
            if (raw == null || raw.isDead()) {
                handleMobDeath(uuid, raw != null ? raw.getLocation() : null);
                continue;
            }
            if (!(raw instanceof LivingEntity le)) continue;
            MobDisplayNames.reassertIfNeeded(le);
            BossBar bar = rt.bossBars.get(uuid);
            if (bar != null) {
                AttributeInstance hp = le.getAttribute(Attribute.MAX_HEALTH);
                double max = hp != null ? hp.getValue() : 20.0;
                bar.progress((float) Math.max(0.0, Math.min(1.0, le.getHealth() / max)));
            }
        }
    }

    private WaveEngine() {}
}
