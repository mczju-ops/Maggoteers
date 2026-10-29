package io.mczju.maggoteers.effect;

import java.util.Optional;
import java.util.Set;

/** Load-time validation for weapon held_effects and use_ability ADD_POTION paths. */
public final class WeaponEffectValidator {

    private static final Set<Trigger> HELD_TRIGGERS = Set.of(
            Trigger.ON_DAMAGE_DEALT, Trigger.ON_DAMAGE_TAKEN, Trigger.ON_KILL, Trigger.ON_COUNTER);

    private static final Set<Effect> PERMANENT_HELD = Set.of(
            Effect.ADD_ATTRIBUTE, Effect.ADD_POTION, Effect.AURA);

    private WeaponEffectValidator() {}

    public static Optional<String> validateHeld(Effect effect, Trigger fireTrigger,
                                                 EffectContext params, Stack stack,
                                                 int upgradeMax, boolean hasExpiryBlock) {
        if (hasExpiryBlock) {
            return Optional.of("held_effects must not use expiry");
        }
        if (upgradeMax > 0 || stack == Stack.UPGRADE_LEVEL) {
            return Optional.of("held forbids UPGRADE_LEVEL");
        }
        if (effect == Effect.GRANT_ITEM || effect == Effect.SUMMON) {
            return Optional.of("held forbids " + effect);
        }
        if (params != null && effect == Effect.ADD_ATTRIBUTE
                && TriggeredGrantAttribute.isRevoke(params)) {
            return Optional.of("held forbids REVOKE_GRANTS");
        }
        if (params != null && effect == Effect.ADD_ATTRIBUTE) {
            String attrName = params.get(EffectKeys.ATTR_NAME);
            if (attrName != null && VirtualStats.isReloadSpeed(attrName)) {
                if (!params.has(EffectKeys.VALUE)) {
                    return Optional.of("missing value");
                }
                String op = params.get(EffectKeys.OP);
                if (op != null && !"ADD".equalsIgnoreCase(op.trim())) {
                    return Optional.of("RELOAD_SPEED requires op ADD (or omitted)");
                }
            }
        }
        if (fireTrigger != null) {
            if (!HELD_TRIGGERS.contains(fireTrigger)) {
                return Optional.of("forbidden held trigger: " + fireTrigger);
            }
            if (fireTrigger == Trigger.ON_COUNTER) {
                if (params == null || params.get(EffectKeys.COUNTER_SPEC) == null) {
                    return Optional.of("ON_COUNTER held requires params.counter");
                }
            }
            if (effect == Effect.AURA) {
                return Optional.of("AURA held must be permanent");
            }
            if (effect == Effect.ADD_POTION) {
                int dur = params != null ? params.getOrDefault(EffectKeys.DURATION_TICKS, 0) : 0;
                if (dur <= 0) {
                    return Optional.of("triggered ADD_POTION requires duration_ticks > 0");
                }
                if (params == null || params.get(EffectKeys.POTION) == null) {
                    return Optional.of("missing potion");
                }
            }
            if (effect == Effect.HEAL_AREA) {
                return MagicEffectParams.validateHealAreaTargets(params)
                        .map(msg -> "HEAL_AREA " + msg);
            }
            if (effect == Effect.BUFF_AREA) {
                return MagicEffectParams.validateBuffArea(params, fireTrigger, false);
            }
            return Optional.empty();
        }
        if (!PERMANENT_HELD.contains(effect)) {
            return Optional.of("permanent held requires trigger for " + effect);
        }
        if (effect == Effect.ADD_POTION) {
            if (params == null || params.get(EffectKeys.POTION) == null) {
                return Optional.of("missing potion");
            }
        }
        if (effect == Effect.AURA) {
            if (params == null || params.get(EffectKeys.GRANT_EFFECT) == null) {
                return Optional.of("AURA missing grant");
            }
            if (params.getOrDefault(EffectKeys.RADIUS, 0.0) <= 0) {
                return Optional.of("AURA missing radius");
            }
        }
        return Optional.empty();
    }

    /** Spec 5.3 decision table for use_ability ADD_POTION only. */
    public static Optional<String> validateUseAbilityPotion(boolean hasExpiry, int durationTicks) {
        if (hasExpiry) {
            if (durationTicks > 0) {
                return Optional.of("expiry ADD_POTION must use duration_ticks 0 or omit");
            }
            return Optional.empty();
        }
        if (durationTicks > 0) {
            return Optional.empty();
        }
        return Optional.of("instant ADD_POTION requires duration_ticks > 0 without expiry");
    }
}
