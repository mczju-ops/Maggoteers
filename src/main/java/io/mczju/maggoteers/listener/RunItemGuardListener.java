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
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;

public final class RunItemGuardListener implements Listener {

    private static boolean dropProtectionEnabled() {
        return MaggoteersPlugin.getInstance().getConfig().getBoolean("run_items.drop_protection", true);
    }

    private static boolean inRun(Player player) {
        return new PlayerExt(player).isInGame(MaggoteersGame.class);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (!dropProtectionEnabled()) return;
        if (!inRun(event.getPlayer())) return;
        if (RunItemTags.isRunItem(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!dropProtectionEnabled()) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!inRun(player)) return;
        if (isBlockedMove(event.getCurrentItem(), event.getCursor(), event.getAction())) {
            event.setCancelled(true);
            return;
        }
        if (event.getClickedInventory() != null
                && event.getClickedInventory() != player.getInventory()
                && (RunItemTags.isRunItem(event.getCurrentItem()) || RunItemTags.isRunItem(event.getCursor()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!dropProtectionEnabled()) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!inRun(player)) return;
        var view = event.getView();
        for (int raw : event.getRawSlots()) {
            if (view.getInventory(raw) == player.getInventory()) continue;
            for (ItemStack stack : event.getNewItems().values()) {
                if (RunItemTags.isRunItem(stack)) {
                    event.setCancelled(true);
                    return;
                }
            }
        }
    }

    private static boolean isBlockedMove(ItemStack current, ItemStack cursor, InventoryAction action) {
        if (action == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            return RunItemTags.isRunItem(current);
        }
        return false;
    }
}
