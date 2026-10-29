package io.mczju.maggoteers.world;

/** 世界命名纯函数（与 Bukkit 解耦，便于单测）。 */
public final class WorldNames {
    private WorldNames() {}

    /** 由种子推导本局世界名：maggoteers_<16进制>。 */
    public static String forSeed(long seed) {
        return "maggoteers_" + Long.toHexString(seed);
    }
}
