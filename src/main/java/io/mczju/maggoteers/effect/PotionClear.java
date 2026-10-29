package io.mczju.maggoteers.effect;

import org.bukkit.entity.LivingEntity;
import org.bukkit.potion.PotionEffectType;

import java.util.List;

/** One-shot potion strip helper. */
public final class PotionClear {

    private PotionClear() {}

    public static void apply(LivingEntity target, List<PotionEffectType> types) {
        if (target == null || types == null || types.isEmpty()) {
            return;
        }
        if (!target.isValid() || target.isDead()) {
            return;
        }
        for (PotionEffectType type : types) {
            if (type != null) {
                target.removePotionEffect(type);
            }
        }
    }
}