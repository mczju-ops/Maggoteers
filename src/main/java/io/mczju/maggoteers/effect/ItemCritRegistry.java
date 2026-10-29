package io.mczju.maggoteers.effect;

import io.mczju.maggoteers.util.GameRegistries;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 近战「元素暴击」配置（2026-10-29 用户口径）。
 *
 * <p>items/*.yml 里任意物品可写：
 * <pre>
 * crit:
 *   condition: burning | frozen | airborne | wither
 *   multiplier: 3.0            # mul 算法：最终伤害 = 原伤害 × multiplier
 *   sound: ITEM_FIRECHARGE_USE # 可选，缺省用该条件的默认音效
 *   sound_volume: 0.9
 *   sound_pitch: 1.2
 * </pre>
 *
 * <p>判定与结算在 {@code MeleeCritListener}（HIGH，近战命中前改伤害）；
 * 条件与默认音效的**唯一真相**是本类，别在监听器里另写一份。
 */
public final class ItemCritRegistry {

    /** 暴击触发的目标状态。 */
    public enum Condition {
        /** 燃烧中（{@code getFireTicks() > 0}）。 */
        BURNING,
        /** 寒冷/冻结中（{@code getFreezeTicks() > 0}）。 */
        FROZEN,
        /** 在空中（{@code !isOnGround()}）。 */
        AIRBORNE,
        /** 带凋零（{@code hasPotionEffect(WITHER)}）。 */
        WITHER;

        /** 解析 YAML 值；未知返回 null（加载期告警并跳过该物品）。 */
        public static Condition parse(String raw) {
            if (raw == null || raw.isBlank()) return null;
            try {
                return valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                return null;
            }
        }
    }

    /** {@code multiplier} 合法区间：>1 才有意义，<=1 视为配置错误。 */
    public static final double MIN_MULTIPLIER = 1.01;
    public static final double MAX_MULTIPLIER = 20.0;

    /** {@code sound} 为 null = 播放时回落到 {@link #defaultSound(Condition)}（默认音效表）。 */
    public record Spec(Condition condition, double multiplier, Sound sound, float volume, float pitch) {

        /** 实际要播的音效（未显式配置时用条件默认值）。 */
        public Sound resolveSound() {
            return sound != null ? sound : ItemCritRegistry.defaultSound(condition);
        }
    }

    private static final Map<String, Spec> BY_ITEM = new HashMap<>();

    private ItemCritRegistry() {}

    public static void clear() {
        BY_ITEM.clear();
    }

    /** 该物品 id 的元素暴击配置；没有则 null。 */
    public static Spec critOf(String itemId) {
        return itemId == null ? null : BY_ITEM.get(itemId);
    }

    public static void register(String itemId, Spec spec) {
        if (itemId == null || itemId.isBlank() || spec == null) return;
        BY_ITEM.put(itemId, spec);
    }

    /** 条件的默认音效（用户口径）：燃烧=火焰弹 / 寒冷=冰块破碎 / 空中=风弹 / 凋零=嘎吱怪死亡。 */
    public static Sound defaultSound(Condition condition) {
        if (condition == null) return null;
        return switch (condition) {
            case BURNING -> Sound.ITEM_FIRECHARGE_USE;
            case FROZEN -> Sound.BLOCK_GLASS_BREAK;
            case AIRBORNE -> Sound.ENTITY_BREEZE_WIND_BURST;   // 2026-10-29 用户口径：风弹用 breeze.wind_burst
            case WITHER -> Sound.ENTITY_CREAKING_DEATH;
        };
    }

    /** 解析一个物品的 {@code crit:} 段；非法配置告警后跳过（不抛、不崩）。 */
    public static void parse(Logger log, String itemId, ConfigurationSection sec) {
        if (sec == null) return;
        Condition condition = Condition.parse(sec.getString("condition"));
        if (condition == null) {
            log.warning("items: " + itemId + " crit.condition 非法（burning|frozen|airborne|wither），已忽略");
            return;
        }
        double multiplier = sec.getDouble("multiplier", 0.0);
        if (multiplier < MIN_MULTIPLIER || multiplier > MAX_MULTIPLIER) {
            log.warning("items: " + itemId + " crit.multiplier=" + multiplier
                    + " 超出 (" + MIN_MULTIPLIER + ", " + MAX_MULTIPLIER + "]，已忽略");
            return;
        }
        Sound sound = null;
        String rawSound = sec.getString("sound");
        if (rawSound != null && !rawSound.isBlank()) {
            Sound parsed = GameRegistries.sound(rawSound);
            if (parsed != null) {
                sound = parsed;
            } else {
                log.warning("items: " + itemId + " crit.sound 未识别: " + rawSound + "（用默认音效）");
            }
        }
        float volume = (float) sec.getDouble("sound_volume", 0.9);
        float pitch = (float) sec.getDouble("sound_pitch", 1.2);
        register(itemId, new Spec(condition, multiplier, sound, volume, pitch));
    }

    /** 已加载条数（启动日志用）。 */
    public static int size() {
        return BY_ITEM.size();
    }
}
