package io.mczju.maggoteers.effect;

import io.mczju.maggoteers.game.MaggoteersGame;
import io.mczju.maggoteers.state.PlayerStateManager;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

/** Cancels registry-summon damage vs players/allies when friendly_fire is false. */
public final class SummonFriendlyFireListener implements Listener {

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFriendlyFire(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        MaggoteersGame game = PlayerStateManager.gameOf(victim);
        if (game == null || !PlayerStateManager.isRunParticipant(game, victim)) return;

        Entity damager = resolveDamager(e.getDamager());
        if (damager == null || !SummonRegistry.isTrackedSummon(damager)) return;
        if (SummonRegistry.allowsFriendlyFire(damager)) return;
        e.setCancelled(true);
    }

    private static Entity resolveDamager(Entity raw) {
        if (SummonRegistry.isTrackedSummon(raw)) return raw;
        if (raw instanceof Projectile proj && proj.getShooter() instanceof Entity shooter
                && SummonRegistry.isTrackedSummon(shooter)) {
            return shooter;
        }
        return null;
    }

}