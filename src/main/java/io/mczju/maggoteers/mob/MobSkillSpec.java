package io.mczju.maggoteers.mob;

import io.mczju.maggoteers.util.GameDifficulty;

import java.util.List;

/**
 * 一条怪物技能（trigger + 可选条件 + 可选计数器 + 效果列表）。
 * <p>{@code invisible: true} 由 {@code MobSkillService} 在 SPAWN 时处理
 * （setInvisible + 玻璃瓶头盔，不用 INVISIBILITY 药水）。{@code effects} 允许为空（纯装饰）。
 * <p>{@code difficulty}（1.3.3）可选：{@code hard}=仅在困难及以上剧本挂载，{@code expert}=仅在专家；
 * {@code null}=全难度。挂载过滤在 {@link MobSkillService#attach}。
 * <p>{@code chance}（2026-10-26）：每次触发时的命中概率（0~1，默认 1.0 = 必触发）。对齐 InfernalMobs
 * 的 {@code chance} 语义——**冷却先按 {@code cooldown_sec} 消耗，再掷概率**，所以掷空也吃冷却
 * （与 IM {@code CombatService} 一致）。{@code refrigerate} 用它做 25% 冰冻。
 */
public record MobSkillSpec(
        String id,
        MobTrigger trigger,
        MobCondition condition,
        boolean invisible,
        MobCounterSpec counter,
        int cooldownSec,
        List<MobEffectSpec> effects,
        GameDifficulty difficulty,
        DamageReduction damageReduction,
        double chance,
        /** D1（2026-10-26）：只触发一次（典型：血线阶段切换）——见 {@link OnceGate}。 */
        boolean once,
        /** D2（2026-10-26）：单次伤害上限 / 真免疫；null = 无门。 */
        MobDamageGate damageGate,
        /** 阶段限定（2026-10-26）：空 = 全阶段可用；否则只在这些阶段（1 基）生效。 */
        java.util.Set<Integer> phases,
        /** 永久关闭 AI（2026-10-26；与限时 DISABLE_AI 区分）。 */
        boolean noAi
) {
    public MobSkillSpec {
        effects = effects == null ? List.of() : List.copyOf(effects);
        cooldownSec = Math.max(0, cooldownSec);
        chance = Math.max(0.0, Math.min(1.0, chance));
        phases = phases == null ? java.util.Set.of() : java.util.Set.copyOf(phases);
    }

    /** 该技能在指定阶段是否可用（空 set = 全阶段）。 */
    public boolean phaseAllowed(int phase) {
        return phases.isEmpty() || phases.contains(phase);
    }

    /** 12 参完备构造（旧调用方兼容：once=false、无伤害门）。 */
    public MobSkillSpec(String id, MobTrigger trigger, MobCondition condition, boolean invisible,
                        MobCounterSpec counter, int cooldownSec, List<MobEffectSpec> effects,
                        GameDifficulty difficulty, DamageReduction damageReduction, double chance) {
        this(id, trigger, condition, invisible, counter, cooldownSec, effects, difficulty,
                damageReduction, chance, false, null, java.util.Set.of(), false);
    }

    /** 9 参兼容构造：无难度限制、无被动减伤、必触发。 */
    public MobSkillSpec(String id, MobTrigger trigger, MobCondition condition, boolean invisible,
                        MobCounterSpec counter, int cooldownSec, List<MobEffectSpec> effects,
                        GameDifficulty difficulty, DamageReduction damageReduction) {
        this(id, trigger, condition, invisible, counter, cooldownSec, effects, difficulty, damageReduction, 1.0);
    }

    /** 8 参兼容构造：无难度限制、无被动减伤。 */
    public MobSkillSpec(String id, MobTrigger trigger, MobCondition condition, boolean invisible,
                        MobCounterSpec counter, int cooldownSec, List<MobEffectSpec> effects,
                        GameDifficulty difficulty) {
        this(id, trigger, condition, invisible, counter, cooldownSec, effects, difficulty, null, 1.0);
    }

    /** 7 参兼容构造：全难度可用。 */
    public MobSkillSpec(String id, MobTrigger trigger, MobCondition condition, boolean invisible,
                        MobCounterSpec counter, int cooldownSec, List<MobEffectSpec> effects) {
        this(id, trigger, condition, invisible, counter, cooldownSec, effects, null, null, 1.0);
    }
}
