package io.mczju.maggoteers.reward;

import java.util.List;

public record RewardPool(String id, int cost, int upgradeLevelCap, List<RewardOption> options) {
    public RewardPool {
        options = List.copyOf(options);
    }
}
