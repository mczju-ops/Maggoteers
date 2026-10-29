package io.mczju.maggoteers.mob;

import io.mczju.maggoteers.MaggoteersPlugin;
import io.mczju.maggoteers.config.ConfigParse;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.persistence.PersistentDataType;

import java.util.Objects;
import java.util.Optional;

/**
 * Locks Maggoteers-spawned mob display names: configured MiniMessage name,
 * or clear custom name (suppress stray nametags / 旧 IM nametag 遗留).
 */
public final class MobDisplayNames {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private MobDisplayNames() {}

    static NamespacedKey nameLockKey() {
        return new NamespacedKey(MaggoteersPlugin.getInstance(), "name_lock");
    }

    static NamespacedKey displayNameKey() {
        return new NamespacedKey(MaggoteersPlugin.getInstance(), "display_name");
    }

    /** Trim; empty → null. */
    public static String normalizeConfiguredName(String raw) {
        return ConfigParse.trimToNull(raw);
    }

    public static Component resolveTitle(String lockedMmOrNull, EntityType type) {
        if (lockedMmOrNull != null) return MM.deserialize(lockedMmOrNull);
        return Component.text(type.name());
    }

    public static void lock(LivingEntity le, String nameOrNull) {
        if (le == null) return;
        String name = normalizeConfiguredName(nameOrNull);
        var pdc = le.getPersistentDataContainer();
        pdc.set(nameLockKey(), PersistentDataType.BYTE, (byte) 1);
        if (name != null) {
            pdc.set(displayNameKey(), PersistentDataType.STRING, name);
            le.customName(MM.deserialize(name));
            le.setCustomNameVisible(true);
        } else {
            pdc.remove(displayNameKey());
            le.customName(null);
            le.setCustomNameVisible(false);
        }
    }

    public static boolean hasNameLock(LivingEntity le) {
        if (le == null) return false;
        Byte v = le.getPersistentDataContainer().get(nameLockKey(), PersistentDataType.BYTE);
        return v != null && v == (byte) 1;
    }

    public static Optional<String> lockedDisplayName(LivingEntity le) {
        if (le == null || !hasNameLock(le)) return Optional.empty();
        String raw = le.getPersistentDataContainer().get(displayNameKey(), PersistentDataType.STRING);
        return Optional.ofNullable(normalizeConfiguredName(raw));
    }

    public static void reassertIfNeeded(LivingEntity le) {
        if (le == null || !hasNameLock(le)) return;
        Optional<String> intended = lockedDisplayName(le);
        Component current = le.customName();
        if (intended.isPresent()) {
            Component want = MM.deserialize(intended.get());
            if (!componentsEqual(current, want) || !le.isCustomNameVisible()) {
                lock(le, intended.get());
            }
        } else {
            if (current != null || le.isCustomNameVisible()) {
                lock(le, null);
            }
        }
    }

    private static boolean componentsEqual(Component a, Component b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return Objects.equals(PLAIN.serialize(a), PLAIN.serialize(b));
    }
}
