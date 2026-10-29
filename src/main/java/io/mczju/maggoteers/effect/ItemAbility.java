package io.mczju.maggoteers.effect;

import io.mczju.maggoteers.item.fx.MagicUseFx;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;

/** Parsed {@code use_ability} block for one weapon item id (one or more steps). */
public record ItemAbility(
        String itemId,
        double cooldownSec,
        boolean consume,
        MagicUseFx fx,
        MagicUseFx empowerFx,
        List<BuffPotionSpec> selfPotions,
        List<PotionEffectType> selfClearPotions,
        List<AbilityStep> steps
) {
    public ItemAbility {
        selfPotions = selfPotions == null ? List.of() : List.copyOf(selfPotions);
        selfClearPotions = selfClearPotions == null ? List.of() : List.copyOf(selfClearPotions);
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    /** All steps have expiry (next-hit / deferred pack). */
    public boolean isDeferredOnly() {
        if (steps.isEmpty()) {
            return false;
        }
        for (AbilityStep step : steps) {
            if (!step.isDeferred()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 2026-10-28（吟游诗人「每第四发音波弹 300%」）：复制一份并把**伤害参数** ×multiplier。
     * 只动 {@code DAMAGE} 键（DAMAGE / DAMAGE_AREA / DAMAGE_BEAM / SALVO 共用），
     * 冷却 / 治疗 / 范围都不变。{@code multiplier <= 1} 时原样返回。
     */
    public ItemAbility withDamageMultiplier(double multiplier) {
        if (steps.isEmpty() || multiplier <= 1.0) {
            return this;
        }
        List<AbilityStep> scaled = new ArrayList<>(steps.size());
        for (AbilityStep step : steps) {
            EffectContext params = step.params();
            if (params == null || !isDamageEffect(step.effect())) {
                scaled.add(step);
                continue;
            }
            EffectContext copy = params.copy();
            double dmg = copy.getOrDefault(EffectKeys.DAMAGE, 0.0);
            if (dmg > 0) {
                copy.put(EffectKeys.DAMAGE, dmg * multiplier);
            }
            scaled.add(new AbilityStep(step.effect(), copy,
                    step.expiryTrigger(), step.expiryCharges(), step.stack()));
        }
        return new ItemAbility(itemId, cooldownSec, consume, fx, empowerFx,
                selfPotions, selfClearPotions, scaled);
    }

    private static boolean isDamageEffect(Effect effect) {
        return effect == Effect.DAMAGE || effect == Effect.DAMAGE_AREA
                || effect == Effect.DAMAGE_BEAM || effect == Effect.SALVO
                || effect == Effect.BARRAGE || effect == Effect.DELAYED_AREA;
    }
}
