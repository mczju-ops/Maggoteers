package io.mczju.maggoteers.plan;

import io.mczju.maggoteers.config.CoeffCfg;
import io.mczju.maggoteers.config.ScalingConfig;

/**
 * 数值合成纯函数：最终倍率 = coeff × scaling（§7.1；affix 层已退役，改技能系统）。
 * 与 Bukkit 解耦，纯单测。
 *
 * <p>⚠️ 2026-10-26：**伤害不再乘 scaling**。`scaling.mob_damage` × `difficulty.multipliers.*.mob_damage`
 * 原先只乘进怪物 ATTACK_DAMAGE（只放大近战），现在改为"玩家受到的所有伤害"倍率，
 * 由 {@code PlayerDamageTakenListener} 在受伤事件里统一施加。
 */
public final class Compose {

    /** 一只怪的最终倍率（hp/dmg/speed/scale/follow_range）。 */
    public record MobScale(double hp, double dmg, double speed, double scale, double followRange) {}

    public static MobScale compose(CoeffCfg coeff, ScalingConfig.Scaling snap) {
        return new MobScale(
                coeff.hp() * snap.mobHp(), coeff.dmg(),
                coeff.speed() * snap.mobSpeed(),
                coeff.scale(), coeff.followRange());
    }

    private Compose() {}
}
