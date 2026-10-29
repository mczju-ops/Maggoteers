package io.mczju.maggoteers.item.fx;

import io.mczju.maggoteers.MaggoteersPlugin;
import io.mczju.maggoteers.util.ParticleEffects;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

/** 按 {@link MagicUseFx} 播放声效与粒子预设。 */
public final class MagicFxService {
    private MagicFxService() {}

    public static void play(Player player, MagicUseFx fx) {
        play(player, fx, false);
    }

    /** @param allowAreaRing 是否允许播放 THICK_RING / RIPPLE_RINGS 等范围标识环 */
    public static void play(Player player, MagicUseFx fx, boolean allowAreaRing) {
        if (fx == null || player == null) return;
        Location at = player.getLocation();
        if (fx.sound() != null) {
            player.playSound(at, fx.sound(), fx.soundVolume(), fx.soundPitch());
        }
        if (fx.preset() == MagicFxPreset.NONE) {
            return;
        }
        if (isAreaRingPreset(fx.preset()) && !allowAreaRing) {
            return;
        }
        MagicFxPresets.play(player, fx);
    }

    static boolean isAreaRingPreset(MagicFxPreset preset) {
        return preset == MagicFxPreset.THICK_RING
                || preset == MagicFxPreset.RIPPLE_RINGS
                || preset == MagicFxPreset.SMOOTH_EXPAND_RING
                || preset == MagicFxPreset.SPIRAL_RADIUS;
    }

    public static void playOnEntity(Entity entity, MagicUseFx fx) {
        if (fx == null || entity == null) {
            return;
        }
        if (entity instanceof Player p) {
            play(p, fx);
            return;
        }
        Location at = entity.getLocation();
        if (fx.sound() != null && at.getWorld() != null) {
            at.getWorld().playSound(at, fx.sound(), fx.soundVolume(), fx.soundPitch());
        }
        if (fx.preset() == MagicFxPreset.NONE) {
            return;
        }
        MagicFxPresets.dotAbove(at, fx);
    }
}
