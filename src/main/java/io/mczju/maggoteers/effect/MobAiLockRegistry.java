package io.mczju.maggoteers.effect;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks temporary setAI(false) locks on hostile mobs.
 * Longer remaining duration wins; first restoreAi is kept.
 * Unload path restores AI while entity is still valid before drop.
 *
 * <p>Per-game scoping (I4): {@link #lock(LivingEntity, int, Object)} records the owning
 * game token; {@link #restoreAll(Object)} only restores locks belonging to that game.
 * The no-arg {@link #restoreAll()} clears everything (for shutdown).</p>
 */
public final class MobAiLockRegistry {

    public record Entry(int expireAtTick, boolean restoreAi) {}

    private static final Map<UUID, Entry> LOCKS = new ConcurrentHashMap<>();
    /** Per-entity game ownership for scoped restore (I4). */
    private static final Map<UUID, Object> ENTITY_GAME = new ConcurrentHashMap<>();
    private static BukkitTask scanTask;

    private MobAiLockRegistry() {}

    public static void lock(LivingEntity entity, int durationTicks) {
        lock(entity, durationTicks, null);
    }

    /** Lock with game ownership for per-game restore (I4). */
    public static void lock(LivingEntity entity, int durationTicks, Object game) {
        if (entity == null || !entity.isValid() || entity.isDead() || durationTicks <= 0) {
            return;
        }
        int now = Bukkit.getCurrentTick();
        UUID id = entity.getUniqueId();
        Entry existing = LOCKS.get(id);
        if (existing == null) {
            boolean restore = entity.hasAI();
            entity.setAI(false);
            LOCKS.put(id, new Entry(now + durationTicks, restore));
        } else {
            int newExpire = now + durationTicks;
            if (newExpire > existing.expireAtTick()) {
                LOCKS.put(id, new Entry(newExpire, existing.restoreAi()));
            }
            entity.setAI(false);
        }
        if (game != null) {
            ENTITY_GAME.put(id, game);
        }
    }

    /** Package/test: record lock without touching Bukkit entity. */
    static void noteLock(UUID id, int nowTick, int durationTicks, boolean restoreAi) {
        noteLock(id, nowTick, durationTicks, restoreAi, null);
    }

    static void noteLock(UUID id, int nowTick, int durationTicks, boolean restoreAi, Object game) {
        if (id == null || durationTicks <= 0) return;
        Entry existing = LOCKS.get(id);
        int newExpire = nowTick + durationTicks;
        if (existing == null) {
            LOCKS.put(id, new Entry(newExpire, restoreAi));
        } else if (newExpire > existing.expireAtTick()) {
            LOCKS.put(id, new Entry(newExpire, existing.restoreAi()));
        }
        if (game != null) {
            ENTITY_GAME.put(id, game);
        }
    }

    /** Package/test: if expired, remove and return entry. */
    static Optional<Entry> pollExpired(UUID id, int nowTick) {
        Entry e = LOCKS.get(id);
        if (e == null) return Optional.empty();
        if (nowTick < e.expireAtTick()) return Optional.empty();
        LOCKS.remove(id);
        ENTITY_GAME.remove(id);
        return Optional.of(e);
    }

    /**
     * Unload/remove bookkeeping.
     * @param entityStillValid if true and entry exists → return restoreAi for caller to apply
     *                         if false → drop silently (no restore value)
     */
    static Optional<Boolean> takeForUnloadRestore(UUID id, boolean entityStillValid) {
        Entry e = LOCKS.remove(id);
        ENTITY_GAME.remove(id);
        if (e == null) return Optional.empty();
        if (!entityStillValid) return Optional.empty();
        return Optional.of(e.restoreAi());
    }

    static Optional<Entry> getForTests(UUID id) {
        return Optional.ofNullable(LOCKS.get(id));
    }

    static void clearForTests() {
        LOCKS.clear();
        ENTITY_GAME.clear();
        stop();
    }

    public static void tick() {
        int now = Bukkit.getCurrentTick();
        Iterator<Map.Entry<UUID, Entry>> it = LOCKS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Entry> mapEntry = it.next();
            UUID id = mapEntry.getKey();
            Entry e = mapEntry.getValue();
            Entity entity = Bukkit.getEntity(id);
            if (!(entity instanceof LivingEntity le) || !le.isValid() || le.isDead()) {
                it.remove();
                ENTITY_GAME.remove(id);
                continue;
            }
            if (now >= e.expireAtTick()) {
                le.setAI(e.restoreAi());
                it.remove();
                ENTITY_GAME.remove(id);
            }
        }
    }

    public static void onEntityRemove(Entity entity) {
        if (entity == null) return;
        UUID id = entity.getUniqueId();
        if (!LOCKS.containsKey(id)) return;
        boolean stillValid = entity.isValid();
        Optional<Boolean> restore = takeForUnloadRestore(id, stillValid);
        if (restore.isPresent() && entity instanceof LivingEntity le && le.isValid()) {
            le.setAI(restore.get());
        }
    }

    /** Restore AI for all locked entities (shutdown). */
    public static void restoreAll() {
        for (Map.Entry<UUID, Entry> mapEntry : LOCKS.entrySet()) {
            Entity entity = Bukkit.getEntity(mapEntry.getKey());
            if (entity instanceof LivingEntity le && le.isValid()) {
                le.setAI(mapEntry.getValue().restoreAi());
            }
        }
        LOCKS.clear();
        ENTITY_GAME.clear();
    }

    /** Restore AI only for entities locked by the given game (I4). */
    public static void restoreAll(Object game) {
        if (game == null) { restoreAll(); return; }
        Iterator<Map.Entry<UUID, Entry>> it = LOCKS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Entry> mapEntry = it.next();
            UUID id = mapEntry.getKey();
            if (!game.equals(ENTITY_GAME.get(id))) continue;
            Entity entity = Bukkit.getEntity(id);
            if (entity instanceof LivingEntity le && le.isValid()) {
                le.setAI(mapEntry.getValue().restoreAi());
            }
            it.remove();
            ENTITY_GAME.remove(id);
        }
    }

    public static void start(Plugin plugin) {
        stop();
        if (plugin == null) return;
        scanTask = Bukkit.getScheduler().runTaskTimer(plugin, MobAiLockRegistry::tick, 1L, 1L);
    }

    public static void stop() {
        if (scanTask != null) {
            scanTask.cancel();
            scanTask = null;
        }
    }
}
