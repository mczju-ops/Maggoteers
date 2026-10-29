package io.mczju.maggoteers.effect;

/** One parsed step inside use_ability (immediate or deferred). */
public record AbilityStep(
        Effect effect,
        EffectContext params,
        Trigger expiryTrigger,
        int expiryCharges,
        Stack stack
) {
    /**
     * 2026-10-28：无 expiry 的 {@code ADD_ATTRIBUTE} 也算"延期"——它要写成常驻 PlayerEffect（永久代价，
     * 如老魔杖每次使用 -8 生命上限），而不是"即时生效不支持"。
     */
    public boolean isDeferred() {
        return expiryTrigger != null || effect == Effect.ADD_ATTRIBUTE;
    }
}
