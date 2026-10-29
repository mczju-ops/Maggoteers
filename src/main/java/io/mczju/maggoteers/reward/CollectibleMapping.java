package io.mczju.maggoteers.reward;

import java.util.Map;
import java.util.Optional;

public record CollectibleMapping(
        String rewardId,
        String singleItem,
        Map<Integer, String> itemsByLevel
) {
    public Optional<String> itemIdForLevel(int level) {
        if (itemsByLevel != null && !itemsByLevel.isEmpty()) {
            return Optional.ofNullable(itemsByLevel.get(level));
        }
        return singleItem == null || singleItem.isBlank() ? Optional.empty() : Optional.of(singleItem);
    }

    public boolean isLeveled() {
        return itemsByLevel != null && !itemsByLevel.isEmpty();
    }
}
