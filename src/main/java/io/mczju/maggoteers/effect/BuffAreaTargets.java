package io.mczju.maggoteers.effect;

import io.mczju.maggoteers.game.AllyTargeting;
import io.mczju.maggoteers.game.MaggoteersGame;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

/** Resolves BUFF_AREA target lists (dedicated hit_target branch). */
public final class BuffAreaTargets {

    public static final String TARGET_SELF = "self";
    public static final String TARGET_HIT_TARGET = "hit_target";
    public static final String TARGET_ATTACKER = "attacker";
    public static final String ANCHOR_DEATH_SITE = "death_site";

    private BuffAreaTargets() {}

    public static List<LivingEntity> resolve(MaggoteersGame game, Player caster,
                                             EffectContext params, TriggerContext ctx) {
        EffectContext p = MagicEffectParams.withDefaults(Effect.BUFF_AREA, params);
        String t = p.getOrDefault(EffectKeys.TARGETS, AuraParams.TARGET_ALLIES)
                .toLowerCase(Locale.ROOT);
        return resolveTargetKey(t, game, caster, p, ctx);
    }

    static List<LivingEntity> resolveTargetKey(String targetsKey, MaggoteersGame game, Player caster,
                                               EffectContext params, TriggerContext ctx) {
        if (targetsKey == null || targetsKey.isBlank()) {
            return List.of();
        }
        return switch (targetsKey.toLowerCase(Locale.ROOT)) {
            case TARGET_SELF -> caster == null ? List.of() : List.of(caster);
            case TARGET_HIT_TARGET -> ctx != null && ctx.hitTarget() != null
                    ? List.of(ctx.hitTarget()) : List.of();
            case TARGET_ATTACKER -> ctx != null && ctx.attacker() != null
                    ? List.of(ctx.attacker()) : List.of();
            case AuraParams.TARGET_ALLIES -> {
                double r = params.getOrDefault(EffectKeys.RADIUS, 5.0);
                boolean inc = AuraParams.includeSelf(params);
                org.bukkit.Location center = TriggerContext.resolveOrigin(caster, ctx);
                yield AllyTargeting.alliesNear(game, center, r, caster, inc).stream()
                        .map(pl -> (LivingEntity) pl)
                        .toList();
            }
            case AuraParams.TARGET_ENEMIES -> {
                double r = params.getOrDefault(EffectKeys.RADIUS, 5.0);
                if (caster == null) {
                    yield List.of();
                }
                org.bukkit.Location center = TriggerContext.resolveOrigin(caster, ctx);
                if (center == null) {
                    yield List.of();
                }
                yield TargetResolver.collect(game, caster, center, r, Effect.BUFF_AREA, params);
            }
            default -> List.of();
        };
    }
}
