package io.mczju.maggoteers.effect;

import java.util.Optional;

public record GrantItemParams(String itemId, int amount) {
    public GrantItemParams {
        if (amount < 1) amount = 1;
    }

    public static Optional<GrantItemParams> parse(EffectContext raw) {
        if (raw == null) return Optional.empty();
        String itemId = raw.get(EffectKeys.ITEM_ID);
        if (itemId == null || itemId.isBlank()) return Optional.empty();
        int amount = 1;
        Integer count = raw.get(EffectKeys.COUNT);
        if (count != null) {
            amount = count;
        } else {
            Double d = raw.get(EffectKeys.AMOUNT);
            if (d != null) amount = Math.max(1, d.intValue());
        }
        if (amount < 1) amount = 1;
        return Optional.of(new GrantItemParams(itemId.trim(), amount));
    }
}
