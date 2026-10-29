package io.mczju.maggoteers.item;

import io.mczju.maggoteers.MaggoteersPlugin;
import io.mczju.maggoteers.effect.WeaponHeldService;
import io.mczju.maggoteers.game.MaggoteersGame;
import io.mczju.maggoteers.state.PlayerStateManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;

/** Keeps {@code held:*} weapon passives in sync with main-hand PDC. */
public final class WeaponHeldListener implements Listener {

    private final MaggoteersPlugin plugin;

    public WeaponHeldListener(MaggoteersPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeld(PlayerItemHeldEvent e) {
        // ⚠️ PlayerItemHeldEvent 触发时 getItemInMainHand() 仍是旧槽位物品（新槽位在事件处理后才应用），
        // 同步 sync 会挂载「上一把武器」的 held_effects，导致效果比实际手持慢一个槽位
        // （A→B 上凋零 / B→C 上缓慢 / C→B 无效果，bug #52）。延迟 1 tick 等槽位切换完成后才读主手。
        Bukkit.getScheduler().runTask(plugin, () -> syncIfInGame(e.getPlayer()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        Bukkit.getScheduler().runTask(plugin, () -> syncIfInGame(p));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        Bukkit.getScheduler().runTask(plugin, () -> syncIfInGame(e.getPlayer()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        Bukkit.getScheduler().runTask(plugin, () -> syncIfInGame(e.getPlayer()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Bukkit.getScheduler().runTask(plugin, () -> syncIfInGame(p));
    }

    private void syncIfInGame(Player p) {
        MaggoteersGame game = PlayerStateManager.gameOf(p);
        if (game == null) return;
        WeaponHeldService.sync(p, game);
    }
}
