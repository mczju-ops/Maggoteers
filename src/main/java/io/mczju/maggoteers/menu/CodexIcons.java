package io.mczju.maggoteers.menu;

import io.mczju.maggoteers.codex.CodexConfig;
import io.mczju.maggoteers.codex.CodexEntries;
import io.mczju.maggoteers.config.MessageService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 图鉴菜单的图标工厂：按钮、边框、三态条目、Boss、词条。
 *
 * <p>「未遇见」态的"未附魔形态"就在这里做：剥掉附魔与光效，并把**风味描述**（{@code <dark_gray>}
 * 那行起）整体替换成一句占位文案。物品 lore 的既有约定是
 * {@code <gold>类型 / <gray>玩法 / <dark_gray>风味}，所以按颜色认风味行；
 * 万一某个物品没有风味行，就追加到最后一行。
 */
final class CodexIcons {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer PLAIN =
            net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText();
    private static final java.util.regex.Pattern STRIP_TAGS = java.util.regex.Pattern.compile("<[^>]*>");
    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger("Maggoteers");

    private CodexIcons() {}

    // ---------------------------------------------------------------- 通用

    static ItemStack pane() {
        ItemStack s = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta m = s.getItemMeta();
        if (m != null) {
            m.displayName(Component.text(" ").decoration(TextDecoration.ITALIC, false));
            s.setItemMeta(m);
        }
        return s;
    }

    /** 单行按钮：名字 + 声明式 lore（loreKey 为 null 时不加 lore）。 */
    static ItemStack button(Material mat, String nameKey, String loreKey) {
        return button(mat, MessageService.component(nameKey, Map.of()), loreKey, Map.of());
    }

    static ItemStack button(Material mat, String nameKey, String loreKey, Map<String, String> ph) {
        return button(mat, MessageService.component(nameKey, ph), loreKey, ph);
    }

    private static ItemStack button(Material mat, Component name, String loreKey, Map<String, String> ph) {
        ItemStack s = new ItemStack(mat);
        ItemMeta m = s.getItemMeta();
        if (m != null) {
            m.displayName(name.decoration(TextDecoration.ITALIC, false));
            if (loreKey != null) {
                m.lore(List.of(MessageService.component(loreKey, ph).decoration(TextDecoration.ITALIC, false)));
            }
            s.setItemMeta(m);
        }
        return s;
    }

    /** 灰掉的按钮（不可点：调用方不挂 action 即可）。 */
    static ItemStack disabled(Material mat, String nameKey, String loreKey) {
        return button(mat, nameKey, loreKey);
    }

    /**
     * MiniMessage 解析；写坏了（未闭合标签等）就退回灰字，别让图鉴打不开。
     *
     * <p>⚠️ MiniMessage **默认不报错**：不认识的标签（如 `<purple>`）会被原样输出成文字。
     * 所以解析完再看一眼纯文本里还有没有 `<xxx>`——有就说明配置写错了，打一条告警方便排查。
     */
    static Component line(String miniMessage) {
        if (miniMessage == null) return Component.empty();
        try {
            Component parsed = MM.deserialize(miniMessage).decoration(TextDecoration.ITALIC, false);
            String plain = PLAIN.serialize(parsed);
            if (plain.indexOf('<') >= 0 && plain.indexOf('>') > plain.indexOf('<')) {
                LOG.warning("codex 文案里有 MiniMessage 不认识的标签（会被原样显示）：" + miniMessage);
            }
            return parsed;
        } catch (RuntimeException ex) {
            LOG.warning("codex 文案解析失败，已按纯文本显示：" + miniMessage + " -> " + ex.getMessage());
            return plain(STRIP_TAGS.matcher(miniMessage).replaceAll(""), NamedTextColor.GRAY);
        }
    }

    static Component plain(String text, NamedTextColor color) {
        return Component.text(text == null ? "" : text, color).decoration(TextDecoration.ITALIC, false);
    }

    // ---------------------------------------------------------------- 武器 / 藏品

    /**
     * 三态条目图标。
     *
     * @param unseenKey 占位文案的 message key（武器/藏品各一句）
     */
    static ItemStack entry(CodexEntries.Kind kind, CodexEntries.Entry entry, CodexEntries.State state,
                           String unseenKey) {
        if (state == CodexEntries.State.LOCKED) {
            String loreKey = kind == CodexEntries.Kind.WEAPON
                    ? "menu.codex.locked_lore_weapon" : "menu.codex.locked_lore_collectible";
            return button(Material.GRAY_CONCRETE, "menu.codex.locked_name", loreKey);
        }
        ItemStack base = CodexEntries.previewIcon(kind, entry)
                .orElseGet(() -> fallbackPaper(entry.display()));
        if (state == CodexEntries.State.OBTAINED) return base;
        return unseen(base, MessageService.raw(unseenKey, Map.of()));
    }

    /** 未获得态的"未附魔形态"：剥附魔 + 去光效 + 换风味行。 */
    static ItemStack unseen(ItemStack base, String unseenLine) {
        ItemStack s = base.clone();
        s.removeEnchantments();
        ItemMeta meta = s.getItemMeta();
        if (meta == null) return s;
        meta.setEnchantmentGlintOverride(false);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        Component placeholder = line(unseenLine);
        int firstFlavor = -1;
        for (int i = 0; i < lore.size(); i++) {
            if (isFlavor(lore.get(i))) {
                firstFlavor = i;
                break;
            }
        }
        if (firstFlavor < 0) {
            lore.add(placeholder);
        } else {
            // 风味描述可能不止一行：整段压成一句占位，避免第二行把线索漏出来
            lore.set(firstFlavor, placeholder);
            for (int i = lore.size() - 1; i > firstFlavor; i--) {
                if (isFlavor(lore.get(i))) lore.remove(i);
            }
        }
        meta.lore(lore);
        s.setItemMeta(meta);
        return s;
    }

    /** 物品 lore 约定：{@code <dark_gray>} 开头的是风味描述。 */
    static boolean isFlavor(Component c) {
        return c != null && NamedTextColor.DARK_GRAY.equals(c.color());
    }

    private static ItemStack fallbackPaper(String display) {
        ItemStack s = new ItemStack(Material.PAPER);
        ItemMeta m = s.getItemMeta();
        if (m != null) {
            m.displayName(plain(display, NamedTextColor.GOLD));
            s.setItemMeta(m);
        }
        return s;
    }

    // ---------------------------------------------------------------- Boss

    /** Boss 图标：见过 = 刷怪蛋 + 玩法/风味/血量；没见过 = 灰混凝土 + "？？？"。 */
    static ItemStack boss(CodexEntries.Boss boss, boolean met) {
        if (!met) {
            return button(Material.GRAY_CONCRETE, "menu.codex.boss_locked_name", "menu.codex.boss_locked_lore");
        }
        ItemStack s = new ItemStack(iconOf(boss.iconKey()));
        ItemMeta m = s.getItemMeta();
        if (m == null) return s;
        m.displayName(line(boss.name()));
        List<Component> lore = new ArrayList<>();
        if (boss.gameplay() != null && !boss.gameplay().isBlank()) lore.add(line(boss.gameplay()));
        if (boss.flavor() != null && !boss.flavor().isBlank()) lore.add(line(boss.flavor()));
        if (boss.hp() > 0) {
            lore.add(MessageService.component("menu.codex.hp", Map.of("hp", String.valueOf(boss.hp())))
                    .decoration(TextDecoration.ITALIC, false));
        }
        if (!lore.isEmpty()) m.lore(lore);
        s.setItemMeta(m);
        return s;
    }

    // ---------------------------------------------------------------- 词条

    static ItemStack affix(CodexEntries.Affix affix) {
        ItemStack s = new ItemStack(iconOf(affix.iconKey()));
        ItemMeta m = s.getItemMeta();
        if (m == null) return s;
        m.displayName(line(affix.name()));
        List<Component> lore = new ArrayList<>();
        if (affix.gameplay() != null && !affix.gameplay().isBlank()) lore.add(line(affix.gameplay()));
        if (affix.flavor() != null && !affix.flavor().isBlank()) lore.add(line(affix.flavor()));
        if (!lore.isEmpty()) m.lore(lore);
        s.setItemMeta(m);
        return s;
    }

    /** 材质名 → Material（解析失败回落 PAPER：图鉴不该因为写错一个图标就打不开）。 */
    static Material iconOf(String iconKey) {
        Material m = CodexConfig.materialQuiet(iconKey);
        return m == null ? Material.PAPER : m;
    }
}
