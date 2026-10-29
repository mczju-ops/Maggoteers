package io.mczju.maggoteers.effect;

import io.mczju.maggoteers.game.MaggoteersGame;
import io.mczju.maggoteers.item.ItemService;
import io.mczju.maggoteers.item.RunItemTags;
import io.mczju.maggoteers.state.PlayerState;
import io.mczju.maggoteers.state.PlayerStateManager;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

/** Sync/resync/remove bound equipment views (chest slot + inventory orphan cleanup). */
public final class BoundEquipService {
    private static final Logger LOG = Logger.getLogger("Maggoteers");

    /** {@link org.bukkit.inventory.PlayerInventory} index for equipped chestplate (Paper 26.2). */
    static final int CHESTPLATE_ARMOR_INDEX = 38;

    private BoundEquipService() {}

    /**
     * Orphan sweep indices: storage, hotbar, and armor slots except the equipped chest.
     * The chest slot is managed explicitly by {@link #sync} / {@link #remove}, not orphan cleanup.
     */
    static boolean isOrphanSweepIndex(int inventoryIndex) {
        return inventoryIndex != CHESTPLATE_ARMOR_INDEX;
    }

    public static boolean isConflictToDestroy(boolean occupied, boolean sameRewardBound) {
        return occupied && !sameRewardBound;
    }

    public static void sync(Player player, PlayerEffect pe) {
        if (player == null || pe == null || pe.effect() != Effect.BOUND_EQUIP) return;

        String rewardId = pe.id();
        int level = pe.level();
        ItemStack chest = player.getInventory().getChestplate();
        boolean occupied = chest != null && !chest.getType().isAir();
        boolean sameRewardBound = RunItemTags.isBoundEquipFor(chest, rewardId);

        if (isConflictToDestroy(occupied, sameRewardBound)) {
            player.getInventory().setChestplate(null);
        }

        removeInventoryOrphans(player, rewardId);

        if (sameRewardBound && RunItemTags.readRewardLevel(chest) == level) {
            return;
        }

        Optional<String> itemId = BoundEquipParams.itemIdForLevel(pe.params(), level);
        if (itemId.isEmpty()) {
            LOG.warning("BoundEquipService.sync: no item for " + rewardId + " level " + level);
            return;
        }
        Optional<ItemStack> created = ItemService.createItem(itemId.get(), 1);
        if (created.isEmpty()) {
            LOG.warning("BoundEquipService.sync: ItemCreator missing " + itemId.get());
            return;
        }
        ItemStack stack = created.get();
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            RunItemTags.setBoundEquipPdc(meta, rewardId, level);
            stack.setItemMeta(meta);
        }
        player.getInventory().setChestplate(stack);
    }

    public static void resync(Player player, MaggoteersGame game) {
        if (player == null || game == null) return;
        PlayerState ps = PlayerStateManager.get(game, player.getUniqueId());
        if (ps == null) return;

        Set<String> keepIds = new HashSet<>();
        for (PlayerEffect pe : ps.effects()) {
            if (pe.effect() != Effect.BOUND_EQUIP) continue;
            keepIds.add(pe.id());
            sync(player, pe);
        }

        for (ItemStack stack : allInventoryStacks(player)) {
            if (!RunItemTags.isBoundEquip(stack)) continue;
            RunItemTags.readRewardId(stack).ifPresent(rid -> {
                if (!keepIds.contains(rid)) {
                    remove(player, rid);
                }
            });
        }
    }

    public static int remove(Player player, String rewardId) {
        if (player == null || rewardId == null) return 0;
        int removed = 0;
        ItemStack chest = player.getInventory().getChestplate();
        if (RunItemTags.isBoundEquipFor(chest, rewardId)) {
            player.getInventory().setChestplate(null);
            removed++;
        }
        removed += removeInventoryOrphans(player, rewardId);
        return removed;
    }

    private static int removeInventoryOrphans(Player player, String rewardId) {
        int removed = 0;
        var inv = player.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            if (!isOrphanSweepIndex(i)) continue;
            ItemStack stack = inv.getItem(i);
            if (!RunItemTags.isBoundEquipFor(stack, rewardId)) continue;
            inv.setItem(i, null);
            removed++;
        }
        ItemStack off = inv.getItemInOffHand();
        if (RunItemTags.isBoundEquipFor(off, rewardId)) {
            inv.setItemInOffHand(null);
            removed++;
        }
        return removed;
    }

    private static Iterable<ItemStack> allInventoryStacks(Player player) {
        java.util.List<ItemStack> out = new java.util.ArrayList<>();
        for (ItemStack s : player.getInventory().getContents()) {
            if (s != null && !s.getType().isAir()) out.add(s);
        }
        ItemStack chest = player.getInventory().getChestplate();
        if (chest != null && !chest.getType().isAir()) out.add(chest);
        ItemStack off = player.getInventory().getItemInOffHand();
        if (off != null && !off.getType().isAir()) out.add(off);
        return out;
    }
}