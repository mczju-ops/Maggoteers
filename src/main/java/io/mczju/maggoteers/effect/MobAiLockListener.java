package io.mczju.maggoteers.effect;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityRemoveEvent;

/** Restores AI before chunk unload / remove drops registry entry. */
public final class MobAiLockListener implements Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRemove(EntityRemoveEvent event) {
        MobAiLockRegistry.onEntityRemove(event.getEntity());
    }
}
