package io.mczju.maggoteers.effect;

/** 解析 {@link Effect#AURA} 的 {@link EffectContext} 参数。 */
public final class AuraParams {
    public static final String TARGET_ALLIES = "allies";
    public static final String TARGET_ENEMIES = "enemies";
    public static final String TARGET_BOTH = "both";
    public static final String ENEMY_TRACKED = "tracked";
    public static final String ENEMY_ALL_LIVING = "all_living";

    private AuraParams() {}

    public static double radius(EffectContext p) {
        return Math.max(0.1, p.getOrDefault(EffectKeys.RADIUS, 8.0));
    }

    public static String targets(EffectContext p) {
        String t = p.get(EffectKeys.TARGETS);
        return t == null ? TARGET_ALLIES : t.toLowerCase();
    }

    public static String enemyScope(EffectContext p) {
        String s = p.get(EffectKeys.ENEMY_SCOPE);
        return s == null ? ENEMY_TRACKED : s.toLowerCase();
    }

    public static boolean includeSelf(EffectContext p) {
        Boolean b = p.get(EffectKeys.INCLUDE_SELF);
        return b == null || b;
    }

    public static int grantPulseSec(EffectContext p) {
        return Math.max(1, p.getOrDefault(EffectKeys.GRANT_PULSE_SEC, 1));
    }

    public static Effect grantEffect(EffectContext p) {
        return p.get(EffectKeys.GRANT_EFFECT);
    }

    public static EffectContext grantParams(EffectContext p) {
        EffectContext g = p.get(EffectKeys.GRANT_PARAMS);
        return g == null ? new EffectContext() : g;
    }

    public static boolean targetsAllies(EffectContext p) {
        String t = targets(p);
        return TARGET_ALLIES.equals(t) || TARGET_BOTH.equals(t);
    }

    public static boolean targetsEnemies(EffectContext p) {
        String t = targets(p);
        return TARGET_ENEMIES.equals(t) || TARGET_BOTH.equals(t);
    }

    public static boolean markHead(EffectContext p) {
        Boolean b = p.get(EffectKeys.MARK_HEAD);
        return b != null && b;
    }

    public static EffectContext carrierFx(EffectContext p) {
        return p.get(EffectKeys.CARRIER_FX);
    }

    public static EffectContext markFx(EffectContext p) {
        return p.get(EffectKeys.MARK_FX);
    }
}
