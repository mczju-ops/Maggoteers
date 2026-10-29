package io.mczju.maggoteers.config;

/** 系数倍率（波次内 coeff）。Plan 3 由 RunPlanner 再叠 affix×scaling。 */
public record CoeffCfg(double hp, double dmg, double speed, double scale, double followRange) {
    public CoeffCfg(double hp, double dmg, double speed) {
        this(hp, dmg, speed, 1.0, 1.0);
    }

    public CoeffCfg {
        if (hp <= 0) hp = 1;
        if (dmg <= 0) dmg = 1;
        if (speed <= 0) speed = 1;
        if (scale <= 0) scale = 1;
        if (followRange <= 0) followRange = 1;
    }
}
