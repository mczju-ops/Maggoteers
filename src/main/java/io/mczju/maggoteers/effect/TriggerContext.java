package io.mczju.maggoteers.effect;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;

public record TriggerContext(
        Trigger fired,
        LivingEntity hitTarget,
        LivingEntity attacker,
        Location eventLocation,
        String counterId
) {
    public TriggerContext(Trigger fired, LivingEntity hitTarget, LivingEntity attacker) {
        this(fired, hitTarget, attacker, null, null);
    }

    /** Back-compat: 4-arg pre-record form (no counter). */
    public TriggerContext(Trigger fired, LivingEntity hitTarget, LivingEntity attacker, Location eventLocation) {
        this(fired, hitTarget, attacker, eventLocation, null);
    }

    public static TriggerContext empty() {
        return new TriggerContext(null, null, null, null, null);
    }

    /** Event anchor (e.g. death site) with optional trigger tag. */
    public static TriggerContext atEvent(Trigger fired, Location location) {
        Location loc = location == null ? null : location.clone();
        return new TriggerContext(fired, null, null, loc, null);
    }

    /**
     * 兼容包装（Spec1 起由 {@link EffectService#resolveOrigin} 取代，玩家/mob 施法者统一走那边）。
     * 仅保留给 SummonExecutor / BuffAreaTargets / DisableAiTargets 等旧调用点，语义不变。
     */
    public static Location resolveOrigin(org.bukkit.entity.Player player, TriggerContext ctx) {
        if (ctx != null && ctx.eventLocation() != null && ctx.eventLocation().getWorld() != null) {
            return ctx.eventLocation();
        }
        if (ctx != null && ctx.fired() == Trigger.ON_KILL && ctx.hitTarget() != null
                && ctx.hitTarget().getWorld() != null) {
            return ctx.hitTarget().getLocation();
        }
        return player != null ? player.getLocation() : null;
    }

    public TriggerContext withHitTarget(LivingEntity hitTarget) {
        return new TriggerContext(fired, hitTarget, attacker, eventLocation, counterId);
    }

    public TriggerContext withFired(Trigger fired) {
        return new TriggerContext(fired, hitTarget, attacker, eventLocation, counterId);
    }

    public TriggerContext withCounter(String counterId) {
        return new TriggerContext(fired, hitTarget, attacker, eventLocation, counterId);
    }
}
