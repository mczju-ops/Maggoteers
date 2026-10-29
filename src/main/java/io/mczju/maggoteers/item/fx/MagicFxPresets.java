package io.mczju.maggoteers.item.fx;

import io.mczju.maggoteers.MaggoteersPlugin;
import io.mczju.maggoteers.util.ParticleEffects;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

public final class MagicFxPresets {
    private MagicFxPresets() {}

    public static void play(Player player, MagicUseFx fx) {
        if (player == null || fx == null) return;
        fx = scaledForCaster(player, fx);
        switch (fx.preset()) {
            case NONE -> { }
            case SPIRAL_RADIUS -> spiralRadius(player, fx);
            case AIM_RAY -> aimRay(player, fx);
            case SHIELD_SHELL -> play(player.getLocation().add(0, player.getHeight() * 0.5, 0), fx);
            default -> play(player.getLocation(), fx);
        }
    }

    /**
     * 施法范围（虚拟属性 {@code RANGE}）缩放：环半径 / 射线长度 / 螺旋半径都吃同一个系数。
     *
     * <p>2026-10-28 用户口径：新的「施法范围」attribute 必须同时影响**技能的粒子效果半径**
     * （用来标记范围的环），否则 +范围藏品会让真实判定圈比看到的环更大。
     * 这里在「玩家施法」这一层统一缩放；{@link #play(Location, MagicUseFx)}（怪物/标记点）不缩放。
     * 怪物施法者 {@code rangeMultiplierOf} 恒为 1.0，天然不受影响。
     */
    public static MagicUseFx scaledForCaster(Player player, MagicUseFx fx) {
        if (player == null || fx == null) return fx;
        double m = io.mczju.maggoteers.effect.EffectService.rangeMultiplierOf(player);
        if (!Double.isFinite(m) || Math.abs(m - 1.0) < 1e-6) return fx;
        return new MagicUseFx(fx.sound(), fx.soundVolume(), fx.soundPitch(), fx.preset(), fx.particle(),
                fx.radius() * m, fx.rayLength() * m, fx.density(), fx.rippleRings(), fx.expandSteps(),
                fx.spiralTicks(), fx.durationTicks());
    }

    /**
     * Location 级重载（Spec1 §4.3）：环类以 {@code origin} 为圆心，施法者抽象为坐标点。
     * SPIRAL_RADIUS / AIM_RAY 依赖 yaw/视线，仍走 {@link #play(Player, MagicUseFx)}。
     * SHIELD_SHELL 以 origin 为球心。
     */
    public static void play(Location origin, MagicUseFx fx) {
        if (origin == null || fx == null || origin.getWorld() == null) return;
        switch (fx.preset()) {
            case NONE -> { }
            case THICK_RING -> ParticleEffects.playThickRing(
                    origin.getWorld(), origin, fx.radius(), fx.particle(), fx.density());
            case RIPPLE_RINGS -> rippleRings(origin, fx);
            case SMOOTH_EXPAND_RING -> smoothExpandRing(origin, fx);
            case SHIELD_AXES -> ParticleEffects.playShieldAxes(
                    origin.getWorld(), origin, fx.radius(), fx.particle(), fx.density());
            case SHIELD_SHELL -> ParticleEffects.playShieldShell(
                    origin.getWorld(), origin, fx.radius(), fx.particle(), fx.density());
            case DOT_ABOVE -> dotAbove(origin, fx);
            default -> { /* SPIRAL_RADIUS / AIM_RAY 依赖 yaw/视线，Player 重载专用 */ }
        }
    }

    /**
     * 2026-10-29：**扇形（CONE）扩散粒子**——从圆心沿准星向锥内由内向外扩散 {@code expand_steps} 帧。
     * <p>张角只有 effect 参数知道（{@link MagicUseFx} 不含角度），所以由
     * {@code EffectService.executeConeCore} 直接调用；{@link MagicFxPreset#CONE_SPREAD} 在
     * {@link #play(Player, MagicUseFx)} / {@link #play(Location, MagicUseFx)} 里是空操作（只贡献音效）。
     */
    public static void playConeAlong(Location eye, Vector dir, double radius, double angleDeg, MagicUseFx fx) {
        playConeAlong(eye, dir, radius, angleDeg, fx, 0.0);
    }

    /**
     * @param yOffset 整片扇形的 y 偏移（负值下移；清扫服务用 -0.5 避免糊在准头上挡视野）
     */
    public static void playConeAlong(Location eye, Vector dir, double radius, double angleDeg,
                                     MagicUseFx fx, double yOffset) {
        if (eye == null || eye.getWorld() == null || dir == null || fx == null) return;
        Particle p = fx.particle();
        if (p == null || radius <= 0) return;
        // 2026-10-29：扇形永远贴在垂直于 y 轴的平面上——只取朝向的水平分量
        Vector tmp = dir.clone();
        tmp.setY(0);
        final Vector d;
        if (tmp.lengthSquared() < 1e-9) {
            double yawRad = Math.toRadians(eye.getYaw());
            d = new Vector(-Math.sin(yawRad), 0, Math.cos(yawRad)).normalize();
        } else {
            d = tmp.normalize();
        }
        Vector right = d.clone().crossProduct(new Vector(0, 1, 0)).normalize();
        double half = Math.toRadians(Math.max(0.5, angleDeg) / 2.0);
        int frames = Math.max(6, Math.min(24, fx.expandSteps() > 0 ? fx.expandSteps() / 2 : 10));
        int perFrame = Math.max(8, Math.min(48, fx.density() / 3));
        new BukkitRunnable() {
            int f = 0;

            @Override
            public void run() {
                if (f > frames || eye.getWorld() == null) {
                    cancel();
                    return;
                }
                double r = radius * (f / (double) frames);
                for (int i = 0; i < perFrame; i++) {
                    double yaw = (Math.random() * 2 - 1) * half;
                    // 水平扇面：只在 y 上加一点抖动当视觉厚度
                    double jitterY = (Math.random() * 2 - 1) * 0.12;
                    Vector radial = right.clone().multiply(Math.sin(yaw))
                            .add(d.clone().multiply(Math.cos(yaw)))
                            .normalize();
                    Location at = eye.clone().add(radial.multiply(r)).add(0, yOffset + jitterY, 0);
                    ParticleEffects.spawn(eye.getWorld(), p, at, 1, 0.05, 0.05, 0.05, 0.01);
                }
                f++;
            }
        }.runTaskTimer(MaggoteersPlugin.getInstance(), 0L, 1L);
    }

    /** 沿方向绘制加粗光束（Ex咖喱棒等），起点 eye，长度 length。 */
    public static void playBeamAlong(org.bukkit.Location eye, Vector dir, double length, MagicUseFx fx) {
        if (eye.getWorld() == null || dir == null) return;
        Vector d = dir.clone().normalize();
        Particle p = fx.particle();
        int steps = Math.max(20, fx.density());
        double step = length / steps;
        for (int i = 0; i <= steps; i++) {
            org.bukkit.Location pt = eye.clone().add(d.clone().multiply(step * i));
            ParticleEffects.spawn(eye.getWorld(), p, pt, 10, 0.12, 0.12, 0.12, 0.02);
        }
    }

    /** 实体正上方约 1 格：加粗圆点。 */
    public static void dotAbove(Location entityLoc, MagicUseFx fx) {
        if (entityLoc.getWorld() == null) return;
        Location c = entityLoc.clone().add(0, 1.05, 0);
        Particle p = fx.particle();
        int n = Math.max(12, fx.density() / 3);
        double dotR = 0.45;
        for (int i = 0; i < n; i++) {
            double a = 2 * Math.PI * i / n;
            ParticleEffects.spawn(c.getWorld(), p,
                    new Location(c.getWorld(), c.getX() + dotR * Math.cos(a), c.getY(), c.getZ() + dotR * Math.sin(a)),
                    5, 0.03, 0.04, 0.03, 0.012);
        }
        ParticleEffects.spawn(c.getWorld(), p, c, 8, 0.08, 0.05, 0.08, 0.01);
    }

    /** 沿视线加粗射线，长度 {@link MagicUseFx#rayLength()}。 */
    private static void aimRay(Player player, MagicUseFx fx) {
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        double len = fx.rayLength();
        Particle p = fx.particle();
        int steps = Math.max(20, fx.density() / 2);
        double step = len / steps;
        for (int i = 0; i <= steps; i++) {
            Location pt = eye.clone().add(dir.clone().multiply(step * i));
            ParticleEffects.spawn(eye.getWorld(), p, pt, 8, 0.08, 0.08, 0.08, 0.015);
        }
    }

    /**
     * 波浪式同心环：环数 {@code ripple_rings}，每步绘制当前环并淡化重绘旧环（残留可见）。
     */
    private static void rippleRings(Location origin, MagicUseFx fx) {
        new BukkitRunnable() {
            int ring = 0;
            final int rings = Math.max(2, fx.rippleRings());

            @Override public void run() {
                if (ring >= rings) {
                    cancel();
                    return;
                }
                for (int k = 0; k <= ring; k++) {
                    double r = fx.radius() * (k + 1) / rings;
                    int dens = k == ring ? fx.density() : Math.max(6, fx.density() / 4);
                    ParticleEffects.playThickRing(origin.getWorld(), origin, r, fx.particle(), dens);
                }
                ring++;
            }
        }.runTaskTimer(MaggoteersPlugin.getInstance(), 0L, 5L);
    }

    /**
     * 单环平滑放大，步数 {@code expand_steps}，同一时刻主环最亮。
     *
     * <p>2026-10-28（圣城庇护反馈）：扩散时**半径离散增长点变稀疏**（预设在 AURA 里把
     * {@code expand_steps} 调到 10），但**每个圆周更密集**——这里按周长给点
     * （{@code density / 32} 点/格），保证任意半径下圈都是连续的一条，而不是"小圈堆点、大圈断线"。
     */
    private static void smoothExpandRing(Location origin, MagicUseFx fx) {
        new BukkitRunnable() {
            int step = 0;
            final int steps = Math.max(8, fx.expandSteps());

            @Override public void run() {
                if (step > steps) {
                    cancel();
                    return;
                }
                double t = step / (double) steps;
                double r = fx.radius() * t;
                if (r > 0.05) {
                    int ringPoints = Math.max(10,
                            (int) Math.round(2 * Math.PI * r * (fx.density() / 32.0)));
                    ParticleEffects.playThickRing(
                            origin.getWorld(), origin, r, fx.particle(), ringPoints);
                }
                step++;
            }
        }.runTaskTimer(MaggoteersPlugin.getInstance(), 0L, 1L);
    }

    /** 2–3 条弧段绕玩家旋转的剑气半径粒子。 */
    private static void spiralRadius(Player player, MagicUseFx fx) {
        new BukkitRunnable() {
            int tick = 0;
            final int duration = Math.max(8, fx.spiralTicks());

            @Override public void run() {
                if (!player.isOnline() || tick >= duration) {
                    cancel();
                    return;
                }
                Location base = player.getLocation();
                double yawRad = Math.toRadians(base.getYaw());
                double spin = 2 * Math.PI * tick / duration;
                Particle p = fx.particle();
                int arcs = 3;
                double arcWidth = Math.toRadians(28);
                for (int a = 0; a < arcs; a++) {
                    double baseAngle = spin + a * (2 * Math.PI / arcs);
                    int steps = Math.max(6, fx.density() / 10);
                    for (int i = 0; i <= steps; i++) {
                        double t = i / (double) steps;
                        double r = fx.radius() * t;
                        double angle = baseAngle + (t - 0.5) * arcWidth;
                        double x = base.getX() + r * Math.sin(angle - yawRad);
                        double z = base.getZ() + r * Math.cos(angle - yawRad);
                        ParticleEffects.spawn(base.getWorld(), p,
                                new Location(base.getWorld(), x, base.getY() + 0.9, z), 1, 0.02, 0.02, 0.02, 0.01);
                    }
                }
                tick++;
            }
        }.runTaskTimer(MaggoteersPlugin.getInstance(), 0L, 1L);
    }
}
