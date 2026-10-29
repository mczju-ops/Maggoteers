package io.mczju.maggoteers.effect;

import io.mczju.maggoteers.game.MaggoteersGame;
import org.bukkit.Bukkit;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Same-tick re-entry guard for magic/summon damage owned by a player.
 * Suppresses both {@code ON_DAMAGE_DEALT} and {@code ON_DAMAGE_TAKEN} so
 * Guardian/Elder Guardian spike reflect cannot recurse with thorns STAT effects.
 *
 * <p>Uses reference counting so nested {@link #run} calls (e.g. outer AOE kill
 * → inner ON_KILL → inner DAMAGE_AREA) don't prematurely clear the mark.</p>
 */
public final class MagicDamageContext {
    private static final Map<String, Integer> REFCOUNT = new HashMap<>();

    private MagicDamageContext() {}

    private static String key(Object game, UUID source, int tick) {
        return System.identityHashCode(game) + ":" + source + ":" + tick;
    }

    public static void run(MaggoteersGame game, UUID source, Runnable action) {
        if (game == null || source == null) {
            action.run();
            return;
        }
        runMarked(game, source, Bukkit.getCurrentTick(), action);
    }

    /** Package-visible for tests — same mark semantics without Bukkit tick. */
    static void runMarked(Object game, UUID source, int tick, Runnable action) {
        String k = key(game, source, tick);
        REFCOUNT.merge(k, 1, Integer::sum);
        try {
            action.run();
        } finally {
            int count = REFCOUNT.getOrDefault(k, 0);
            if (count <= 1) {
                REFCOUNT.remove(k);
            } else {
                REFCOUNT.put(k, count - 1);
            }
        }
    }

    /** Package-visible for tests. */
    static boolean isMarked(Object game, UUID source, int tick) {
        if (game == null || source == null) return false;
        return REFCOUNT.getOrDefault(key(game, source, tick), 0) > 0;
    }

    /** True while this player's magic damage is applying this tick. */
    public static boolean shouldSuppressCombatTriggers(MaggoteersGame game, UUID source) {
        if (game == null || source == null) return false;
        return isMarked(game, source, Bukkit.getCurrentTick());
    }

    public static boolean shouldSuppressOnDamageDealt(MaggoteersGame game, UUID source) {
        return shouldSuppressCombatTriggers(game, source);
    }

    public static boolean shouldSuppressOnDamageTaken(MaggoteersGame game, UUID source) {
        return shouldSuppressCombatTriggers(game, source);
    }
}
