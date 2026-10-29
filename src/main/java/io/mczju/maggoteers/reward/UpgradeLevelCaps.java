package io.mczju.maggoteers.reward;

import io.mczju.maggoteers.MaggoteersPlugin;

/** Resolves effective UPGRADE_LEVEL cap: option -> pool -> config default. */
public final class UpgradeLevelCaps {

    private static int defaultCap = 4;

    private UpgradeLevelCaps() {}

    public static void load(MaggoteersPlugin plugin) {
        defaultCap = Math.max(1, plugin.getConfig().getInt("rewards.upgrade_level_cap_default", 4));
    }

    /** Test hook (same package). */
    static void resetDefaultCap(int cap) {
        defaultCap = Math.max(1, cap);
    }

    public static int resolve(RewardPool pool, RewardOption opt) {
        if (opt != null && opt.upgradeMax() > 0) {
            return opt.upgradeMax();
        }
        if (pool != null && pool.upgradeLevelCap() > 0) {
            return pool.upgradeLevelCap();
        }
        return defaultCap;
    }
}