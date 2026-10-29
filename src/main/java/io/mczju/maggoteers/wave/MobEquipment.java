package io.mczju.maggoteers.wave;

public record MobEquipment(String slot, String itemId) {
    public MobEquipment {
        slot = slot == null ? "" : slot.trim().toUpperCase();
        itemId = itemId == null ? "" : itemId.trim();
    }
}