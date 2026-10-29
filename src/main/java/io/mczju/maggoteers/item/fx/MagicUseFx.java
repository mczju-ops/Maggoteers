package io.mczju.maggoteers.item.fx;

import org.bukkit.Particle;
import org.bukkit.Sound;

/** 单次魔法释放的可配置声效 + 粒子参数（合并 defaults 与 per-weapon 覆盖）。 */
public record MagicUseFx(
        Sound sound,
        float soundVolume,
        float soundPitch,
        MagicFxPreset preset,
        Particle particle,
        double radius,
        double rayLength,
        int density,
        int rippleRings,
        int expandSteps,
        int spiralTicks,
        int durationTicks
) {
    public static MagicUseFx defaults() {
        return new MagicUseFx(
                Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.8f, 1.1f,
                MagicFxPreset.THICK_RING, Particle.FLAME,
                4.0, 10.0, 64,
                4, 28, 18, 0);
    }
}
