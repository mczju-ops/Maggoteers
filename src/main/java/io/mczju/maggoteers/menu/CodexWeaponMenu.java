package io.mczju.maggoteers.menu;

import io.mczju.maggoteers.codex.CodexEntries;
import org.bukkit.entity.Player;

/** 武器图鉴（独立菜单：只收 {@code category: WEAPON} 的奖励项）。 */
public class CodexWeaponMenu extends CodexPageMenu {

    public CodexWeaponMenu(Player player, Object... args) {
        super(player, args);
    }

    @Override protected CodexEntries.Kind kind() { return CodexEntries.Kind.WEAPON; }
    @Override protected String menuId() { return "maggoteers-codex-weapon"; }
    @Override protected String titleKey() { return "menu.codex.weapon_title"; }
}
