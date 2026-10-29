package io.mczju.maggoteers.menu;

import com.github.mczjuops.mczjugamecore.menu.Menu;
import com.github.mczjuops.mczjugamecore.menu.MenuFacade;
import io.mczju.maggoteers.codex.CodexEntries;
import io.mczju.maggoteers.codex.CodexLayout;
import io.mczju.maggoteers.codex.CodexProgress;
import io.mczju.maggoteers.config.MessageService;
import io.mczju.maggoteers.persist.MaggoteersPlayerData;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 图鉴一级菜单（3×9）。
 *
 * <p>布局（用户口径）：第二行第 2/4/6/8 格 = 武器 / 藏品 / Boss / 词条；第三行右下角 = 退出。
 */
public class CodexMenu extends Menu {

    private static final int SLOT_WEAPON = CodexLayout.MAIN_WEAPON;
    private static final int SLOT_COLLECTIBLE = CodexLayout.MAIN_COLLECTIBLE;
    private static final int SLOT_BOSS = CodexLayout.MAIN_BOSS;
    private static final int SLOT_AFFIX = CodexLayout.MAIN_AFFIX;
    private static final int SLOT_EXIT = CodexLayout.MAIN_EXIT;

    public CodexMenu(Player player, Object... args) {
        super(player, args);
    }

    @Override protected String getTitle() {
        return MessageService.raw("menu.codex.title", Map.of());
    }
    @Override protected int getRows() { return CodexLayout.MAIN_ROWS; }
    @Override protected String getPermission() { return "maggoteers.play"; }

    @Override
    protected void setup() {
        Player p = player.player();
        for (int slot = 0; slot < CodexLayout.MAIN_ROWS * CodexLayout.COLUMNS; slot++) {
            setSlot(slot, CodexIcons.pane());
        }

        MaggoteersPlayerData data = CodexProgress.dataOf(p);
        List<CodexEntries.Section> weapons = CodexEntries.sections(CodexEntries.Kind.WEAPON);
        List<CodexEntries.Section> collectibles = CodexEntries.sections(CodexEntries.Kind.COLLECTIBLE);
        int weaponGot = CodexEntries.obtainedCount(weapons, CodexProgress.obtainedWeapons(data));
        int collectibleGot = CodexEntries.obtainedCount(collectibles, CodexProgress.obtainedCollectibles(data));
        List<CodexEntries.BossRow> bossRows = CodexEntries.bossRows(CodexProgress.visitedActs(data));
        int bossTotal = 0;
        int bossMet = 0;
        Set<String> met = CodexProgress.metBosses(data);
        for (CodexEntries.BossRow row : bossRows) {
            bossTotal += row.bosses().size();
            for (CodexEntries.Boss boss : row.bosses()) {
                if (met.contains(boss.strategyId())) bossMet++;
            }
        }
        int affixTotal = CodexEntries.affixes().size();

        setSlot(SLOT_WEAPON, CodexIcons.button(Material.IRON_SWORD,
                        "menu.codex.weapon_name", "menu.codex.weapon_lore", Map.of(
                                "got", String.valueOf(weaponGot),
                                "total", String.valueOf(CodexEntries.entryCount(weapons)))),
                (c, e) -> MenuFacade.open("maggoteers-codex-weapon", p, 0));
        setSlot(SLOT_COLLECTIBLE, CodexIcons.button(Material.RECOVERY_COMPASS,
                        "menu.codex.collectible_name", "menu.codex.collectible_lore", Map.of(
                                "got", String.valueOf(collectibleGot),
                                "total", String.valueOf(CodexEntries.entryCount(collectibles)))),
                (c, e) -> MenuFacade.open("maggoteers-codex-collectible", p, 0));
        setSlot(SLOT_BOSS, CodexIcons.button(Material.WITHER_SKELETON_SKULL,
                        "menu.codex.boss_name", "menu.codex.boss_lore", Map.of(
                                "got", String.valueOf(bossMet),
                                "total", String.valueOf(bossTotal))),
                (c, e) -> MenuFacade.open("maggoteers-codex-boss", p));
        setSlot(SLOT_AFFIX, CodexIcons.button(Material.ENCHANTED_BOOK,
                        "menu.codex.affix_name", "menu.codex.affix_lore", Map.of(
                                "total", String.valueOf(affixTotal))),
                (c, e) -> MenuFacade.open("maggoteers-codex-affix", p));
        setSlot(SLOT_EXIT, CodexIcons.button(Material.RED_CONCRETE,
                        "menu.codex.exit_name", "menu.codex.exit_lore"),
                (c, e) -> p.closeInventory());
    }
}
