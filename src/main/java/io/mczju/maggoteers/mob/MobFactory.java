package io.mczju.maggoteers.mob;

import com.github.mczjuops.mczjugamecore.game.AbstractGame;
import io.mczju.maggoteers.MaggoteersPlugin;
import io.mczju.maggoteers.item.ItemService;
import io.mczju.maggoteers.wave.*;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Hoglin;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.PiglinAbstract;
import org.bukkit.entity.Slime;
import org.bukkit.entity.AbstractCubeMob;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.logging.Logger;

/**
 * 生成原版怪 + 骑乘步（延迟挂载、嵌套乘客）+ 尺寸/寻敌/装备/死亡召唤配置。
 */
public final class MobFactory {
    private static final Logger LOG = Logger.getLogger("Maggoteers");

    /**
     * 3×3 九宫格第 {@code index % 9} 格（相对 base 的整格偏移，均在 ±1 内）。
     *
     * <p><b>中心优先</b>（2026-10-28 修）：第 0 格就是 base 本身，之后按 +X / −X / +Z / −Z / 四角展开。
     * 之前是"从 (−1,−1) 角开始数"，于是 {@code count=1}（Boss）以及 {@code count=2} 的每一步
     * 都会整体偏**西北一格**——实测表现就是"Boss 没有刷在刷怪点/海晶灯正上方，小怪也同方向偏"。
     * 数量超过 9 时回到第 0 格（与原实现一样堆叠）。
     */
    private static final int[][] PAD_OFFSETS = {
            {0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {-1, 1}, {1, -1}, {-1, -1}
    };

    public static Location spawnPadLocation(Location base, int index) {
        int[] o = PAD_OFFSETS[Math.floorMod(index, PAD_OFFSETS.length)];
        return base.clone().add(o[0], 0, o[1]);
    }

    /** 生成一步的全部根坐骑；乘客 tick+1 挂载，每实体 {@code track} 回调。 */
    public static void spawnStepGroup(AbstractGame game, Location baseLoc, SpawnStep step,
                                      BiConsumer<LivingEntity, MobSpawnProfile> track) {
        for (int i = 0; i < step.count(); i++) {
            Location loc = spawnPadLocation(baseLoc, i);
            LivingEntity mount = spawnMount(loc, step);
            if (mount == null) continue;
            track.accept(mount, MobSpawnProfile.fromStep(step));
            if (!step.passengers().isEmpty()) {
                List<PassengerSpawn> passengerTree = step.passengers();
                Bukkit.getScheduler().runTaskLater(MaggoteersPlugin.getInstance(), () -> {
                    if (!mount.isValid() || mount.isDead()) return;
                    List<LivingEntity> direct = new ArrayList<>();
                    mountPassengerTree(mount, passengerTree, track, direct);
                    LivingEntity controller = MountControllerResolver.resolve(mount, direct, passengerTree);
                    MountedSquadRegistry.register(game, mount, controller);
                }, 1L);
            }
        }
    }

    private static void mountPassengerTree(LivingEntity carrier, List<PassengerSpawn> nodes,
                                           BiConsumer<LivingEntity, MobSpawnProfile> track,
                                           List<LivingEntity> directChildrenOut) {
        int slots = MountPassengerLimits.directPassengerLimit(carrier)
                - carrier.getPassengers().size();
        for (PassengerSpawn node : nodes) {
            if (slots <= 0) {
                LOG.warning("坐骑 " + carrier.getType() + " 直接乘客已满，跳过 " + node.type());
                break;
            }
            LivingEntity child = spawnPassengerEntity(carrier.getLocation(), node);
            if (child == null) continue;
            track.accept(child, MobSpawnProfile.fromPassenger(node));
            mountPassengerTree(child, node.passengers(), track, null);
            if (!carrier.addPassenger(child)) {
                // L1：child 已 remove 但仍被 livingMobs 追踪，由 5-tick 扫描回收；孙乘客随 child 移除弹飞成散怪
                LOG.warning("addPassenger 失败：" + carrier.getType() + " <- " + child.getType());
                child.remove();
                continue;
            }
            slots--;
            if (directChildrenOut != null) directChildrenOut.add(child);
        }
    }

    /** 刷怪后统一配置：防卸载 → 系数 → 装备 → 名字锁定 → 名字再断言 → 挂载限制。技能挂载由 WaveEngine.trackSpawned 做（Task 6）。 */
    private static void configureSpawnedLiving(LivingEntity le, double hpMult, double dmgMult, double speedMult,
                                               double scaleMult, double followRangeMult,
                                               List<MobEquipment> equipment, String name) {
        le.setRemoveWhenFarAway(false);
        applyMobStats(le, hpMult, dmgMult, speedMult, scaleMult, followRangeMult);
        applyEquipment(le, equipment);
        MobDisplayNames.lock(le, name);
        scheduleNameReassert(le);
        MountPassengerLimits.prepareMount(le);
        // 刷怪唯一漏斗：波次 / 坐骑 / 乘客 / 亡语 的蜘蛛统一登记「禁止爬墙」（非蜘蛛自动忽略）
        SpiderClimbGuard.mark(le);
    }

    public static LivingEntity spawnDeathMob(Location loc, DeathSpawn ds) {
        if (loc.getWorld() == null) return null;
        try {
            if (ds.type() == EntityType.TNT) {
                var raw = loc.getWorld().spawnEntity(loc, EntityType.TNT);
                if (raw instanceof TNTPrimed tnt) {
                    tnt.setFuseTicks(40);
                }
                return null;
            }
            var raw = loc.getWorld().spawnEntity(loc, ds.type());
            if (!(raw instanceof LivingEntity le)) {
                raw.remove();
                return null;
            }
            configureSpawnedLiving(le, ds.hpMult(), ds.dmgMult(), ds.speedMult(), ds.scaleMult(),
                    ds.followRangeMult(), ds.equipment(), ds.name());
            if (le instanceof Mob m) m.setAware(true);
            return le;
        } catch (Exception e) {
            LOG.warning("死亡召唤失败 " + ds.type() + "：" + e.getMessage());
            return null;
        }
    }

    /** 亡语生成物延迟挂载乘客（与 {@link #spawnStepGroup} 同 tick+1 语义）。 */
    public static void mountPassengersDelayed(AbstractGame game, LivingEntity mount, List<PassengerSpawn> passengers,
                                              BiConsumer<LivingEntity, MobSpawnProfile> track) {
        if (mount == null || passengers == null || passengers.isEmpty()) return;
        Bukkit.getScheduler().runTaskLater(MaggoteersPlugin.getInstance(), () -> {
            if (!mount.isValid() || mount.isDead()) return;
            List<LivingEntity> direct = new ArrayList<>();
            mountPassengerTree(mount, passengers, track, direct);
            LivingEntity controller = MountControllerResolver.resolve(mount, direct, passengers);
            MountedSquadRegistry.register(game, mount, controller);
        }, 1L);
    }

    private static LivingEntity spawnMount(Location loc, SpawnStep step) {
        if (loc.getWorld() == null) return null;
        try {
            var raw = loc.getWorld().spawnEntity(loc, step.type());
            if (!(raw instanceof LivingEntity le)) {
                raw.remove();
                return null;
            }
            configureSpawnedLiving(le, step.hpMult(), step.dmgMult(), step.speedMult(), step.scaleMult(),
                    step.followRangeMult(), step.equipment(), step.name());
            return le;
        } catch (Exception e) {
            LOG.warning("生成怪物失败 " + step.type() + "：" + e.getMessage());
            return null;
        }
    }

    public static LivingEntity spawnPassengerEntity(Location loc, PassengerSpawn ps) {
        if (loc.getWorld() == null) return null;
        try {
            var raw = loc.getWorld().spawnEntity(loc, ps.type());
            if (!(raw instanceof LivingEntity le)) {
                raw.remove();
                return null;
            }
            configureSpawnedLiving(le, ps.hpMult(), ps.dmgMult(), ps.speedMult(), ps.scaleMult(),
                    ps.followRangeMult(), ps.equipment(), ps.name());
            if (le instanceof Mob m) m.setAware(true);
            return le;
        } catch (Exception e) {
            LOG.warning("生成乘客失败 " + ps.type() + "：" + e.getMessage());
            return null;
        }
    }

    /** 调试用：在玩家位置生成带技能的怪。 */
    public static LivingEntity spawnDebugMob(Location loc, org.bukkit.entity.EntityType type,
                                             double hpMult, double dmgMult, double spdMult,
                                             List<String> skillIds) {
        SpawnStep fake = new SpawnStep(
                new Vec3(loc.getX(), loc.getY(), loc.getZ()),
                type, 1, hpMult, dmgMult, spdMult, 1.0, 1.0,
                0, skillIds, List.of(), List.of(), List.of());
        LivingEntity le = spawnMount(loc, fake);
        if (le != null) MobSkillService.attach(le, skillIds);
        return le;
    }

    private static void scheduleNameReassert(LivingEntity le) {
        var plugin = MaggoteersPlugin.getInstance();
        if (plugin == null) return;
        Runnable r = () -> {
            if (le.isValid()) MobDisplayNames.reassertIfNeeded(le);
        };
        Bukkit.getScheduler().runTask(plugin, r);
        Bukkit.getScheduler().runTaskLater(plugin, r, 1L);
        Bukkit.getScheduler().runTaskLater(plugin, r, 2L);
    }

    private static void applyMobStats(LivingEntity le, double hpMult, double dmgMult, double speedMult,
                                      double scaleMult, double followRangeMult) {
        // setSize 会重置 hp/dmg/speed，必须在 applyMultipliers 之前
        ensureCombatSlimeSize(le);
        double gScale = globalScaleMult();
        double gFollow = globalFollowRangeMult();
        applyMultipliers(le, hpMult, dmgMult, speedMult);
        applyScale(le, scaleMult * gScale);
        applyFollowRange(le, followRangeMult * gFollow);
        preventOverworldZombification(le);
    }

    /** 局内史莱姆一律三阶（size 4，原版可裂两次的大史莱姆；分裂由 {@code SlimeSplitEvent} 取消）。
     *  setSize 会把基础 HP 重置为 size²（size4→16），这里复位回 size2 档（4），使强度仍只由 waves.yml coeff 决定；
     *  攻击力随 size 走原版（大史莱姆单跳伤害更高），属三阶身份的一部分。岩浆怪保持保底 size 2。 */
    private static void ensureCombatSlimeSize(LivingEntity le) {
        // 26.2 起 MagmaCube 不再继承 Slime；AbstractCubeMob 统一史莱姆/岩浆怪
        if (le instanceof Slime slime) {
            slime.setSize(4);
            AttributeInstance hp = slime.getAttribute(Attribute.MAX_HEALTH);
            if (hp != null) {
                hp.setBaseValue(4.0);
            }
        } else if (le instanceof AbstractCubeMob cube && cube.getSize() < 2) {
            cube.setSize(2);
        }
        // 源头堵死分裂：给所有插件生成的 cube 打 PDC 标记。分裂在 AbstractCubeMob.remove() 触发，
        // 依赖 PENDING_SPLIT_CANCEL 的旧逻辑要求 handleMobDeath 恰好先于 remove 打标（事件顺序耦合）；
        // 直接查实体自带标记则无论谁先谁后、追踪与否都无条件取消（2026-08-27 波末史莱姆残留修复）。
        if (le instanceof AbstractCubeMob) {
            le.getPersistentDataContainer().set(combatCubeKey(), PersistentDataType.BOOLEAN, true);
        }
    }

    /**
     * 插件生成的战斗 cube（史莱姆/岩浆怪）标记键；{@code SlimeSplitEvent} 据此无条件取消分裂。
     * 惰性创建（不落静态初始化）：测试环境 {@code MaggoteersPlugin.getInstance()} 为 null，静态初始化会炸。
     */
    public static NamespacedKey combatCubeKey() {
        return new NamespacedKey(MaggoteersPlugin.getInstance(), "combat_cube");
    }

    /** 主世界虚空图里猪灵/疣猪兽默认会转化；局内刷怪一律免疫。 */
    private static void preventOverworldZombification(LivingEntity le) {
        if (le instanceof PiglinAbstract piglin) piglin.setImmuneToZombification(true);
        else if (le instanceof Hoglin hoglin) hoglin.setImmuneToZombification(true);
    }

    private static double globalScaleMult() {
        return MaggoteersPlugin.getInstance().getConfig().getDouble("mob_attributes.scale", 1.0);
    }

    private static double globalFollowRangeMult() {
        return MaggoteersPlugin.getInstance().getConfig().getDouble("mob_attributes.follow_range", 1.0);
    }

    private static void applyMultipliers(LivingEntity le, double hpMult, double dmgMult, double spdMult) {
        AttributeInstance hp = le.getAttribute(Attribute.MAX_HEALTH);
        if (hp != null) {
            double want = hp.getBaseValue() * Math.max(0.0001, hpMult);
            hp.setBaseValue(want);
            double got = hp.getBaseValue();                  // 会被属性上限钳制（spigot.yml maxHealth.max）
            warnIfHealthClamped(le, want, got);
            le.setHealth(Math.min(want, hp.getValue()));     // getValue() = 实际属性上限（受 spigot.yml 约束），不再硬编码 1024（D12）
        }
        AttributeInstance dmg = le.getAttribute(Attribute.ATTACK_DAMAGE);
        if (dmg != null) dmg.setBaseValue(dmg.getBaseValue() * Math.max(0.0001, dmgMult));
        AttributeInstance spd = le.getAttribute(Attribute.MOVEMENT_SPEED);
        if (spd != null) spd.setBaseValue(spd.getBaseValue() * Math.max(0.0001, spdMult));
    }

    /**
     * 血量上限自检（D12 的运行时对账）：{@code MAX_HEALTH} 的属性上限由
     * {@code spigot.yml → settings.attribute.maxHealth.max} 决定（默认 1024，改完**必须重启**）。
     * 这里只在"想要的血量被钳掉"时告警一次/类型，避免刷屏；同时首次出现 >1024 的怪时记一行 INFO，
     * 用来证明上限确实放开了（而不是靠猜）。
     */
    private static void warnIfHealthClamped(LivingEntity le, double want, double got) {
        if (want > got + 1.0e-6) {
            if (CLAMP_WARNED.add(le.getType())) {
                LOG.warning("MAX_HEALTH 被属性上限钳制：" + le.getType() + " 想要 " + want + " 实际 " + got
                        + "（检查 spigot.yml 的 settings.attribute.maxHealth.max，改完需重启）");
            }
        } else if (got > 1024.0 && HP_OVER_1024_LOGGED.add(le.getType())) {
            LOG.info("MAX_HEALTH 超过 1024 已生效：" + le.getType() + " = " + got
                    + "（spigot.yml maxHealth.max 生效中）");
        }
    }

    /** 只告警一次的实体类型集合（钳制 / 超过 1024 各一份）。 */
    private static final java.util.Set<org.bukkit.entity.EntityType> CLAMP_WARNED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.util.Set<org.bukkit.entity.EntityType> HP_OVER_1024_LOGGED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void applyScale(LivingEntity le, double mult) {
        if (Math.abs(mult - 1.0) < 1.0e-6) return;
        AttributeInstance scale = le.getAttribute(Attribute.SCALE);
        if (scale == null) {
            LOG.fine(le.getType() + " 无 SCALE 属性，忽略 scale 配置");
            return;
        }
        scale.setBaseValue(scale.getBaseValue() * Math.max(0.05, mult));
    }

    private static void applyFollowRange(LivingEntity le, double mult) {
        if (Math.abs(mult - 1.0) < 1.0e-6) return;
        AttributeInstance fr = le.getAttribute(Attribute.FOLLOW_RANGE);
        if (fr == null) return;
        fr.setBaseValue(fr.getBaseValue() * Math.max(0.1, mult));
    }

    static void applyEquipment(LivingEntity le, List<MobEquipment> equipment) {
        if (equipment == null || equipment.isEmpty()) return;
        EntityEquipment eq = le.getEquipment();
        if (eq == null) return;
        for (MobEquipment entry : equipment) {
            if (entry.itemId().isBlank()) continue;
            ItemStack stack = ItemService.createItem(entry.itemId(), 1).orElse(null);
            if (stack == null) {
                LOG.warning("怪物装备物品不存在（ItemCreator）：" + entry.itemId());
                continue;
            }
            EquipmentSlot slot = parseSlot(entry.slot());
            if (slot == null) {
                LOG.warning("未知装备槽 " + entry.slot() + "，物品 " + entry.itemId());
                continue;
            }
            setSlot(eq, slot, stack);
        }
    }

    private static EquipmentSlot parseSlot(String raw) {
        if (raw == null || raw.isBlank()) return EquipmentSlot.HAND;
        return switch (raw.toUpperCase()) {
            case "HEAD", "HELMET" -> EquipmentSlot.HEAD;
            case "CHEST", "CHESTPLATE" -> EquipmentSlot.CHEST;
            case "LEGS", "LEGGINGS" -> EquipmentSlot.LEGS;
            case "FEET", "BOOTS" -> EquipmentSlot.FEET;
            case "MAIN_HAND", "HAND", "MAIN" -> EquipmentSlot.HAND;
            case "OFF_HAND", "OFFHAND" -> EquipmentSlot.OFF_HAND;
            default -> null;
        };
    }

    private static void setSlot(EntityEquipment eq, EquipmentSlot slot, ItemStack stack) {
        switch (slot) {
            case HEAD -> {
                eq.setHelmet(stack);
                eq.setHelmetDropChance(0f);
            }
            case CHEST -> {
                eq.setChestplate(stack);
                eq.setChestplateDropChance(0f);
            }
            case LEGS -> {
                eq.setLeggings(stack);
                eq.setLeggingsDropChance(0f);
            }
            case FEET -> {
                eq.setBoots(stack);
                eq.setBootsDropChance(0f);
            }
            case HAND -> {
                eq.setItemInMainHand(stack);
                eq.setItemInMainHandDropChance(0f);
            }
            case OFF_HAND -> {
                eq.setItemInOffHand(stack);
                eq.setItemInOffHandDropChance(0f);
            }
            default -> { }
        }
    }

    /** 战斗触发型词缀已退役；技能改为 Task 6 的 MobSkillService 挂载。 */
    private MobFactory() {}
}
