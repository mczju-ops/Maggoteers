package io.mczju.maggoteers.listener;

import org.bukkit.event.entity.EntityPotionEffectEvent;

/** Pure policy for EntityPotionEffectEvent cancel decisions. */
public final class PotionImmunityEventPolicy {

    private PotionImmunityEventPolicy() {}

    public static boolean shouldCancel(EntityPotionEffectEvent.Action action) {
        if (action == null) {
            return false;
        }
        return action == EntityPotionEffectEvent.Action.ADDED
                || action == EntityPotionEffectEvent.Action.CHANGED;
    }
}