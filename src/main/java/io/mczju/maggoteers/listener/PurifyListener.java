package io.mczju.maggoteers.listener;

import io.mczju.maggoteers.effect.PotionImmunity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.potion.PotionEffectType;

/**
 * Run-long potion immunity: cancel ADDED/CHANGED for immune types; cancel POISON/WITHER damage.
 */
public final class PurifyListener implements Listener {

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPotionEffect(EntityPotionEffectEvent e) {
        if (!(e.getEntity() instanceof Player p)) {
            return;
        }
        if (!PotionImmunityEventPolicy.shouldCancel(e.getAction())) {
            return;
        }
        PotionEffectType type = e.getModifiedType();
        if (type == null) {
            return;
        }
        if (PotionImmunity.isImmune(p, type)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) {
            return;
        }
        if (PotionImmunity.isImmuneToCause(p, e.getCause())) {
            e.setCancelled(true);
        }
    }
}