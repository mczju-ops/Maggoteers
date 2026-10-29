package io.mczju.maggoteers.mob;

import java.util.Locale;
import java.util.Set;

/**
 * 伤害分类（D2 / 2026-10-26）：把 Bukkit 的 {@code DamageCause} 归成 Boss 内容能写的粗粒度类别。
 *
 * <p>为什么不让内容直接写 {@code DamageCause}：内容里想表达的是"免疫远程/免疫近战/免疫法术"这种**叙事**
 * 分类，而 {@code DamageCause} 有 40 多个值（{@code ENTITY_ATTACK} / {@code ENTITY_SWEEP_ATTACK} /
 * {@code PROJECTILE} / {@code MAGIC} / {@code THORNS} …），写全等于把引擎细节漏进 YAML。
 *
 * <p>「法术」按本插件既有口径判定：伤害期间目标身上有 {@code MagicDamage.MAGIC_MARKER}
 * （见 {@code MagicDamage.applyIndependent}）⇒ {@link #MAGIC}，优先于其他分类。
 */
public enum DamageCategory {
    MELEE,
    PROJECTILE,
    MAGIC,
    EXPLOSION,
    FIRE,
    FALL,
    OTHER;

    /** YAML 里可写的小写名（也接受大写与 Bukkit 的 {@code DamageCause} 名做宽松匹配）。 */
    public static DamageCategory parse(String raw) {
        if (raw == null) return null;
        String n = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return switch (n) {
            case "MELEE", "ATTACK", "PHYSICAL", "ENTITY_ATTACK", "ENTITY_SWEEP_ATTACK", "THORNS" -> MELEE;
            case "PROJECTILE", "RANGED", "ARROW", "TRIDENT", "SNOWBALL" -> PROJECTILE;
            case "MAGIC", "SPELL", "POTION", "INDIRECT_MAGIC" -> MAGIC;
            case "EXPLOSION", "ENTITY_EXPLOSION", "BLOCK_EXPLOSION" -> EXPLOSION;
            case "FIRE", "FIRE_TICK", "LAVA", "HOT_FLOOR", "CAMPFIRE" -> FIRE;
            case "FALL", "FALLING_BLOCK", "VOID", "DROWNING" -> FALL;
            case "OTHER", "ANY" -> OTHER;
            default -> fromCauseName(n);
        };
    }

    private static DamageCategory fromCauseName(String n) {
        try {
            return of(org.bukkit.event.entity.EntityDamageEvent.DamageCause.valueOf(n), false);
        } catch (IllegalArgumentException | NoSuchFieldError ex) {
            return null;
        }
    }

    /**
     * {@code DamageCause} → 分类。
     *
     * @param magic 该次伤害是否打了 {@code MagicDamage.MAGIC_MARKER}（法术伤害优先判定）
     */
    public static DamageCategory of(org.bukkit.event.entity.EntityDamageEvent.DamageCause cause, boolean magic) {
        if (magic) return MAGIC;
        if (cause == null) return OTHER;
        return switch (cause) {
            case ENTITY_ATTACK, ENTITY_SWEEP_ATTACK, THORNS -> MELEE;
            case PROJECTILE -> PROJECTILE;
            case MAGIC -> MAGIC;
            case ENTITY_EXPLOSION, BLOCK_EXPLOSION -> EXPLOSION;
            case FIRE, FIRE_TICK, LAVA, HOT_FLOOR, CAMPFIRE -> FIRE;
            case FALL, FALLING_BLOCK, VOID, DROWNING -> FALL;
            default -> OTHER;
        };
    }

    /** {@code immune_damage: [projectile, magic]} 的解析（未知名静默丢弃，由 schema 测试兜底）。 */
    public static Set<DamageCategory> parseSet(java.util.List<String> raw) {
        if (raw == null || raw.isEmpty()) return Set.of();
        java.util.Set<DamageCategory> out = java.util.EnumSet.noneOf(DamageCategory.class);
        for (String s : raw) {
            DamageCategory c = parse(s);
            if (c != null) out.add(c);
        }
        return java.util.Collections.unmodifiableSet(out);
    }
}
