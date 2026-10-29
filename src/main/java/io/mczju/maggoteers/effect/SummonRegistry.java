package io.mczju.maggoteers.effect;

import io.mczju.maggoteers.MaggoteersPlugin;
import io.mczju.maggoteers.game.MaggoteersGame;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Projectile;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Per-game tracked summons for cleanup, friendly-fire, and loop suppression. */
public final class SummonRegistry {

    private static final Map<MaggoteersGame, List<SummonEntry>> BY_GAME = new IdentityHashMap<>();

    private SummonRegistry() {}

    public record SummonEntry(UUID entityId, UUID ownerId, SummonCleanup cleanup, BukkitTask expireTask,
                              boolean friendlyFire) {}

    public static void register(MaggoteersGame game, Entity entity, UUID ownerId,
                                SummonCleanup cleanup, int durationSec, boolean friendlyFire) {
        if (game == null || entity == null || ownerId == null) return;
        tagEntity(entity, ownerId, friendlyFire);
        BukkitTask task = null;
        if (cleanup == SummonCleanup.DURATION && durationSec > 0) {
            UUID id = entity.getUniqueId();
            task = Bukkit.getScheduler().runTaskLater(MaggoteersPlugin.getInstance(),
                    () -> removeEntity(game, id), durationSec * 20L);
        }
        BY_GAME.computeIfAbsent(game, g -> new ArrayList<>())
                .add(new SummonEntry(entity.getUniqueId(), ownerId, cleanup, task, friendlyFire));
    }

    public static void onWaveClear(MaggoteersGame game) {
        removeMatching(game, SummonCleanup.WAVE_CLEAR);
    }

    public static void onActEnter(MaggoteersGame game) {
        clearAll(game);
    }

    public static void clearAll(MaggoteersGame game) {
        removeMatching(game, null);
    }

    public static boolean isTrackedSummon(Entity entity) {
        if (entity == null) return false;
        var pdc = entity.getPersistentDataContainer();
        return pdc.has(ownerKey(), PersistentDataType.STRING);
    }

    public static boolean isTrackedProjectile(Entity damager) {
        if (!(damager instanceof Projectile)) return false;
        if (isTrackedSummon(damager)) return true;
        Projectile proj = (Projectile) damager;
        if (proj.getShooter() instanceof Entity shooter && isTrackedSummon(shooter)) {
            return true;
        }
        return false;
    }

    public static boolean allowsFriendlyFire(Entity entity) {
        if (entity == null) return true;
        Byte ff = entity.getPersistentDataContainer().get(ffKey(), PersistentDataType.BYTE);
        return ff != null && ff == 1;
    }

    public static UUID ownerOf(Entity entity) {
        if (entity == null) return null;
        String raw = entity.getPersistentDataContainer().get(ownerKey(), PersistentDataType.STRING);
        if (raw == null || raw.isBlank()) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static void tagEntity(Entity entity, UUID ownerId, boolean friendlyFire) {
        var pdc = entity.getPersistentDataContainer();
        pdc.set(ownerKey(), PersistentDataType.STRING, ownerId.toString());
        pdc.set(ffKey(), PersistentDataType.BYTE, (byte) (friendlyFire ? 1 : 0));
    }

    private static NamespacedKey ownerKey() {
        return new NamespacedKey(MaggoteersPlugin.getInstance(), "summon_owner");
    }

    private static NamespacedKey ffKey() {
        return new NamespacedKey(MaggoteersPlugin.getInstance(), "summon_ff");
    }

    private static void removeMatching(MaggoteersGame game, SummonCleanup cleanupKind) {
        List<SummonEntry> entries = BY_GAME.get(game);
        if (entries == null || entries.isEmpty()) return;
        Iterator<SummonEntry> it = entries.iterator();
        while (it.hasNext()) {
            SummonEntry e = it.next();
            if (cleanupKind != null && e.cleanup() != cleanupKind) continue;
            if (e.expireTask() != null) e.expireTask().cancel();
            Entity ent = Bukkit.getEntity(e.entityId());
            if (ent != null && ent.isValid()) ent.remove();
            it.remove();
        }
        if (entries.isEmpty()) BY_GAME.remove(game);
    }

    private static void removeEntity(MaggoteersGame game, UUID entityId) {
        List<SummonEntry> entries = BY_GAME.get(game);
        if (entries == null) return;
        Iterator<SummonEntry> it = entries.iterator();
        while (it.hasNext()) {
            SummonEntry e = it.next();
            if (!e.entityId().equals(entityId)) continue;
            if (e.expireTask() != null) e.expireTask().cancel();
            Entity ent = Bukkit.getEntity(entityId);
            if (ent != null && ent.isValid()) ent.remove();
            it.remove();
            break;
        }
        if (entries.isEmpty()) BY_GAME.remove(game);
    }
}