package io.mczju.maggoteers.effect;

import org.bukkit.potion.PotionEffectType;

/** Parsed potion entry for BUFF_AREA. */
public record BuffPotionSpec(PotionEffectType type, int amp, int durationTicks) {}
