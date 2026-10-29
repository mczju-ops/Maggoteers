package io.mczju.maggoteers.util;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;

import java.util.logging.Logger;

/** 粒子效果工具：给范围效果提供视觉范围提示。 */
public final class ParticleEffects {
    private static final Logger LOG = Logger.getLogger("Maggoteers");

    private ParticleEffects() {}

    /**
     * 安全播粒子：DRAGON_BREATH/SONIC_BOOM 等 scalable 粒子（data class = Float）走裸 6 参
     * {@code spawnParticle} 会抛 {@code missing required data class java.lang.Float}（scalable 粒子须显式传
     * Float data 作 scale），异常沿 {@code executeAbility} 冒泡中断整个技能链
     * （2026-08-22 沧溟星梦"完全没有效果"）。这里统一兜底：Float data 走 8 参带 data 重载、scale 固定 1.0
     * （正常大小烟雾；直接把 extra 当 scale 传 0.01~0.02 会小到不可见）；Void 走原重载；
     * 其余需复杂 data 的粒子（DUST/SCULK_CHARGE 等）无法从配置推导，跳过并警告一次。
     */
    public static void spawn(World world, Particle particle, Location loc,
                             int count, double dx, double dy, double dz, double extra) {
        if (world == null || particle == null || loc == null || count <= 0) return;
        Class<?> data = particle.getDataType();
        if (data == Float.class) {
            // 注意：传 (float) extra 会被拓宽成 double、落回 7 参 data=null 重载，仍抛异常（2026-08-22 实测）
            world.spawnParticle(particle, loc, count, dx, dy, dz, extra, (float) 1.0);
        } else if (data == Void.class) {
            world.spawnParticle(particle, loc, count, dx, dy, dz, extra);
        } else {
            LOG.warning("particle " + particle + " requires data class " + data.getSimpleName()
                    + " — skipped (cannot derive from config)");
        }
    }

    /** 加粗水平圆环（魔法物品默认）。每格密度 12→7、每点 3→2 粒子：扩散环整体稀疏化（2026-08-22）。 */
    public static void playThickRing(World world, Location center, double radius, Particle particle, int points) {
        if (world == null || center == null || radius <= 0 || particle == null || points <= 0) return;
        double y = center.getY() + 0.5;
        double cx = center.getX(), cz = center.getZ();
        int n = Math.max(points, (int) (radius * 7));
        for (int i = 0; i < n; i++) {
            double angle = 2 * Math.PI * i / n;
            double x = cx + radius * Math.cos(angle);
            double z = cz + radius * Math.sin(angle);
            spawn(world, particle, new Location(world, x, y, z), 2, 0.04, 0.06, 0.04, 0.01);
        }
    }

    /** 三轴正交护盾环：xz 水平 + xy/yz 两圈竖环，以 center 为原点。 */
    public static void playShieldAxes(World world, Location center, double radius, Particle particle, int points) {
        if (world == null || center == null || radius <= 0 || particle == null || points <= 0) return;
        double cx = center.getX(), cy = center.getY(), cz = center.getZ();
        int n = Math.max(points, (int) (radius * 7));
        // xz（水平）
        for (int i = 0; i < n; i++) {
            double a = 2 * Math.PI * i / n;
            spawn(world, particle, new Location(world, cx + radius * Math.cos(a), cy, cz + radius * Math.sin(a)),
                    2, 0.04, 0.06, 0.04, 0.01);
        }
        // xy（竖环，法线 ≈ z）
        for (int i = 0; i < n; i++) {
            double a = 2 * Math.PI * i / n;
            spawn(world, particle, new Location(world, cx + radius * Math.cos(a), cy + radius * Math.sin(a), cz),
                    2, 0.04, 0.06, 0.04, 0.01);
        }
        // yz（竖环，法线 ≈ x）
        for (int i = 0; i < n; i++) {
            double a = 2 * Math.PI * i / n;
            spawn(world, particle, new Location(world, cx, cy + radius * Math.cos(a), cz + radius * Math.sin(a)),
                    2, 0.04, 0.06, 0.04, 0.01);
        }
    }

    /** 简便重载：默认用 FLAME + 加粗密度。 */
    public static void playAreaRing(World world, Location center, double radius) {
        playThickRing(world, center, radius, Particle.FLAME, Math.max(24, (int) (radius * 7)));
    }

    /** 球壳稀疏几何的纯计数（可测）：极点 2、竖环每点 count=1、总 ≤1.3×points。 */
    public record ShieldShellCounts(int polePoints, int verticalRingCountPerPoint, int total) {}

    static ShieldShellCounts shieldShellPointCounts(int points) {
        int lats = Math.max(3, (int) Math.round(points / 12.0));
        int nonPole = 0;
        for (int i = 1; i < lats; i++) {
            double sinPhi = Math.sin(Math.PI * i / lats);
            nonPole += Math.max(4, (int) Math.round(points * 0.5 * sinPhi));
        }
        int vertical = Math.max(6, points / 8) * 2;        // 两圈竖环，每点 count=1
        return new ShieldShellCounts(2, 1, 2 + nonPole + vertical);
    }

    /** 经纬球壳：lats 条水平纬线环 + 两条竖环（法线 x/z），以 center 为球心。
     *  稀疏策略：两极各 1 点（不堆环）；纬线环数 ~points/12；每环点数随 sin(phi) 缩减；
     *  竖环各 ~points/8 点，每点 count=1（勿复用 playShieldAxes 的 count=2）。 */
    public static void playShieldShell(World world, Location center, double radius,
                                       Particle particle, int points) {
        if (world == null || center == null || radius <= 0 || particle == null || points <= 0) return;
        double cx = center.getX(), cy = center.getY(), cz = center.getZ();
        int lats = Math.max(3, (int) Math.round(points / 12.0));
        for (int i = 0; i <= lats; i++) {
            double phi = Math.PI * i / lats;
            double sinPhi = Math.sin(phi);
            if (i == 0 || i == lats) {
                spawn(world, particle, new Location(world, cx, cy + radius * Math.cos(phi), cz), 1, 0, 0, 0, 0.005);
                continue;
            }
            int ring = Math.max(4, (int) Math.round(points * 0.5 * sinPhi));
            double y = cy + radius * Math.cos(phi);
            double r = radius * sinPhi;
            for (int k = 0; k < ring; k++) {
                double a = 2 * Math.PI * k / ring;
                spawn(world, particle, new Location(world, cx + r * Math.cos(a), y, cz + r * Math.sin(a)),
                        1, 0.02, 0.02, 0.02, 0.005);
            }
        }
        playShieldVerticalRing(world, center, radius, particle, Math.max(6, points / 8), true);
        playShieldVerticalRing(world, center, radius, particle, Math.max(6, points / 8), false);
    }

    /** 包内私有：画 xy / yz 竖环，每点 count=1（复用 playShieldAxes 竖环几何）。
     *  {@code xy=true} → xy 平面环（法线 ≈ z）；{@code xy=false} → yz 平面环（法线 ≈ x）。 */
    static void playShieldVerticalRing(World world, Location center, double radius, Particle particle,
                                       int points, boolean xy) {
        if (world == null || center == null || radius <= 0 || particle == null || points <= 0) return;
        double cx = center.getX(), cy = center.getY(), cz = center.getZ();
        int n = Math.max(6, points);
        for (int i = 0; i < n; i++) {
            double a = 2 * Math.PI * i / n;
            double x, y, z;
            if (xy) {
                x = cx + radius * Math.cos(a);
                y = cy + radius * Math.sin(a);
                z = cz;
            } else {
                x = cx;
                y = cy + radius * Math.cos(a);
                z = cz + radius * Math.sin(a);
            }
            spawn(world, particle, new Location(world, x, y, z), 1, 0.02, 0.02, 0.02, 0.005);
        }
    }
}
