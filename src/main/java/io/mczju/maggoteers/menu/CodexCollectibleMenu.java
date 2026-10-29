package io.mczju.maggoteers.menu;

import io.mczju.maggoteers.codex.CodexEntries;
import org.bukkit.entity.Player;

/** 藏品图鉴（独立菜单：只收有护符映射的 {@code category: STAT} 奖励项）。 */
public class CodexCollectibleMenu extends CodexPageMenu {

    public CodexCollectibleMenu(Player player, Object... args) {
        super(player, args);
    }

    @Override protected CodexEntries.Kind kind() { return CodexEntries.Kind.COLLECTIBLE; }
    @Override protected String menuId() { return "maggoteers-codex-collectible"; }
    @Override protected String titleKey() { return "menu.codex.collectible_title"; }
}
