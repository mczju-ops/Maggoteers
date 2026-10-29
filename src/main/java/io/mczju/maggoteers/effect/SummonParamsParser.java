package io.mczju.maggoteers.effect;

import io.mczju.maggoteers.util.GameRegistries;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.EntityType;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

public final class SummonParamsParser {

    private static final Pattern ENTITY_NAME = Pattern.compile("^[A-Za-z0-9_]+$");

    private static final Set<String> ANCHORS = Set.of(
            BuffAreaTargets.TARGET_SELF,
            BuffAreaTargets.TARGET_HIT_TARGET,
            BuffAreaTargets.ANCHOR_DEATH_SITE,
            "attacker"
    );

    private SummonParamsParser() {}

    public static EffectContext withDefaults(EffectContext raw) {
        EffectContext p = raw == null ? new EffectContext() : raw.copy();
        if (!p.has(EffectKeys.ANCHOR)) {
            p.put(EffectKeys.ANCHOR, BuffAreaTargets.TARGET_SELF);
        }
        if (!p.has(EffectKeys.COUNT)) {
            p.put(EffectKeys.COUNT, 1);
        }
        if (!p.has(EffectKeys.FRIENDLY_FIRE)) {
            p.put(EffectKeys.FRIENDLY_FIRE, false);
        }
        if (!p.has(EffectKeys.TAMED)) {
            p.put(EffectKeys.TAMED, false);
        }
        return p;
    }

    public static Optional<String> validate(EffectContext params, Trigger triggerOrNull, boolean weaponPath) {
        EffectContext p = withDefaults(params);
        String entity = p.get(EffectKeys.ENTITY);
        if (entity == null || entity.isBlank()) {
            return Optional.of("missing entity");
        }
        if (!ENTITY_NAME.matcher(entity.trim()).matches()) {
            return Optional.of("invalid entity name: " + entity);
        }
        String cleanup = p.get(EffectKeys.CLEANUP);
        if (cleanup == null || cleanup.isBlank()) {
            return Optional.of("missing cleanup");
        }
        Optional<String> cleanupErr = validateCleanup(p);
        if (cleanupErr.isPresent()) return cleanupErr;

        String anchor = normalizeAnchor(p.get(EffectKeys.ANCHOR));
        if (!ANCHORS.contains(anchor)) {
            return Optional.of("unknown anchor: " + anchor);
        }
        if (weaponPath && !BuffAreaTargets.TARGET_SELF.equals(anchor)) {
            return Optional.of("weapon path only supports anchor: self");
        }
        if (!weaponPath && triggerOrNull != null) {
            Optional<String> compat = validateAnchorTrigger(anchor, triggerOrNull);
            if (compat.isPresent()) return compat;
        }
        return Optional.empty();
    }

    public static Optional<EntityType> resolveEntity(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        try {
            return Optional.of(GameRegistries.entityType(name));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    public static Optional<SummonParams> parse(EffectContext raw, boolean weaponPath) {
        EffectContext p = withDefaults(raw);
        if (weaponPath) {
            p.put(EffectKeys.ANCHOR, BuffAreaTargets.TARGET_SELF);
        }
        Optional<String> err = validate(p, null, weaponPath);
        if (err.isPresent()) return Optional.empty();

        Optional<EntityType> typeOpt = resolveEntity(p.get(EffectKeys.ENTITY));
        if (typeOpt.isEmpty()) return Optional.empty();

        String anchor = normalizeAnchor(p.get(EffectKeys.ANCHOR));
        EntityType type = typeOpt.get();
        SummonCleanup cleanup = parseCleanupKind(p);
        int durationSec = p.getOrDefault(EffectKeys.DURATION_SEC, 0);
        int count = p.getOrDefault(EffectKeys.COUNT, 1);
        double offsetY = p.getOrDefault(EffectKeys.OFFSET_Y, 0.0);
        boolean tamed = Boolean.TRUE.equals(p.get(EffectKeys.TAMED));
        boolean friendlyFire = Boolean.TRUE.equals(p.get(EffectKeys.FRIENDLY_FIRE));
        Map<Attribute, Double> attrs = parseAttributes(p.get(EffectKeys.ATTRIBUTES), type);
        SummonParams.ProjectileParams projectile = parseProjectile(p.get(EffectKeys.PROJECTILE));

        return Optional.of(new SummonParams(
                type, anchor, count, offsetY, cleanup, durationSec, attrs, tamed, friendlyFire, projectile,
                p.get(EffectKeys.HOMING_TARGET)));
    }

    static Optional<String> validateAnchorTrigger(String anchor, Trigger trigger) {
        if (BuffAreaTargets.TARGET_HIT_TARGET.equals(anchor) && trigger == Trigger.ON_DAMAGE_TAKEN) {
            return Optional.of("anchor=hit_target incompatible with ON_DAMAGE_TAKEN");
        }
        if ("attacker".equals(anchor)
                && (trigger == Trigger.ON_DAMAGE_DEALT || trigger == Trigger.ON_KILL)) {
            return Optional.of("anchor=attacker incompatible with " + trigger);
        }
        if (BuffAreaTargets.ANCHOR_DEATH_SITE.equals(anchor) && trigger != Trigger.ON_DEATH) {
            return Optional.of("anchor=death_site requires ON_DEATH");
        }
        if (trigger == Trigger.ON_DEATH
                && (BuffAreaTargets.TARGET_HIT_TARGET.equals(anchor) || "attacker".equals(anchor))) {
            return Optional.of("anchor=" + anchor + " incompatible with ON_DEATH");
        }
        return Optional.empty();
    }

    static Optional<String> validateCleanup(EffectContext p) {
        String cleanup = p.get(EffectKeys.CLEANUP).trim().toLowerCase(Locale.ROOT);
        if ("wave_clear".equals(cleanup)) return Optional.empty();
        if ("duration".equals(cleanup)) {
            int sec = p.getOrDefault(EffectKeys.DURATION_SEC, 0);
            if (sec <= 0) return Optional.of("duration cleanup requires duration_sec > 0");
            return Optional.empty();
        }
        return Optional.of("unknown cleanup: " + cleanup);
    }

    private static SummonCleanup parseCleanupKind(EffectContext p) {
        String cleanup = p.get(EffectKeys.CLEANUP).trim().toLowerCase(Locale.ROOT);
        return "duration".equals(cleanup) ? SummonCleanup.DURATION : SummonCleanup.WAVE_CLEAR;
    }

    private static String normalizeAnchor(String anchor) {
        if (anchor == null || anchor.isBlank()) return BuffAreaTargets.TARGET_SELF;
        return anchor.trim().toLowerCase(Locale.ROOT);
    }

    static Map<Attribute, Double> parseAttributes(Map<String, Double> raw, EntityType type) {
        Map<Attribute, Double> out = new HashMap<>();
        if (raw == null || raw.isEmpty()) return out;
        for (var e : raw.entrySet()) {
            Attribute attr = GameRegistries.attribute(e.getKey());
            if (attr == null) continue;
            out.put(attr, e.getValue());
        }
        return Map.copyOf(out);
    }

    static SummonParams.ProjectileParams parseProjectile(EffectContext sec) {
        if (sec == null) return SummonParams.ProjectileParams.defaults();
        double speed = sec.getOrDefault(EffectKeys.PROJECTILE_SPEED, 1.0);
        return new SummonParams.ProjectileParams(speed);
    }
}