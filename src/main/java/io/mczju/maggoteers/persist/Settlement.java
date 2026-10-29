package io.mczju.maggoteers.persist;

import io.mczju.maggoteers.game.GameOutcome;

/**
 * 结算纯函数（§7.6，与 Bukkit 解耦，单测）。
 * <p>WIN = win_flat；FAIL = fail_per_act × 已过层数 + fail_per_wave × 当前层已过波数；IN_PROGRESS = 0。
 * <p>结算积分再乘当局难度系数（普通 1.0 / 困难 1.25 / 专家 1.5，config.yml settlement.difficulty_multipliers）；
 * 负乘数钳到 0；结果四舍五入为整数（data.grant 收 int）。
 */
public final class Settlement {
    public record Input(GameOutcome outcome, int actsCleared, int wavesClearedInCurrentAct) {}

    public static int grant(Input in, int winFlat, int failPerAct, int failPerWave, double multiplier) {
        if (in == null || in.outcome() == null) return 0;
        double m = Math.max(0, multiplier);
        double base = switch (in.outcome()) {
            case WIN  -> winFlat;
            case FAIL -> Math.max(0, failPerAct) * Math.max(0, in.actsCleared())
                       + Math.max(0, failPerWave) * Math.max(0, in.wavesClearedInCurrentAct());
            default   -> 0;   // IN_PROGRESS
        };
        return (int) Math.round(base * m);
    }

    private Settlement() {}
}
