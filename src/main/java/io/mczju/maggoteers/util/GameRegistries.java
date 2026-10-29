package io.mczju.maggoteers.util;

import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import io.mczju.maggoteers.effect.VirtualStats;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.EntityType;
import org.bukkit.potion.PotionEffectType;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Paper 26.x Registry 解析（替代 getByName / Attribute.valueOf / EntityType.valueOf）。 */
public final class GameRegistries {

    /** Paper 1.21.2+ 去掉 generic. 前缀；两套都试。 */
    private static final Map<String, String[]> ATTRIBUTE_KEYS = Map.ofEntries(
            Map.entry("MAX_HEALTH", new String[]{"max_health", "generic.max_health"}),
            Map.entry("ATTACK_DAMAGE", new String[]{"attack_damage", "generic.attack_damage"}),
            Map.entry("MOVEMENT_SPEED", new String[]{"movement_speed", "generic.movement_speed"}),
            Map.entry("ATTACK_SPEED", new String[]{"attack_speed", "generic.attack_speed"})
    );

    private GameRegistries() {}

    public static PotionEffectType potionEffect(String name) {
        if (name == null || name.isBlank()) return null;
        String u = name.trim().toUpperCase(Locale.ROOT);
        String snake = u.toLowerCase(Locale.ROOT);
        String dotted = snake.replace('_', '.');
        for (String key : new String[]{snake, dotted}) {
            NamespacedKey nk = NamespacedKey.minecraft(key);
            // Paper 26：优先 EFFECT / MOB_EFFECT；POTION_EFFECT_TYPE 为兼容别名
            PotionEffectType t = Registry.EFFECT.get(nk);
            if (t != null) return t;
            t = Registry.MOB_EFFECT.get(nk);
            if (t != null) return t;
            t = Registry.POTION_EFFECT_TYPE.get(nk);
            if (t != null) return t;
        }
        PotionEffectType t = PotionEffectType.getByName(u);
        if (t != null) return t;
        return PotionEffectType.getByName(snake);
    }

    public static boolean isVirtualAttribute(String name) {
        return VirtualStats.parse(name).isPresent();
    }

    public static Attribute attribute(String name) {
        if (name == null || name.isBlank()) return null;
        if (isVirtualAttribute(name)) return null;
        String u = name.trim().toUpperCase(Locale.ROOT);
        // rewards.yml 使用 ATTACK_DAMAGE 等常量名；优先静态常量 / valueOf
        try {
            Attribute byValueOf = Attribute.valueOf(u);
            if (byValueOf != null) return byValueOf;
        } catch (IllegalArgumentException ignored) {
            // 走 Registry
        }
        try {
            // Paper 26：Attribute.MAX_HEALTH 等为接口静态字段
            var field = Attribute.class.getField(u);
            Object v = field.get(null);
            if (v instanceof Attribute a) return a;
        } catch (ReflectiveOperationException ignored) {
        }
        Set<String> candidates = new LinkedHashSet<>();
        String[] mapped = ATTRIBUTE_KEYS.get(u);
        if (mapped != null) {
            candidates.addAll(java.util.Arrays.asList(mapped));
        }
        // MAX_HEALTH → max_health（勿用 replace('_','.') 变成 max.health）
        candidates.add(u.toLowerCase(Locale.ROOT));
        String dotted = u.toLowerCase(Locale.ROOT).replace('_', '.');
        candidates.add(dotted);
        if (dotted.startsWith("generic.")) {
            candidates.add(dotted.substring("generic.".length()));
        }
        for (String key : candidates) {
            Attribute a = Registry.ATTRIBUTE.get(NamespacedKey.minecraft(key));
            if (a != null) return a;
        }
        var plugin = io.mczju.maggoteers.MaggoteersPlugin.getInstance();
        if (plugin != null) {
            plugin.getLogger().warning("未知属性: " + name);
        }
        return null;
    }

    public static EntityType entityType(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("entity type blank");
        String u = name.trim().toUpperCase(Locale.ROOT);
        try {
            EntityType t = Registry.ENTITY_TYPE.get(NamespacedKey.minecraft(u.toLowerCase(Locale.ROOT)));
            if (t != null) return t;
        } catch (RuntimeException | LinkageError ex) {
            // headless（单元测试）无 RegistryAccess 实现 → 回落枚举查找，保证解析逻辑可单测；运行时有 Registry 不受影响
            // LinkageError 覆盖首次 ExceptionInInitializerError 与后续 NoClassDefFoundError（Registry.<clinit> 失败后 JVM 行为）
        }
        try {
            return EntityType.valueOf(u);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown entity type: " + name);
        }
    }

    /** 解析 Sound：{@code ENTITY_PLAYER_ATTACK_SWEEP} 或 {@code minecraft:entity.player.attack.sweep}。 */
    public static org.bukkit.Sound sound(String name) {
        if (name == null || name.isBlank()) return null;
        String raw = name.trim();
        String u = raw.contains(":") ? raw.substring(raw.indexOf(':') + 1) : raw;
        u = u.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
        try {
            return org.bukkit.Sound.valueOf(u);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 解析 Particle；失败时返回 null（调用方保留默认）。 */
    public static Particle particle(String name) {
        if (name == null || name.isBlank()) return null;
        try {
            return Particle.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
