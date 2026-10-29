package io.mczju.maggoteers.listener;

import com.github.mczjuops.mczjugamecore.player.PlayerExt;
import io.mczju.maggoteers.MaggoteersPlugin;
import io.mczju.maggoteers.game.MaggoteersGame;
import io.mczju.maggoteers.item.RunItemTags;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Blocks in-run inventory moves for {@code kind=bound_equip} chestpieces (spec 5.3).
 * Drop / foreign-container rules stay in {@link RunItemGuardListener}.
 */
public final class BoundEquipLockListener implements Listener {

    private static boolean protectionEnabled() {
        return MaggoteersPlugin.getInstance().getConfig().getBoolean("run_items.drop_protection", true);
    }

    private static boolean inRun(Player player) {
        return new PlayerExt(player).isInGame(MaggoteersGame.class);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!protectionEnabled()) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!inRun(player)) return;
        if (shouldBlockClick(event, player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!protectionEnabled()) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!inRun(player)) return;
        if (shouldBlockDrag(event, player)) {
            event.setCancelled(true);
        }
    }

    static boolean shouldBlockClick(InventoryClickEvent event, Player player) {
        if (!isMoveAction(event.getAction())) {
            return false;
        }
        if (RunItemTags.isBoundEquip(event.getCurrentItem()) || RunItemTags.isBoundEquip(event.getCursor())) {
            return true;
        }
        if (!RunItemTags.isBoundEquip(player.getInventory().getChestplate())) {
            return false;
        }
        if (isPlayerChestArmorSlot(event.getView(), event.getRawSlot(), player)) {
            return true;
        }
        if (event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY && isChestplateStack(event.getCurrentItem())) {
            return true;
        }
        return false;
    }

    static boolean shouldBlockDrag(InventoryDragEvent event, Player player) {
        for (ItemStack stack : event.getNewItems().values()) {
            if (RunItemTags.isBoundEquip(stack)) {
                return true;
            }
        }
        if (!RunItemTags.isBoundEquip(player.getInventory().getChestplate())) {
            return false;
        }
        InventoryView view = event.getView();
        for (int rawSlot : event.getRawSlots()) {
            if (isPlayerChestArmorSlot(view, rawSlot, player)) {
                return true;
            }
        }
        return false;
    }

    static boolean isMoveAction(InventoryAction action) {
        return action != InventoryAction.NOTHING;
    }

    /**
     * Paper 26.2: player chest armor is {@link InventoryType.SlotType#ARMOR} in the player
     * {@link InventoryView}, mapping to player-inventory index 38 (chestplate; see
     * {@link PlayerInventory#setItem(int, ItemStack)}) through {@link InventoryView#convertSlot(int)}.
     * Raw slot ids differ by view (e.g. raw 6 in CRAFTING); convertSlot is the stable check.
     */
    static boolean isPlayerChestArmorSlot(InventoryView view, int rawSlot, Player player) {
        if (view.getSlotType(rawSlot) != InventoryType.SlotType.ARMOR) {
            return false;
        }
        Inventory inventory = view.getInventory(rawSlot);
        if (inventory != player.getInventory()) {
            return false;
        }
        return view.convertSlot(rawSlot) == 38;
    }

    private static boolean isChestplateStack(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return false;
        }
        return stack.getType().name().endsWith("_CHESTPLATE");
    }
}