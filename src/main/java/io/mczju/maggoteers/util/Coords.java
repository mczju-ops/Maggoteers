package io.mczju.maggoteers.util;

import io.mczju.maggoteers.wave.Vec3;

/** 坐标计算纯函数（与 Bukkit 解耦，便于单测）。 */
public final class Coords {
    private Coords() {}

    /** 绝对坐标 = 层原点 + 图内相对坐标（铁律①：绝对坐标永不入配置）。 */
    public static Vec3 resolve(Vec3 origin, Vec3 relative) {
        return origin.add(relative);
    }

    /**
     * 整格坐标 → **方块中心**（X/Z 各 +0.5；已经是 .5 的坐标原样返回）。
     *
     * <p>{@code points.yml} 里的点位写的是方块坐标（整数），实体必须生成在方块中心，
     * 否则会站在方块的角上、看起来整体偏半格（2026-10-28 实测"Boss 不在标记正上方"）。
     * Y 不动：点位 y 就是脚底高度。
     */
    public static double blockCenter(double v) {
        return v == Math.rint(v) ? v + 0.5 : v;
    }

    /** 点位 → 方块中心（只动 X/Z）。 */
    public static Vec3 blockCenterXZ(Vec3 p) {
        return new Vec3(blockCenter(p.x()), p.y(), blockCenter(p.z()));
    }
}
