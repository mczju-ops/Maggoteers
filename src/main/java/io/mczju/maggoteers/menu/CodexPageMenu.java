package io.mczju.maggoteers.menu;

import com.github.mczjuops.mczjugamecore.menu.Menu;
import com.github.mczjuops.mczjugamecore.menu.MenuFacade;
import io.mczju.maggoteers.codex.CodexEntries;
import io.mczju.maggoteers.codex.CodexLayout;
import io.mczju.maggoteers.codex.CodexProgress;
import io.mczju.maggoteers.config.MessageService;
import io.mczju.maggoteers.persist.MaggoteersPlayerData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 武器图鉴 / 藏品图鉴的**共用翻页骨架**（5×9）。
 *
 * <p>版式：第 1 行第 5 格 = 本页池子 + 进度；第 2–4 行第 2–8 格 = 21 件条目；
 * 第 5 行第 2 / 第 8 格 = 上一页 / 下一页，第 5 行第 9 格 = 返回。
 *
 * <p>一个池子的藏品可能有 30+ 件，21 格装不下 → **页内续屏**（同一池子再翻一屏，标题带 `1/2`）。
 *
 * <p>两个子类 {@link CodexWeaponMenu}、{@link CodexCollectibleMenu} 是**两个独立菜单**
 * （不同 MenuFacade id、不同标题、只显示自己那一类条目）——不要再合成一个。
 */
public abstract class CodexPageMenu extends Menu {

    private final int requestedScreen;

    protected CodexPageMenu(Player player, Object... args) {
        super(player, args);
        this.requestedScreen = args.length > 0 && args[0] instanceof Number n ? n.intValue() : 0;
    }

    /** 这本书收哪一类条目。 */
    protected abstract CodexEntries.Kind kind();

    /** 本菜单的 MenuFacade id（翻页时重开自己）。 */
    protected abstract String menuId();

    /** 标题文案 key。 */
    protected abstract String titleKey();

    /** 标题必须在 super 构造里就能取到 → 只读子类的常量方法，不碰字段。 */
    @Override protected String getTitle() {
        return MessageService.raw(titleKey(), Map.of());
    }
    @Override protected int getRows() { return CodexLayout.SUB_ROWS; }
    @Override protected String getPermission() { return "maggoteers.play"; }

    @Override
    protected void setup() {
        Player p = player.player();
        for (int slot = 0; slot < CodexLayout.SUB_ROWS * CodexLayout.COLUMNS; slot++) {
            setSlot(slot, CodexIcons.pane());
        }

        CodexEntries.Kind kind = kind();
        MaggoteersPlayerData data = CodexProgress.dataOf(p);
        Set<String> unlocked = unlockedOf(data);
        Set<String> obtained = kind == CodexEntries.Kind.COLLECTIBLE
                ? CodexProgress.obtainedCollectibles(data)
                : CodexProgress.obtainedWeapons(data);
        List<CodexEntries.Section> sections = CodexEntries.sections(kind);
        List<CodexEntries.Screen> screens = CodexEntries.screens(sections, CodexEntries.PER_SCREEN);
        int index = CodexEntries.clampIndex(requestedScreen, screens.size());

        if (!screens.isEmpty()) {
            CodexEntries.Screen screen = screens.get(index);
            setSlot(CodexLayout.PAGE_HEADER, header(screen, screens.size(), index, obtained));
            String unseenKey = kind == CodexEntries.Kind.COLLECTIBLE
                    ? "menu.codex.unseen_collectible" : "menu.codex.unseen_weapon";
            List<CodexEntries.Entry> entries = screen.entries();
            for (int i = 0; i < entries.size() && i < CodexLayout.PAGE_ENTRY_SLOTS.length; i++) {
                CodexEntries.Entry entry = entries.get(i);
                CodexEntries.State state = CodexEntries.stateOf(
                        entry.requiresUnlock(), unlocked.contains(entry.optionId()),
                        obtained.contains(entry.optionId()));
                setSlot(CodexLayout.PAGE_ENTRY_SLOTS[i], CodexIcons.entry(kind, entry, state, unseenKey));
            }
            setupPaging(p, index, screens.size());
        }
        setSlot(CodexLayout.SUB_BACK, CodexIcons.button(Material.RED_CONCRETE,
                        "menu.codex.back_name", "menu.codex.back_lore"),
                (c, e) -> MenuFacade.open("maggoteers-codex", p));
    }

    private void setupPaging(Player p, int index, int total) {
        if (index > 0) {
            setSlot(CodexLayout.PAGE_PREV, CodexIcons.button(Material.FEATHER,
                            "menu.codex.prev_name", "menu.codex.prev_lore"),
                    (c, e) -> openScreen(p, index - 1));
        } else {
            setSlot(CodexLayout.PAGE_PREV, CodexIcons.disabled(Material.FEATHER,
                    "menu.codex.prev_name", "menu.codex.page_edge"));
        }
        if (index + 1 < total) {
            setSlot(CodexLayout.PAGE_NEXT, CodexIcons.button(Material.FEATHER,
                            "menu.codex.next_name", "menu.codex.next_lore"),
                    (c, e) -> openScreen(p, index + 1));
        } else {
            setSlot(CodexLayout.PAGE_NEXT, CodexIcons.disabled(Material.FEATHER,
                    "menu.codex.next_name", "menu.codex.page_edge"));
        }
    }

    private void openScreen(Player p, int index) {
        MenuFacade.open(menuId(), p, index);
    }

    /** 页脚中间：本页读的是哪个池子 + 该页收集进度 + 全书页码。 */
    private ItemStack header(CodexEntries.Screen screen, int totalScreens, int index,
                             Set<String> obtained) {
        CodexEntries.Section section = screen.section();
        ItemStack stack = new ItemStack(Material.CHEST);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;

        Component name = CodexIcons.line(section.title());
        if (screen.pagesInSection() > 1) {
            name = name.append(CodexIcons.plain(" " + screen.pageNo() + "/" + screen.pagesInSection(),
                    NamedTextColor.DARK_GRAY));
        }
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        lore.add(MessageService.component("menu.codex.page_progress", Map.of(
                        "got", String.valueOf(CodexEntries.obtainedCount(List.of(section), obtained)),
                        "total", String.valueOf(section.entries().size())))
                .decoration(TextDecoration.ITALIC, false));
        if (totalScreens > 1) {
            lore.add(MessageService.component("menu.codex.page_index", Map.of(
                            "page", String.valueOf(index + 1),
                            "pages", String.valueOf(totalScreens)))
                    .decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private static Set<String> unlockedOf(MaggoteersPlayerData data) {
        if (data == null || data.unlocks == null) return Set.of();
        return Set.copyOf(data.unlocks);
    }
}
