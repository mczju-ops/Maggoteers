package io.mczju.maggoteers.effect;

import org.bukkit.event.entity.EntityDamageEvent;

/**
 * "玩家受伤倍率"是否施加的判定（纯函数，便于单测）。
 *
 * <p>2026-10-26 用户口径：倍率作用于"所有伤害"，但两项豁免：
 * <ol>
 *   <li><b>自伤</b>（自己打自己 / 自己技能反噬）——永远固定值，不乘倍率；</li>
 *   <li><b>摔落伤害</b>——不乘倍率，并且整局把玩家的 {@code SAFE_FALL_DISTANCE} 抬到 40 格，
 *       实际上不会再吃到摔落伤害。</li>
 * </ol>
 */
public final class DamageScalingPolicy {

    public static boolean shouldScale(EntityDamageEvent.DamageCause cause, boolean selfInflicted) {
        if (selfInflicted) return false;
        return cause != EntityDamageEvent.DamageCause.FALL;
    }

    private DamageScalingPolicy() {}
}
