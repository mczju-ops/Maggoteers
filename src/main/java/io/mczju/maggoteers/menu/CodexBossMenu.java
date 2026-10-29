package io.mczju.maggoteers.menu;

import com.github.mczjuops.mczjugamecore.menu.Menu;
import com.github.mczjuops.mczjugamecore.menu.MenuFacade;
import io.mczju.maggoteers.codex.CodexEntries;
import io.mczju.maggoteers.codex.CodexLayout;
import io.mczju.maggoteers.codex.CodexProgress;
import io.mczju.maggoteers.config.MessageService;
import io.mczju.maggoteers.persist.MaggoteersPlayerData;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Boss 图鉴（5×9）。
 *
 * <p>布局（用户口径）：第 1–4 行的**第 1 格标注层数**，第 2–9 格摆该层关底的 Boss
 * （同一关的 Boss 紧挨着，不同关各占一行）。最后一行第 9 格 = 返回。
 *
 * <p>没进过隐藏层（Act4）时，那一行整行留空——不是"显示 ？？？"，是压根不画。
 * 没遭遇过的 Boss 显示灰混凝土 + ？？？。
 */
public class CodexBossMenu extends Menu {

    private static final int SLOT_BACK = CodexLayout.SUB_BACK;
    private static final int MAX_ROWS = CodexLayout.BOSS_MAX_ROWS;
    private static final int MAX_BOSSES_PER_ROW = CodexLayout.BOSS_SLOTS_PER_ROW;

    public CodexBossMenu(Player player, Object... args) {
        super(player, args);
    }

    @Override protected String getTitle() {
        return MessageService.raw("menu.codex.boss_title", Map.of());
    }
    @Override protected int getRows() { return CodexLayout.SUB_ROWS; }
    @Override protected String getPermission() { return "maggoteers.play"; }

    @Override
    protected void setup() {
        Player p = player.player();
        for (int slot = 0; slot < CodexLayout.SUB_ROWS * CodexLayout.COLUMNS; slot++) {
            setSlot(slot, CodexIcons.pane());
        }

        MaggoteersPlayerData data = CodexProgress.dataOf(p);
        Set<String> met = CodexProgress.metBosses(data);
        List<CodexEntries.BossRow> rows = CodexEntries.bossRows(CodexProgress.visitedActs(data));

        for (int r = 0; r < Math.min(MAX_ROWS, rows.size()); r++) {
            CodexEntries.BossRow row = rows.get(r);
            int base = CodexLayout.slot(r, CodexLayout.BOSS_LABEL_OFFSET);
            setSlot(base, actLabel(row));
            List<CodexEntries.Boss> bosses = row.bosses();
            for (int c = 0; c < Math.min(MAX_BOSSES_PER_ROW, bosses.size()); c++) {
                CodexEntries.Boss boss = bosses.get(c);
                setSlot(base + 1 + c, CodexIcons.boss(boss, met.contains(boss.strategyId())));
            }
        }
        setSlot(SLOT_BACK, CodexIcons.button(Material.RED_CONCRETE,
                        "menu.codex.back_name", "menu.codex.back_lore"),
                (c, e) -> MenuFacade.open("maggoteers-codex", p));
    }

    /** 每行第 1 格：层数（lore 里带层 id，方便对配置）。 */
    private static ItemStack actLabel(CodexEntries.BossRow row) {
        ItemStack stack = new ItemStack(Material.BOOK);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;
        meta.displayName(MessageService.component("menu.codex.act_row", Map.of(
                        "act", String.valueOf(row.actNo())))
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(MessageService.component("menu.codex.act_row_lore", Map.of(
                        "id", row.actId()))
                .decoration(TextDecoration.ITALIC, false)));
        stack.setItemMeta(meta);
        return stack;
    }
}
