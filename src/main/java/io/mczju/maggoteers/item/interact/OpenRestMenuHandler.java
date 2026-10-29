package io.mczju.maggoteers.item.interact;

import com.github.mczjuops.mczjugamecore.game.AbstractGame;
import com.github.mczjuops.mczjugamecore.menu.MenuFacade;
import io.mczju.maggoteers.config.MessageService;
import io.mczju.maggoteers.wave.WaveScheduler;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Map;

/** 普通/Boss 货币右键 → 打开休整升级菜单。
 * 2026-10-27：**随时可开**（战斗中也能看数值），商店/跳过由 {@code RestMenu} 按休整相位灰显。 */
public final class OpenRestMenuHandler implements ItemUseHandler {
    @Override
    public void onUse(Player player, AbstractGame game, ItemStack stack) {
        MenuFacade.open("maggoteers-rest", player, game);
    }
}
