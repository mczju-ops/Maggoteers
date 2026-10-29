package io.mczju.maggoteers.menu;

import com.github.mczjuops.mczjugamecore.menu.Menu;
import com.github.mczjuops.mczjugamecore.menu.MenuFacade;
import io.mczju.maggoteers.codex.CodexEntries;
import io.mczju.maggoteers.codex.CodexLayout;
import io.mczju.maggoteers.config.MessageService;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;

/**
 * 词条图鉴（5×9）：只有 {@code codex.yml affixes} 白名单里的词条才会出现。
 *
 * <p>布局（用户口径）：第 2–4 行第 2–8 格摆词条（21 格）；最后一行第 9 格 = 返回。
 * 白名单条目多于 21 条时，超出的部分不显示（词条是精选内容，不做翻页）。
 */
public class CodexAffixMenu extends Menu {

    private static final int[] SLOTS = CodexLayout.AFFIX_SLOTS;
    private static final int SLOT_BACK = CodexLayout.SUB_BACK;

    public CodexAffixMenu(Player player, Object... args) {
        super(player, args);
    }

    @Override protected String getTitle() {
        return MessageService.raw("menu.codex.affix_title", Map.of());
    }
    @Override protected int getRows() { return CodexLayout.SUB_ROWS; }
    @Override protected String getPermission() { return "maggoteers.play"; }

    @Override
    protected void setup() {
        Player p = player.player();
        for (int slot = 0; slot < CodexLayout.SUB_ROWS * CodexLayout.COLUMNS; slot++) {
            setSlot(slot, CodexIcons.pane());
        }

        List<CodexEntries.Affix> affixes = CodexEntries.affixes();
        for (int i = 0; i < affixes.size() && i < SLOTS.length; i++) {
            setSlot(SLOTS[i], CodexIcons.affix(affixes.get(i)));
        }
        setSlot(SLOT_BACK, CodexIcons.button(Material.RED_CONCRETE,
                        "menu.codex.back_name", "menu.codex.back_lore"),
                (c, e) -> MenuFacade.open("maggoteers-codex", p));
    }
}
