package io.mczju.maggoteers.effect;

import net.kyori.adventure.key.Key;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Paper 26.2：{@link AttributeModifier#getKey()} 为 Adventure {@link Key}，与 {@link NamespacedKey} 比较需统一。
 * <p>效果 id 常含 {@code :}（如 {@code ability:maggoteers:xxx}、{@code held:maggoteers:xxx:0}），
 * 不能直接写入 NamespacedKey path，须先 {@link #sanitizeKeyPath(String)}。
 */
public final class AttributeModifierKeys {

    /** Prefix for PlayerState-derived ADD_ATTRIBUTE modifiers (not item / aura keys). */
    public static final String EFFECT_PATH_PREFIX = "effect/";

    private AttributeModifierKeys() {}

    /**
     * NamespacedKey path 仅允许 {@code [a-z0-9_-./]}；把其余字符（含 {@code :}）换成 {@code _}。
     */
    public static String sanitizeKeyPath(String raw) {
        if (raw == null || raw.isBlank()) return "unnamed";
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '-' || c == '.' || c == '/') {
                sb.append(c);
            } else if (c >= 'A' && c <= 'Z') {
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append('_');
            }
        }
        String out = sb.toString();
        return out.isEmpty() ? "unnamed" : (out.length() > 256 ? out.substring(0, 256) : out);
    }

    /**
     * True only for EffectService-managed derived modifiers.
     * ItemCreator item keys (e.g. {@code herafinger_attack_damage}) and aura keys
     * ({@code aura/...}) must NOT match — otherwise {@code resyncDerived} permanently
     * strips held-weapon attack damage down to fist-level (~1–2 HP hits).
     */
    public static boolean isManagedEffectPath(String keyPath) {
        return keyPath != null && keyPath.startsWith(EFFECT_PATH_PREFIX);
    }

    public static NamespacedKey pluginKey(org.bukkit.plugin.Plugin plugin, String rawPath) {
        return new NamespacedKey(plugin, sanitizeKeyPath(rawPath));
    }

    /** Effect-derived modifier key: {@code effect/<sanitizedId>}. */
    public static NamespacedKey effectKey(org.bukkit.plugin.Plugin plugin, String rawEffectId) {
        return pluginKey(plugin, EFFECT_PATH_PREFIX + sanitizeKeyPath(rawEffectId));
    }

    /**
     * Effect {@code op} → {@link AttributeModifier.Operation}。
     * <ul>
     *   <li>{@code PERCENT}：百分比<b>加算</b>（{@link AttributeModifier.Operation#ADD_SCALAR}，
     *       多个百分比先求和再乘 base）。</li>
     *   <li>{@code MULTIPLY}：<b>最终乘算</b>（{@link AttributeModifier.Operation#MULTIPLY_SCALAR_1}）。
     *       ⚠️ Mojang 1.20.5 把旧的 4 个 Operation 合并为 3 个，旧 {@code MULTIPLY_SCALAR_2}（最终乘算）
     *       并入 {@code MULTIPLY_SCALAR_1}（NMS 映射 {@code ADD_MULTIPLIED_TOTAL}，ordinal 索引对齐）；
     *       每个 modifier 对最终总值独立 ×(1+value)。真实属性与虚拟属性（法伤 MAGIC_DAMAGE，
     *       {@link PlayerCombatStats} 管线）语义一致。</li>
     *   <li>其余（含 {@code FLAT}、null）：绝对值加算（{@link AttributeModifier.Operation#ADD_NUMBER}）。</li>
     * </ul>
     */
    public static AttributeModifier.Operation attributeOperation(String op) {
        if (op == null) return AttributeModifier.Operation.ADD_NUMBER;
        return switch (op.trim().toUpperCase(Locale.ROOT)) {
            case "PERCENT" -> AttributeModifier.Operation.ADD_SCALAR;
            case "MULTIPLY" -> AttributeModifier.Operation.MULTIPLY_SCALAR_1;   // 最终乘算（1.20.5 合并；NMS=ADD_MULTIPLIED_TOTAL）
            default -> AttributeModifier.Operation.ADD_NUMBER;
        };
    }

    /** op 是否为合法的属性操作符（{@code FLAT} / {@code PERCENT} / {@code MULTIPLY}）。 */
    public static boolean isValidAttributeOp(String op) {
        if (op == null) return false;
        String u = op.trim().toUpperCase(Locale.ROOT);
        return u.equals("FLAT") || u.equals("PERCENT") || u.equals("MULTIPLY");
    }

    public static boolean matches(AttributeModifier modifier, NamespacedKey expected) {
        if (modifier == null || expected == null) return false;
        Key k = modifier.getKey();
        return expected.getNamespace().equals(k.namespace()) && expected.getKey().equals(k.value());
    }

    public static boolean matchesPluginNamespace(AttributeModifier modifier, String pluginNamespace) {
        return modifier != null && modifier.getKey().namespace().equals(pluginNamespace);
    }

    public static void removeIfMatches(AttributeInstance inst, NamespacedKey key) {
        if (inst == null || key == null) return;
        for (AttributeModifier m : new ArrayList<>(inst.getModifiers())) {
            if (matches(m, key)) inst.removeModifier(m);
        }
    }

    public static void replace(AttributeInstance inst, NamespacedKey key, double value,
                               AttributeModifier.Operation operation) {
        if (inst == null || key == null) return;
        removeIfMatches(inst, key);
        inst.addModifier(new AttributeModifier(key, value, operation));
    }
}
