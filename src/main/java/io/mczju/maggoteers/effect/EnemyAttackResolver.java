package io.mczju.maggoteers.effect;

import io.mczju.maggoteers.game.MaggoteersGame;
import io.mczju.maggoteers.state.PlayerStateManager;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import java.util.Optional;

/** Resolves enemy attackers for ON_DAMAGE_TAKEN (melee mob or enemy projectile shooter). */
public final class EnemyAttackResolver {

    private EnemyAttackResolver() {}

    /** True when entity is a non-ally run participant (mobs always count as enemies). */
    static boolean isEnemyParticipant(MaggoteersGame game, LivingEntity entity) {
        if (entity == null) return false;
        if (entity instanceof Player pl) {
            return !PlayerStateManager.isRunParticipant(game, pl);
        }
        return true;
    }

    public static Optional<LivingEntity> resolveAttacker(EntityDamageByEntityEvent event, MaggoteersGame game) {
        if (event == null || game == null) return Optional.empty();
        LivingEntity attacker = livingDamager(event.getDamager());
        if (attacker == null) return Optional.empty();
        return isEnemyParticipant(game, attacker) ? Optional.of(attacker) : Optional.empty();
    }

    private static LivingEntity livingDamager(Entity damager) {
        if (damager instanceof Projectile proj) {
            if (proj.getShooter() instanceof LivingEntity le) {
                return le;
            }
            return null;
        }
        if (damager instanceof LivingEntity le) {
            return le;
        }
        return null;
    }
}
