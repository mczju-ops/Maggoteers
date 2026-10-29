package io.mczju.maggoteers.mob;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;

import java.util.Set;

/** 传送安全：候选落点避开虚空与地图外（结构边界外）。找不到安全点返回 null（调用方不传）。 */
public final class TeleportSafety {

    /** 结构外判定半径（自建虚空世界无 WorldBorder；用原点 ± 该值近似地图边界）。 */
    private static final double MAP_RADIUS = 160.0;
    private static final double VOID_FLOOR = -32.0;   // 低于此且脚下无方块 = 虚空

    /**
     * 不可站立方块：空气/液体 + 常见非碰撞装饰块。
     * <p>⚠️ 不用 {@code Material.isSolid()}（其依赖服务端 Registry，单元测试无服务器时抛错）；
     * 用枚举常量+集合判定的纯逻辑等价物（默认=可站立；列出的才算不可站立）。
     */
    private static final Set<Material> NON_SOLID_GROUND = Set.of(
            Material.AIR, Material.CAVE_AIR, Material.VOID_AIR,
            Material.WATER, Material.LAVA, Material.BUBBLE_COLUMN,
            Material.TORCH, Material.WALL_TORCH, Material.SOUL_TORCH, Material.SOUL_WALL_TORCH,
            Material.REDSTONE_TORCH, Material.REDSTONE_WALL_TORCH,
            Material.LANTERN, Material.SOUL_LANTERN,
            Material.FIRE, Material.SOUL_FIRE,
            Material.SHORT_GRASS, Material.TALL_GRASS, Material.FERN, Material.LARGE_FERN, Material.DEAD_BUSH,
            Material.DANDELION, Material.POPPY, Material.BLUE_ORCHID, Material.ALLIUM,
            Material.AZURE_BLUET, Material.RED_TULIP, Material.ORANGE_TULIP,
            Material.WHITE_TULIP, Material.PINK_TULIP, Material.OXEYE_DAISY,
            Material.CORNFLOWER, Material.LILY_OF_THE_VALLEY, Material.WITHER_ROSE,
            Material.SUNFLOWER, Material.LILAC, Material.ROSE_BUSH, Material.PEONY, Material.TORCHFLOWER,
            Material.OAK_SAPLING, Material.SPRUCE_SAPLING, Material.BIRCH_SAPLING,
            Material.JUNGLE_SAPLING, Material.ACACIA_SAPLING, Material.DARK_OAK_SAPLING,
            Material.CHERRY_SAPLING, Material.MANGROVE_PROPAGULE,
            Material.RAIL, Material.POWERED_RAIL, Material.DETECTOR_RAIL, Material.ACTIVATOR_RAIL,
            Material.REDSTONE_WIRE, Material.REPEATER, Material.COMPARATOR, Material.LEVER,
            Material.STONE_BUTTON, Material.OAK_BUTTON, Material.SPRUCE_BUTTON, Material.BIRCH_BUTTON,
            Material.JUNGLE_BUTTON, Material.ACACIA_BUTTON, Material.DARK_OAK_BUTTON,
            Material.CRIMSON_BUTTON, Material.WARPED_BUTTON, Material.MANGROVE_BUTTON,
            Material.CHERRY_BUTTON, Material.POLISHED_BLACKSTONE_BUTTON, Material.BAMBOO_BUTTON,
            Material.OAK_SIGN, Material.SPRUCE_SIGN, Material.BIRCH_SIGN, Material.JUNGLE_SIGN,
            Material.ACACIA_SIGN, Material.DARK_OAK_SIGN, Material.CRIMSON_SIGN, Material.WARPED_SIGN,
            Material.MANGROVE_SIGN, Material.BAMBOO_SIGN, Material.CHERRY_SIGN,
            Material.OAK_WALL_SIGN, Material.SPRUCE_WALL_SIGN, Material.BIRCH_WALL_SIGN,
            Material.JUNGLE_WALL_SIGN, Material.ACACIA_WALL_SIGN, Material.DARK_OAK_WALL_SIGN,
            Material.CRIMSON_WALL_SIGN, Material.WARPED_WALL_SIGN, Material.MANGROVE_WALL_SIGN,
            Material.BAMBOO_WALL_SIGN, Material.CHERRY_WALL_SIGN,
            Material.VINE, Material.GLOW_LICHEN, Material.TWISTING_VINES, Material.WEEPING_VINES,
            Material.KELP, Material.KELP_PLANT, Material.SEAGRASS, Material.TALL_SEAGRASS,
            Material.SUGAR_CANE, Material.COBWEB, Material.SNOW, Material.SCULK_VEIN,
            Material.FLOWER_POT, Material.LADDER, Material.END_ROD,
            Material.TRIPWIRE, Material.TRIPWIRE_HOOK
    );

    private TeleportSafety() {}

    /** 纯逻辑（可测）：绝对坐标距层原点 (0,0) 超出 MAP_RADIUS 即视为出图。 */
    static boolean outsideMap(double x, double z) {
        return Math.hypot(x, z) > MAP_RADIUS;
    }

    /** 纯逻辑（可测）：可站立地面判定（默认=可站立；空气/液体/装饰块/基岩除外）。 */
    static boolean isSolidGround(Material t) {
        return t != null && t != Material.BEDROCK && !NON_SOLID_GROUND.contains(t);
    }

    /** 在 anchor 上方 offsetY 找安全点：向下扫描找实心地面上的空位；越界/虚空则 null。默认以 (0,0) 为层原点。 */
    public static Location safeTarget(LivingEntity anchor, double offsetY) {
        return safeTarget(anchor, offsetY, 0, 0);
    }

    /** 以层原点 (originX, originZ) 为中心的安全落点（Act2/3 层原点非 (0,0)，须显式传入）。 */
    public static Location safeTarget(LivingEntity anchor, double offsetY,
                                      double originX, double originZ) {
        if (anchor == null || anchor.getWorld() == null) return null;
        Location base = anchor.getLocation().clone().add(0, offsetY, 0);
        return scanDown(anchor.getWorld(), base.getX(), base.getY(), base.getZ(), originX, originZ);
    }

    /**
     * 附近随机安全位置：以 caster 为圆心、radius 内随机候选（上限 32 次），逐个 scanDown 校验；
     * 全失败回落 caster 原位。必须传当前层原点 (originX, originZ)（Act2/3 非 (0,0)），否则防出图误判。
     */
    public static Location randomSafeTarget(LivingEntity caster, double radius,
                                            double originX, double originZ) {
        if (caster == null || caster.getWorld() == null) return null;
        java.util.Random rnd = new java.util.Random();
        for (int i = 0; i < 32; i++) {
            double ang = rnd.nextDouble() * 2 * Math.PI;
            double dist = rnd.nextDouble() * radius;
            Location base = caster.getLocation().clone()
                    .add(Math.cos(ang) * dist, 0, Math.sin(ang) * dist);
            Location safe = scanDown(caster.getWorld(),
                    base.getX(), base.getY(), base.getZ(), originX, originZ);
            if (safe != null) return safe;
        }
        return caster.getLocation().clone();
    }

    /** 坐标级安全落点（向下扫 6 格找"脚下实心 + 自身可站立"），越界/虚空返回 null。 */
    static Location scanDown(org.bukkit.World world, double x, double y, double z,
                             double originX, double originZ) {
        if (world == null || outsideMap(x - originX, z - originZ)) return null;
        for (int dy = 0; dy >= -6; dy--) {
            Location cand = new Location(world, x, y + dy, z);
            Block foot = cand.getBlock();
            Block below = cand.clone().add(0, -1, 0).getBlock();
            if (foot.isLiquid() || foot.getType().isAir()) {
                if (isSolidGround(below.getType()) && cand.getY() > VOID_FLOOR) {
                    return cand.clone().add(0.5, 0, 0.5);
                }
            }
        }
        return null;
    }
}
