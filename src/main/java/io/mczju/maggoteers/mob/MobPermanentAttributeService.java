package io.mczju.maggoteers.mob;

import io.mczju.maggoteers.MaggoteersPlugin;
import io.mczju.maggoteers.effect.EffectContext;
import io.mczju.maggoteers.effect.EffectKeys;
import io.mczju.maggoteers.util.GameRegistries;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.EquipmentSlotGroup;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class MobPermanentAttributeService {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final Map<UUID, List<NamespacedKey>> ACTIVE = new ConcurrentHashMap<>();

    private MobPermanentAttributeService() {}

    public static void apply(LivingEntity le, EffectContext p) {
        if (le == null || p == null) return;
        String attrName = p.get(EffectKeys.ATTR_NAME);
        if (attrName == null || attrName.isBlank()) return;
        Attribute attr = GameRegistries.attribute(attrName);
        if (attr == null) return;
        AttributeInstance inst = le.getAttribute(attr);
        if (inst == null) return;
        double value = p.getOrDefault(EffectKeys.VALUE, 0.0);
        String opRaw = p.getOrDefault(EffectKeys.OP, "FLAT");
        NamespacedKey key = new NamespacedKey(MaggoteersPlugin.getInstance(),
                "mperm_" + attrName.toLowerCase(Locale.ROOT) + "_" + SEQ.incrementAndGet());
        inst.addModifier(new AttributeModifier(key, value, operation(opRaw), EquipmentSlotGroup.ANY));
        ACTIVE.computeIfAbsent(le.getUniqueId(), u -> new ArrayList<>()).add(key);
        if (attr == Attribute.MAX_HEALTH && value > 0) {
            le.setHealth(Math.min(le.getMaxHealth(), le.getHealth() + value));
        }
    }

    public static void clearAll(UUID uuid) {
        List<NamespacedKey> list = ACTIVE.remove(uuid);
        if (list == null || list.isEmpty()) return;
        Entity raw = Bukkit.getEntity(uuid);
        if (!(raw instanceof LivingEntity le)) return;
        for (NamespacedKey key : list) {
            for (Attribute attr : Attribute.values()) {
                AttributeInstance ai = le.getAttribute(attr);
                if (ai != null) ai.removeModifier(key);
            }
        }
    }

    private static AttributeModifier.Operation operation(String raw) {
        if (raw == null) return AttributeModifier.Operation.ADD_NUMBER;
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "PERCENT" -> AttributeModifier.Operation.ADD_SCALAR;
            case "MULTIPLY" -> AttributeModifier.Operation.MULTIPLY_SCALAR_1;
            default -> AttributeModifier.Operation.ADD_NUMBER;
        };
    }
}
