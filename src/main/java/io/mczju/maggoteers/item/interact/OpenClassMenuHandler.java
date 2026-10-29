package io.mczju.maggoteers.item.interact;

import com.github.mczjuops.mczjugamecore.game.AbstractGame;
import com.github.mczjuops.mczjugamecore.menu.MenuFacade;
import io.mczju.maggoteers.config.MessageService;
import io.mczju.maggoteers.game.ClassSelectGate;
import io.mczju.maggoteers.game.MaggoteersGame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Map;

/** 职业选择券右键 → 打开职业菜单（仅开局选职业阶段、且未选过）。 */
public final class OpenClassMenuHandler implements ItemUseHandler {
    @Override
    public void onUse(Player player, AbstractGame game, ItemStack stack) {
        if (!(game instanceof MaggoteersGame mg) || !mg.isInClassSelectPhase()) {
            player.sendMessage(MessageService.component("item.class_unavailable", Map.of()));
            return;
        }
        if (ClassSelectGate.hasChosen(game, player.getUniqueId())) {
            player.sendMessage(MessageService.component("item.class_already", Map.of()));
            return;
        }
        MenuFacade.open("maggoteers-class", player, game);
    }
}
