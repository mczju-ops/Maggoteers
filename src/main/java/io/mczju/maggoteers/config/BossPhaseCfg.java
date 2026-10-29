package io.mczju.maggoteers.config;

import io.mczju.maggoteers.mob.MobEffectSpec;

import java.util.List;

/**
 * Boss 阶段（2026-10-26，门扉缔造者）：strategy 级的 `phases:` 列表。
 *
 * <p>语义：Boss 出生即阶段 1；每个阶段声明一条**门槛**（时间或血量百分比），
 * 跨过门槛就进入该阶段并按序执行 {@code effects}（音效/免疫切换/给玩家上负面…）。
 * 技能上写 {@code phases: [2,3,4]} 即"只在这些阶段可用"。
 *
 * @param id       阶段号（1 为初始阶段，配置里不必写）
 * @param afterSec 出生后 N 秒进入（与 hpBelow 二选一，优先 afterSec）
 * @param hpBelow  血量比例 ≤ 该值进入（0.6667 = 2000/3000）
 * @param effects  进入阶段时执行的效果（按出现顺序）
 */
public record BossPhaseCfg(int id, double afterSec, double hpBelow, List<MobEffectSpec> effects) {
    public BossPhaseCfg {
        effects = effects == null ? List.of() : List.copyOf(effects);
    }

    public boolean byTime() {
        return afterSec > 0;
    }
}
