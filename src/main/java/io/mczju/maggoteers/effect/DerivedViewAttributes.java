package io.mczju.maggoteers.effect;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.LivingEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Attributes whose plugin-namespace modifiers are stripped/reapplied on resyncDerived.
 * Keys (not Attribute constants) so unit tests do not need Paper RegistryAccess.
 * Omitting a key used by ADD_ATTRIBUTE lets unique KEY_SEQ modifiers stack unbound
 * (e.g. infinite melee reach via entity_interaction_range).
 */
public final class DerivedViewAttributes {

    private DerivedViewAttributes() {}

    /** Minecraft attribute path keys under {@code minecraft:}. */
    public static final List<String> PLAYER_STRIP_KEYS = List.of(
            "max_health",
            "attack_damage",
            "movement_speed",
            "attack_speed",
            "knockback_resistance",
            "armor",
            "armor_toughness",
            "entity_interaction_range",
            "block_interaction_range"
    );

    public static void forEachPlayerStrip(Consumer<Attribute> action) {
        if (action == null) return;
        for (String key : PLAYER_STRIP_KEYS) {
            Attribute attr = Registry.ATTRIBUTE.get(NamespacedKey.minecraft(key));
            if (attr != null) action.accept(attr);
        }
    }

    /**
     * Remove plugin-namespace modifiers (optional key-path prefix filter for aura links).
     * When {@code keyPathPrefix} is null, only {@link AttributeModifierKeys#isManagedEffectPath}
     * keys are removed — never ItemCreator item modifiers sharing the plugin namespace.
     */
    public static void stripPluginModifiers(LivingEntity le, String pluginNamespace, String keyPathPrefix) {
        if (le == null || pluginNamespace == null || pluginNamespace.isBlank()) return;
        forEachPlayerStrip(attr -> {
            AttributeInstance inst = le.getAttribute(attr);
            if (inst == null) return;
            for (AttributeModifier m : new ArrayList<>(inst.getModifiers())) {
                if (!AttributeModifierKeys.matchesPluginNamespace(m, pluginNamespace)) continue;
                String path = m.getKey().value();
                if (keyPathPrefix != null) {
                    if (!path.startsWith(keyPathPrefix)) continue;
                } else if (!AttributeModifierKeys.isManagedEffectPath(path)) {
                    continue;
                }
                inst.removeModifier(m);
            }
        });
    }
}
