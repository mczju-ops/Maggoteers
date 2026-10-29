package io.mczju.maggoteers.wave;

/** 不可变三维坐标（绝对或相对）。刻意不持有 {@link org.bukkit.World}，便于异步线程预生成（E8）。 */
public record Vec3(double x, double y, double z) {
    /** 加法：常用于 绝对 = 层原点 + 相对。 */
    public Vec3 add(Vec3 o) { return new Vec3(x + o.x, y + o.y, z + o.z); }
}
