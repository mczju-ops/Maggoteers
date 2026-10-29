package io.mczju.maggoteers.effect;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class BoundEquipParams {
    public static final String SLOT_CHEST = "CHEST";

    private BoundEquipParams() {}

    public static Optional<String> validate(EffectContext params, int upgradeMax) {
        if (params == null) return Optional.of("BOUND_EQUIP missing params");
        String slot = params.get(EffectKeys.SLOT);
        if (slot == null || !SLOT_CHEST.equals(slot.toUpperCase(Locale.ROOT))) {
            return Optional.of("BOUND_EQUIP slot must be CHEST (v1)");
        }
        if (upgradeMax <= 0) return Optional.of("BOUND_EQUIP requires upgrade_max > 0");
        Map<Integer, String> byLevel = params.get(EffectKeys.ITEMS_BY_LEVEL);
        if (byLevel == null || byLevel.isEmpty()) {
            return Optional.of("BOUND_EQUIP missing items_by_level");
        }
        for (int i = 1; i <= upgradeMax; i++) {
            String id = byLevel.get(i);
            if (id == null || id.isBlank()) {
                return Optional.of("BOUND_EQUIP missing items_by_level." + i);
            }
        }
        return Optional.empty();
    }

    public static Optional<String> itemIdForLevel(EffectContext params, int level) {
        if (params == null || level < 1) return Optional.empty();
        Map<Integer, String> byLevel = params.get(EffectKeys.ITEMS_BY_LEVEL);
        if (byLevel == null) return Optional.empty();
        String id = byLevel.get(level);
        return id == null || id.isBlank() ? Optional.empty() : Optional.of(id);
    }

    public static int previewLevel(Integer ownedLevelOrNull, int upgradeMax) {
        int max = Math.max(1, upgradeMax);
        if (ownedLevelOrNull == null || ownedLevelOrNull < 1) return 1;
        return Math.min(ownedLevelOrNull + 1, max);
    }
}