package io.mczju.maggoteers.effect;

import org.bukkit.attribute.Attribute;
import org.bukkit.entity.EntityType;

import java.util.Map;

public record SummonParams(
        EntityType entityType,
        String anchor,
        int count,
        double offsetY,
        SummonCleanup cleanup,
        int durationSec,
        Map<Attribute, Double> attributes,
        boolean tamed,
        boolean friendlyFire,
        ProjectileParams projectile,
        String homingTarget
) {
    public SummonParams {
        if (count < 1) count = 1;
        if (anchor == null || anchor.isBlank()) anchor = BuffAreaTargets.TARGET_SELF;
        if (projectile == null) projectile = ProjectileParams.defaults();
        if (homingTarget == null || homingTarget.isBlank()) homingTarget = null;
    }

    /** 保留既有调用：无 homing（homingTarget=null）。 */
    public SummonParams(EntityType entityType, String anchor, int count, double offsetY,
                        SummonCleanup cleanup, int durationSec, Map<Attribute, Double> attributes,
                        boolean tamed, boolean friendlyFire, ProjectileParams projectile) {
        this(entityType, anchor, count, offsetY, cleanup, durationSec,
                attributes, tamed, friendlyFire, projectile, null);
    }

    public record ProjectileParams(double speed) {
        public static ProjectileParams defaults() {
            return new ProjectileParams(1.0);
        }
    }
}
