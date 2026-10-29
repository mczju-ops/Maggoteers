package io.mczju.maggoteers.effect;

import io.mczju.maggoteers.game.MaggoteersGame;
import io.mczju.maggoteers.state.PlayerState;
import io.mczju.maggoteers.state.PlayerStateManager;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.potion.PotionEffectType;

/** Shared run-long potion immunity queries (rewards {@code immunity: true}). */
public final class PotionImmunity {

    private PotionImmunity() {}

    /**
     * Strict immunity legality (spec sec3.3): ADD_POTION + flag + no trigger/expiry/recurring + non-instant.
     */
    public static boolean matchesImmunityEffect(PlayerEffect e, PotionEffectType type) {
        if (e == null || type == null) {
            return false;
        }
        if (e.effect() != Effect.ADD_POTION) {
            return false;
        }
        if (!Boolean.TRUE.equals(e.params().get(EffectKeys.IMMUNITY))) {
            return false;
        }
        if (e.fireTrigger() != null) {
            return false;
        }
        if (e.expiryTrigger() != null) {
            return false;
        }
        if (e.recurringIntervalSec() > 0) {
            return false;
        }
        PotionEffectType have = e.params().get(EffectKeys.POTION);
        if (have == null || !have.equals(type)) {
            return false;
        }
        if (type.isInstant()) {
            return false;
        }
        return true;
    }

    public static boolean isImmune(Player player, PotionEffectType type) {
        if (player == null || type == null) {
            return false;
        }
        PlayerState st = stateOf(player);
        if (st == null) {
            return false;
        }
        for (PlayerEffect e : st.effects()) {
            if (matchesImmunityEffect(e, type)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isImmuneToCause(Player player, EntityDamageEvent.DamageCause cause) {
        if (player == null || cause == null) {
            return false;
        }
        PotionEffectType type = switch (cause) {
            case POISON -> PotionEffectType.POISON;
            case WITHER -> PotionEffectType.WITHER;
            default -> null;
        };
        return type != null && isImmune(player, type);
    }

    private static PlayerState stateOf(Player player) {
        MaggoteersGame mg = PlayerStateManager.gameOf(player);
        if (mg == null) return null;
        return PlayerStateManager.get(mg, player.getUniqueId());
    }
}
