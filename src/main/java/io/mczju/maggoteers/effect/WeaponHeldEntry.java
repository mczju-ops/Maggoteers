package io.mczju.maggoteers.effect;

/** Parsed {@code held_effects[]} row for one weapon item id. */
public record WeaponHeldEntry(
        Effect effect,
        EffectContext params,
        Trigger fireTrigger,
        Stack stack
) {}
