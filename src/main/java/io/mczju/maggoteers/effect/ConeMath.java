package io.mczju.maggoteers.effect;

import org.bukkit.Location;
import org.bukkit.util.Vector;

/**
 * 扇形打击（{@link Effect#CONE}）的几何判定（2026-10-29）。
 *
 * <p>纯函数、不碰 Bukkit 世界状态 —— 单测直接喂坐标即可（{@code ConeMathTest}）。
 *
 * <p>**形状（2026-10-29 用户口径修正）**：永远是一块**垂直于 y 轴的平面扇形**——
 * 只看水平（X/Z）分量：方向取准星的水平朝向，距离取水平距离，高度差完全不参与判定。
 * 这样抬头/低头都不会把扇面抬起来（上一版做成 3D 圆锥，实机看着像"斜着打"）。
 *
 * <p>缩放口径（用户口径）：**半径吃「施法范围」RANGE，张角不吃**——半径由调用方先缩放。
 */
public final class ConeMath {

    private ConeMath() {}

    /**
     * @param origin   扇形顶点（一般是施法者眼睛；只取其 X/Z）
     * @param dir      朝向（**只取水平分量**；水平分量为 0 时视为"无方向"，退化成整圆）
     * @param angleDeg 整张角（度；半角 = angleDeg / 2）
     * @param radius   半径（水平距离，已按 RANGE 缩放）
     * @param point    目标点（只取其 X/Z）
     */
    public static boolean inCone(Location origin, Vector dir, double angleDeg, double radius, Location point) {
        if (origin == null || dir == null || point == null) return false;
        if (origin.getWorld() != null && point.getWorld() != null
                && !origin.getWorld().equals(point.getWorld())) {
            return false;
        }
        double dx = point.getX() - origin.getX();
        double dz = point.getZ() - origin.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist <= 1e-6) return true;                 // 与锥顶重合 = 在扇形里
        if (dist > radius) return false;

        double ax = dir.getX();
        double az = dir.getZ();
        double len = Math.sqrt(ax * ax + az * az);
        if (len <= 1e-6) return true;                  // 竖直朝向：没有水平方向，按整圆处理

        double cos = (dx * ax + dz * az) / (dist * len);
        cos = Math.max(-1.0, Math.min(1.0, cos));
        double halfAngle = Math.max(0.5, angleDeg) / 2.0;
        return Math.toDegrees(Math.acos(cos)) <= halfAngle + 1e-6;
    }
}
