package io.mczju.maggoteers.effect;

import io.mczju.maggoteers.game.MaggoteersGame;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Resolves DISABLE_AI targets + hostile filter (spec §5.1 / §5.3). */
public final class DisableAiTargets {

    private DisableAiTargets() {}

    public static List<LivingEntity> resolve(MaggoteersGame game, Player caster,
                                             EffectContext params, TriggerContext ctx) {
        EffectContext p = MagicEffectParams.withDefaults(Effect.DISABLE_AI, params);
        String t = p.getOrDefault(EffectKeys.TARGETS, AuraParams.TARGET_ENEMIES)
                .toLowerCase(Locale.ROOT);
        List<LivingEntity> raw = resolveTargetKey(t, game, caster, p, ctx);
        List<LivingEntity> out = new ArrayList<>();
        for (LivingEntity le : raw) {
            if (passesHostileFilter(le, game)) {
                out.add(le);
            }
        }
        return out;
    }

    static List<LivingEntity> resolveTargetKey(String targetsKey, MaggoteersGame game, Player caster,
                                               EffectContext params, TriggerContext ctx) {
        if (targetsKey == null || targetsKey.isBlank()) {
            return List.of();
        }
        return switch (targetsKey.toLowerCase(Locale.ROOT)) {
            case BuffAreaTargets.TARGET_HIT_TARGET -> ctx != null && ctx.hitTarget() != null
                    ? List.of(ctx.hitTarget()) : List.of();
            case BuffAreaTargets.TARGET_ATTACKER -> ctx != null && ctx.attacker() != null
                    ? List.of(ctx.attacker()) : List.of();
            case AuraParams.TARGET_ENEMIES -> {
                if (caster == null) {
                    yield List.of();
                }
                double r = params.getOrDefault(EffectKeys.RADIUS, 5.0);
                Location center = TriggerContext.resolveOrigin(caster, ctx);
                if (center == null) {
                    yield List.of();
                }
                yield TargetResolver.collect(game, caster, center, r, Effect.DISABLE_AI, params);
            }
            default -> List.of();
        };
    }

    /**
     * Skip null / dead / invalid / Player / tracked player summons.
     * Wave allies that are players are already excluded by {@code instanceof Player}.
     */
    public static boolean passesHostileFilter(LivingEntity le, MaggoteersGame game) {
        if (le == null || !le.isValid() || le.isDead()) {
            return false;
        }
        if (le instanceof Player) {
            return false;
        }
        if (SummonRegistry.isTrackedSummon(le)) {
            return false;
        }
        return true;
    }
}
