package io.mczju.maggoteers.item.fx;

import io.mczju.maggoteers.effect.EffectContext;
import io.mczju.maggoteers.effect.EffectKey;
import io.mczju.maggoteers.util.GameRegistries;
import org.bukkit.Particle;
import org.bukkit.Sound;

import java.util.Locale;

/** 从 YAML / AURA {@code carrier_fx}、{@code mark_fx} 段构建 {@link MagicUseFx}。 */
public final class MagicFxBuilder {
    private static final EffectKey<String> K_PRESET = new EffectKey<>("preset");
    private static final EffectKey<String> K_PARTICLE = new EffectKey<>("particle");
    private static final EffectKey<String> K_SOUND = new EffectKey<>("sound");
    private static final EffectKey<Double> K_RADIUS = new EffectKey<>("radius");
    private static final EffectKey<Double> K_RAY = new EffectKey<>("ray_length");
    private static final EffectKey<Double> K_DENSITY = new EffectKey<>("density");
    private static final EffectKey<Double> K_VOL = new EffectKey<>("sound_volume");
    private static final EffectKey<Double> K_PITCH = new EffectKey<>("sound_pitch");
    private static final EffectKey<Double> K_RIPPLE = new EffectKey<>("ripple_rings");
    private static final EffectKey<Double> K_EXPAND = new EffectKey<>("expand_steps");
    private static final EffectKey<Double> K_SPIRAL = new EffectKey<>("spiral_ticks");
    private static final EffectKey<Double> K_DURATION = new EffectKey<>("duration_ticks");

    private MagicFxBuilder() {}

    public static void putFxEntry(EffectContext ctx, String key, Object value) {
        switch (key) {
            case "preset" -> ctx.put(K_PRESET, String.valueOf(value));
            case "particle" -> ctx.put(K_PARTICLE, String.valueOf(value));
            case "sound" -> ctx.put(K_SOUND, String.valueOf(value));
            case "radius" -> ctx.put(K_RADIUS, num(value));
            case "ray_length" -> ctx.put(K_RAY, num(value));
            case "density" -> ctx.put(K_DENSITY, num(value));
            case "sound_volume" -> ctx.put(K_VOL, num(value));
            case "sound_pitch" -> ctx.put(K_PITCH, num(value));
            case "ripple_rings" -> ctx.put(K_RIPPLE, num(value));
            case "expand_steps" -> ctx.put(K_EXPAND, num(value));
            case "spiral_ticks" -> ctx.put(K_SPIRAL, num(value));
            case "duration_ticks" -> ctx.put(K_DURATION, num(value));
            default -> { }
        }
    }

    public static MagicUseFx fromContext(EffectContext ctx, MagicUseFx fallback) {
        if (ctx == null) return fallback;
        Sound sound = fallback.sound();
        String soundS = ctx.get(K_SOUND);
        if (soundS != null) {
            Sound s = GameRegistries.sound(soundS);
            if (s != null) sound = s;
        }
        MagicFxPreset preset = fallback.preset();
        String presetRaw = ctx.get(K_PRESET);
        if (presetRaw != null) {
            try {
                preset = MagicFxPreset.valueOf(presetRaw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) { }
        }
        Particle particle = fallback.particle();
        String particleRaw = ctx.get(K_PARTICLE);
        if (particleRaw != null) {
            Particle p = GameRegistries.particle(particleRaw);
            if (p != null) particle = p;
        }
        return new MagicUseFx(
                sound,
                (float) ctx.getOrDefault(K_VOL, (double) fallback.soundVolume()).floatValue(),
                (float) ctx.getOrDefault(K_PITCH, (double) fallback.soundPitch()).floatValue(),
                preset, particle,
                ctx.getOrDefault(K_RADIUS, fallback.radius()),
                ctx.getOrDefault(K_RAY, fallback.rayLength()),
                ctx.getOrDefault(K_DENSITY, (double) fallback.density()).intValue(),
                ctx.getOrDefault(K_RIPPLE, (double) fallback.rippleRings()).intValue(),
                ctx.getOrDefault(K_EXPAND, (double) fallback.expandSteps()).intValue(),
                ctx.getOrDefault(K_SPIRAL, (double) fallback.spiralTicks()).intValue(),
                ctx.getOrDefault(K_DURATION, (double) fallback.durationTicks()).intValue());
    }

    private static double num(Object v) {
        return v instanceof Number n ? n.doubleValue() : 0.0;
    }
}
