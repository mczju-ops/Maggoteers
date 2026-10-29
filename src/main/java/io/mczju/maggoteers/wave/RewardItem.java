package io.mczju.maggoteers.wave;

/** 通关奖励项（ItemCreator 物品 id + 数量）；Plan 6 起由 RewardService 发放。 */
public record RewardItem(String itemId, int amount) {}
