package io.mczju.maggoteers.effect;

import java.util.Locale;
import java.util.Optional;

/**
 * 虚拟属性（不写进 Bukkit 属性系统，使用时现算）。
 *
 * <p>2026-10-28：废弃 {@code COOLDOWN_REDUCTION} / {@code COOLDOWN_COEFFICIENT}，
 * 换成一个类似的系数属性 {@link #RELOAD_SPEED}（装填速度）：
 * <b>最终冷却 = 初始冷却 / 装填速度</b>，且所有藏品都是 <b>ADD 加算</b>。
 * 这样同一档优化的值不变（-25% CD → +33% 装填），但叠加从乘算变加算，
 * 玩家输出曲线从指数变为线性。
 *
 * <p>系数类（基础 100）：{@code HEAL} / {@code RANGE} / {@code RELOAD_SPEED}。
 * YAML 写 {@code op: ADD, value: 20} = +20 点。多个来源加算。
 */
public enum VirtualStats {
    MAGIC_DAMAGE,
    /** 治疗强度系数（基础 100）：技能治疗量 ×(100+ΣΔ)/100。 */
    HEAL,
    /** 技能范围系数（基础 100）：半径 / 射程 ×(100+ΣΔ)/100。 */
    RANGE,
    /**
     * 装填速度系数（基础 100）：最终冷却 = 基础冷却 / (1 + ΣΔ/100)。
     * 诅咒可以为负（如 -33 → 冷却 ×1.5）。
     */
    RELOAD_SPEED;

    public static Optional<VirtualStats> parse(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        String u = name.trim().toUpperCase(Locale.ROOT);
        for (VirtualStats vs : values()) {
            if (vs.name().equals(u)) return Optional.of(vs);
        }
        return Optional.empty();
    }

    public static boolean isMagicDamage(String name) {
        return parse(name).filter(v -> v == MAGIC_DAMAGE).isPresent();
    }

    /** 系数类（基础 100）属性：值按百分点加算。 */
    public static boolean isCoefficient(String name) {
        return parse(name).filter(v -> v == HEAL || v == RANGE || v == RELOAD_SPEED).isPresent();
    }

    public static boolean isHeal(String name) {
        return parse(name).filter(v -> v == HEAL).isPresent();
    }

    public static boolean isRange(String name) {
        return parse(name).filter(v -> v == RANGE).isPresent();
    }

    public static boolean isReloadSpeed(String name) {
        return parse(name).filter(v -> v == RELOAD_SPEED).isPresent();
    }
}
