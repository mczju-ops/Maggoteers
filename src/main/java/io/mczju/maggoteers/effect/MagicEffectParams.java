package io.mczju.maggoteers.effect;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Defaults and load-time validation for configurable magic effect params. */
public final class MagicEffectParams {

    private MagicEffectParams() {}

    public static EffectContext withDefaults(Effect effect, EffectContext raw) {
        EffectContext p = raw == null ? new EffectContext() : raw.copy();
        if (effect == null) {
            return p;
        }
        switch (effect) {
            case DAMAGE_AREA, DAMAGE_BEAM -> {
                if (!p.has(EffectKeys.TARGETS)) {
                    p.put(EffectKeys.TARGETS, AuraParams.TARGET_ENEMIES);
                }
                if (!p.has(EffectKeys.ENEMY_SCOPE)) {
                    p.put(EffectKeys.ENEMY_SCOPE, AuraParams.ENEMY_TRACKED);
                }
            }
            case HEAL_AREA -> {
                if (!p.has(EffectKeys.TARGETS)) {
                    p.put(EffectKeys.TARGETS, AuraParams.TARGET_ALLIES);
                }
                if (!p.has(EffectKeys.INCLUDE_SELF)) {
                    p.put(EffectKeys.INCLUDE_SELF, true);
                }
            }

            case BUFF_AREA -> {
                if (!p.has(EffectKeys.TARGETS)) {
                    p.put(EffectKeys.TARGETS, AuraParams.TARGET_ALLIES);
                }
                if (!p.has(EffectKeys.INCLUDE_SELF)) {
                    p.put(EffectKeys.INCLUDE_SELF, true);
                }
                if (!p.has(EffectKeys.ENEMY_SCOPE)) {
                    p.put(EffectKeys.ENEMY_SCOPE, AuraParams.ENEMY_TRACKED);
                }
                if (!p.has(EffectKeys.RADIUS)) {
                    p.put(EffectKeys.RADIUS, 5.0);
                }
            }
            // 2026-10-29：扇形打击默认打敌人、半径 8、张角 60°
            case CONE -> {
                if (!p.has(EffectKeys.TARGETS)) {
                    p.put(EffectKeys.TARGETS, AuraParams.TARGET_ENEMIES);
                }
                if (!p.has(EffectKeys.ENEMY_SCOPE)) {
                    p.put(EffectKeys.ENEMY_SCOPE, AuraParams.ENEMY_TRACKED);
                }
                if (!p.has(EffectKeys.RADIUS)) {
                    p.put(EffectKeys.RADIUS, 8.0);
                }
                if (!p.has(EffectKeys.ANGLE)) {
                    p.put(EffectKeys.ANGLE, 60.0);
                }
            }
            case DISABLE_AI -> {
                if (!p.has(EffectKeys.TARGETS)) {
                    p.put(EffectKeys.TARGETS, AuraParams.TARGET_ENEMIES);
                }
                if (!p.has(EffectKeys.ENEMY_SCOPE)) {
                    p.put(EffectKeys.ENEMY_SCOPE, AuraParams.ENEMY_TRACKED);
                }
                if (!p.has(EffectKeys.RADIUS)) {
                    p.put(EffectKeys.RADIUS, 5.0);
                }
            }
            case SUMMON -> p = SummonParamsParser.withDefaults(p);
            default -> { }
        }
        return p;
    }

    /**
     * HEAL_AREA load validation (spec section 5.4).
     *
     * @return empty if acceptable; otherwise a short reason for warn+skip
     */
    public static Optional<String> validateHealAreaTargets(EffectContext params) {
        if (params == null) {
            return Optional.empty();
        }
        String targets = params.get(EffectKeys.TARGETS);
        if (targets == null || targets.isBlank()) {
            return Optional.empty();
        }
        String t = targets.trim().toLowerCase(Locale.ROOT);
        if (AuraParams.TARGET_ALLIES.equals(t)) {
            return Optional.empty();
        }
        if (AuraParams.TARGET_ENEMIES.equals(t)) {
            return Optional.of("targets=enemies cannot heal");
        }
        if (AuraParams.TARGET_BOTH.equals(t)) {
            if (Boolean.TRUE.equals(params.get(EffectKeys.INCLUDE_SELF))) {
                return Optional.empty();
            }
            return Optional.of("targets=both requires include_self: true");
        }
        return Optional.of("unknown targets: " + t);
    }


    /** BUFF_AREA load validation (spec section 7.1). */
    public static Optional<String> validateBuffArea(EffectContext params, Trigger triggerOrNull, boolean weaponPath) {
        if (params == null) {
            return Optional.empty();
        }
        EffectContext p = withDefaults(Effect.BUFF_AREA, params);
        java.util.List<BuffPotionSpec> potions = p.get(EffectKeys.POTIONS);
        java.util.List<org.bukkit.potion.PotionEffectType> clears = p.get(EffectKeys.CLEAR_POTIONS);
        boolean hasPotions = potions != null && !potions.isEmpty();
        boolean hasClear = clears != null && !clears.isEmpty();
        if (!hasPotions && !hasClear) {
            return Optional.of("missing potions or clear_potions");
        }
        String targets = p.get(EffectKeys.TARGETS);
        if (targets != null && !targets.isBlank()) {
            String t = targets.trim().toLowerCase(java.util.Locale.ROOT);
            if (!java.util.Set.of(
                    BuffAreaTargets.TARGET_SELF,
                    AuraParams.TARGET_ALLIES,
                    AuraParams.TARGET_ENEMIES,
                    BuffAreaTargets.TARGET_HIT_TARGET,
                    BuffAreaTargets.TARGET_ATTACKER
            ).contains(t)) {
                return Optional.of("unknown targets: " + t);
            }
            if (BuffAreaTargets.TARGET_ATTACKER.equals(t)) {
                if (weaponPath) {
                    return Optional.of("targets=attacker ineffective on weapon right-click");
                }
                if (triggerOrNull == Trigger.ON_KILL || triggerOrNull == Trigger.ON_DAMAGE_DEALT) {
                    return Optional.of("targets=attacker incompatible with " + triggerOrNull);
                }
            }
            if ((AuraParams.TARGET_ALLIES.equals(t) || AuraParams.TARGET_ENEMIES.equals(t))
                    && p.has(EffectKeys.RADIUS)) {
                Double radius = p.get(EffectKeys.RADIUS);
                if (radius != null && radius <= 0) {
                    return Optional.of("radius must be > 0");
                }
            }
        }
        if (!weaponPath && triggerOrNull == null) {
            return Optional.of("fireTrigger required");
        }
        return Optional.empty();
    }

    /**
     * DISABLE_AI load validation (spec §6).
     *
     * @param triggerOrNull STAT/held fireTrigger; null for weapon path
     * @param weaponPath    true = use_ability (fireTrigger not required)
     * @return empty if acceptable; otherwise short reason for warn+skip
     */
    public static Optional<String> validateDisableAi(EffectContext params,
                                                     Trigger triggerOrNull,
                                                     boolean weaponPath) {
        if (params == null) {
            return Optional.of("missing params");
        }
        EffectContext p = withDefaults(Effect.DISABLE_AI, params);

        Integer duration = p.get(EffectKeys.DURATION_TICKS);
        if (duration == null || duration <= 0) {
            return Optional.of("duration_ticks must be > 0");
        }

        String targetsRaw = p.get(EffectKeys.TARGETS);
        String t = targetsRaw == null || targetsRaw.isBlank()
                ? AuraParams.TARGET_ENEMIES
                : targetsRaw.trim().toLowerCase(Locale.ROOT);

        if (!Set.of(
                AuraParams.TARGET_ENEMIES,
                BuffAreaTargets.TARGET_HIT_TARGET,
                BuffAreaTargets.TARGET_ATTACKER
        ).contains(t)) {
            return Optional.of("unsupported targets: " + t);
        }

        if (AuraParams.TARGET_ENEMIES.equals(t) && p.has(EffectKeys.RADIUS)) {
            Double radius = p.get(EffectKeys.RADIUS);
            if (radius != null && radius <= 0) {
                return Optional.of("radius must be > 0");
            }
        }

        if (AuraParams.TARGET_ENEMIES.equals(t) && p.has(EffectKeys.ENEMY_SCOPE)) {
            String scope = p.get(EffectKeys.ENEMY_SCOPE);
            if (scope != null && !scope.isBlank()) {
                String s = scope.trim().toLowerCase(Locale.ROOT);
                if (!AuraParams.ENEMY_TRACKED.equals(s) && !AuraParams.ENEMY_ALL_LIVING.equals(s)) {
                    return Optional.of("invalid enemy_scope: " + s);
                }
            }
        }

        if (!weaponPath && triggerOrNull == null) {
            return Optional.of("fireTrigger required");
        }

        if (triggerOrNull != null) {
            if (BuffAreaTargets.TARGET_HIT_TARGET.equals(t)) {
                if (triggerOrNull == Trigger.ON_DAMAGE_TAKEN) {
                    return Optional.of("targets=hit_target incompatible with ON_DAMAGE_TAKEN");
                }
                if (triggerOrNull == Trigger.ON_KILL) {
                    return Optional.of("targets=hit_target incompatible with ON_KILL");
                }
            }
            if (BuffAreaTargets.TARGET_ATTACKER.equals(t)) {
                if (triggerOrNull == Trigger.ON_KILL) {
                    return Optional.of("targets=attacker incompatible with ON_KILL");
                }
                if (triggerOrNull == Trigger.ON_DAMAGE_DEALT) {
                    return Optional.of("targets=attacker incompatible with ON_DAMAGE_DEALT");
                }
            }
        }

        return Optional.empty();
    }

}
