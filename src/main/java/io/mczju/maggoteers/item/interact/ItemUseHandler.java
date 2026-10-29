package io.mczju.maggoteers.item.interact;

import com.github.mczjuops.mczjugamecore.game.AbstractGame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** 物品右键处理器（由 {@link io.mczju.maggoteers.item.ItemInteractRouter} 按 {@link io.mczju.maggoteers.item.ItemKind} 分发）。 */
public interface ItemUseHandler {
    void onUse(Player player, AbstractGame game, ItemStack stack);
}
