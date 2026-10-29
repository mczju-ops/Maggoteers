package io.mczju.maggoteers.effect;

import java.util.Optional;

/** Ids and pack membership for weapon deferred / temp ability PlayerEffects. */
public final class WeaponAbilityIds {
    public static final String PREFIX = "ability:";

    private WeaponAbilityIds() {}

    public static String effectId(String itemId) {
        return PREFIX + itemId;
    }

    public static String effectId(String itemId, int stepIndex) {
        return PREFIX + itemId + ":" + stepIndex;
    }

    public static Optional<String> itemIdOf(String effectId) {
        if (effectId == null || !effectId.startsWith(PREFIX)) return Optional.empty();
        String rest = effectId.substring(PREFIX.length());
        int lastColon = rest.lastIndexOf(":");
        if (lastColon > 0) {
            String maybeIdx = rest.substring(lastColon + 1);
            if (!maybeIdx.isEmpty() && maybeIdx.chars().allMatch(Character::isDigit)) {
                String withoutIdx = rest.substring(0, lastColon);
                if (withoutIdx.indexOf(":") >= 0) {
                    return Optional.of(withoutIdx);
                }
            }
        }
        return Optional.of(rest);
    }
}
