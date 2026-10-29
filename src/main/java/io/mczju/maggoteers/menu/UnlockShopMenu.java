package io.mczju.maggoteers.menu;

import com.github.mczjuops.mczjugamecore.menu.Menu;
import com.github.mczjuops.mczjugamecore.menu.MenuFacade;
import com.github.mczjuops.mczjugamecore.player.PlayerExt;
import io.mczju.maggoteers.config.MessageService;
import io.mczju.maggoteers.persist.MaggoteersPlayerData;
import io.mczju.maggoteers.reward.RewardOption;
import io.mczju.maggoteers.reward.RewardOptionIcons;
import io.mczju.maggoteers.reward.RewardService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 局外解锁商店（§13.3）：商品 = 所有池里 requires_unlock:true 的选项（按 id 去重）。
 * 点击：balance >= unlock_cost → 扣 balance、unlocks.add(id)。
 */
public class UnlockShopMenu extends Menu {

    /**
     * 每页商品数：第 2–5 行、每行第 2–8 格（跳过最右一列）= 7 × 4 = **28**。
     *
     * <p>2026-10-30 实测溢出：可解锁项共 33 个，旧代码在 `slot > 43` 处直接 break，
     * **最后 5 个被静默吞掉**（玩家永远买不到）。所以加翻页：左下/右下各一个羽毛键。
     */
    private static final int PER_PAGE = 28;
    private static final int SLOT_PREV = 45;   // 第 6 行第 1 格
    private static final int SLOT_NEXT = 53;   // 第 6 行第 9 格

    /** 当前页（0 基）；由 args 传入，翻页后重新打开。 */
    private final int requestedPage;

    public UnlockShopMenu(Player player, Object... args) {
        super(player, args);
        this.requestedPage = args.length > 0 && args[0] instanceof Number n ? n.intValue() : 0;
    }

    @Override protected String getTitle() {
        return MessageService.raw("menu.shop.title", Map.of());
    }
    @Override protected int getRows() { return 6; }
    @Override protected String getPermission() { return "maggoteers.play"; }

    @Override
    protected void setup() {
        Player p = player.player();
        var pe = new PlayerExt(p);
        var data = pe.getData(MaggoteersPlayerData.class);
        // 顶部中央只读余额槽（购买后菜单 reopen 自动刷新，无需实时更新）
        int balance = data == null ? 0 : data.balance;
        setSlot(4, balanceIcon(balance));
        Map<String, RewardOption> byId = new LinkedHashMap<>();
        for (String poolId : RewardService.allPoolIds()) {
            var pool = RewardService.pool(poolId);
            if (pool == null) continue;
            for (RewardOption o : pool.options()) {
                if (!o.requiresUnlock()) continue;
                byId.putIfAbsent(o.id(), o);
            }
        }
        List<RewardOption> goods = new ArrayList<>(byId.values());
        int pages = Math.max(1, (goods.size() + PER_PAGE - 1) / PER_PAGE);
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        int from = page * PER_PAGE;
        int to = Math.min(goods.size(), from + PER_PAGE);

        int slot = 10;
        for (RewardOption o : goods.subList(from, to)) {
            if ((slot + 1) % 9 == 0) slot += 2;   // 跳过边列
            boolean owned = data != null && data.unlocks.contains(o.id());
            setSlot(slot++, icon(o, p, o.unlockCost(), owned), (clicker, ev) -> {
                if (data == null) return;
                if (owned || data.unlocks.contains(o.id())) {
                    p.sendMessage(MessageService.component("menu.shop.already_owned", Map.of(
                            "display", o.displayPlain())));
                    return;
                }
                if (data.balance < o.unlockCost()) {
                    p.sendMessage(MessageService.component("menu.shop.not_enough", Map.of(
                            "cost", String.valueOf(o.unlockCost()))));
                    return;
                }
                // 二次确认（§13.3，R14）：确认后才扣费解锁。确认/取消/按 ESC 都回到军需处同一页。
                new ShopConfirmMenu(p, o, page).open();
            });
        }

        // 翻页（左下 / 右下，均为羽毛）：单页装得下时不画，保持旧观感
        if (pages > 1) {
            setSlot(SLOT_PREV, pageButton(true, page > 0, page + 1, pages), (clicker, ev) -> {
                if (page > 0) MenuFacade.open("maggoteers-shop", clicker.player(), page - 1);
            });
            setSlot(SLOT_NEXT, pageButton(false, page < pages - 1, page + 1, pages), (clicker, ev) -> {
                if (page < pages - 1) MenuFacade.open("maggoteers-shop", clicker.player(), page + 1);
            });
        }
    }

    /** 翻页键（羽毛）；到头时灰字、点了不动。 */
    private static ItemStack pageButton(boolean prev, boolean enabled, int current, int pages) {
        ItemStack s = new ItemStack(Material.FEATHER);
        ItemMeta m = s.getItemMeta();
        if (m != null) {
            m.displayName(MessageService.component(
                            prev ? "menu.shop.prev_name" : "menu.shop.next_name", Map.of())
                    .decoration(TextDecoration.ITALIC, false));
            m.lore(List.of(
                    MessageService.component(enabled ? "menu.shop.page_lore" : "menu.shop.page_edge",
                                    Map.of("page", String.valueOf(current),
                                            "pages", String.valueOf(pages)))
                            .decoration(TextDecoration.ITALIC, false)));
            s.setItemMeta(m);
            if (!enabled) {
                // 到头：灰一点，避免玩家以为还能点
                m = s.getItemMeta();
                if (m != null) {
                    m.displayName(MessageService.component(
                                    prev ? "menu.shop.prev_name" : "menu.shop.next_name", Map.of())
                            .decoration(TextDecoration.ITALIC, false)
                            .color(net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY));
                    s.setItemMeta(m);
                }
            }
        }
        return s;
    }

    /** 顶部余额只读槽：金锭 + 当前账户积分（config messages.menu.shop.balance）。 */
    private static ItemStack balanceIcon(int balance) {
        ItemStack s = new ItemStack(Material.GOLD_NUGGET);
        ItemMeta m = s.getItemMeta();
        if (m != null) {
            m.displayName(MessageService.component("menu.shop.balance",
                            Map.of("balance", String.valueOf(balance)))
                    .decoration(TextDecoration.ITALIC, false));
            m.lore(List.of(MessageService.component("menu.shop.balance_lore", Map.of())
                    .decoration(TextDecoration.ITALIC, false)));
            s.setItemMeta(m);
        }
        return s;
    }

    private static ItemStack icon(RewardOption o, Player viewer, int cost, boolean owned) {
        ItemStack base = owned
                ? new ItemStack(Material.ENCHANTED_BOOK)
                : RewardOptionIcons.preview(o, viewer, null);
        ItemStack s = base.clone();
        ItemMeta m = s.getItemMeta();
        if (m != null) {
            if (s.getType() == Material.PAPER || owned) {
                m.displayName(Component.text(o.displayPlain(), owned ? NamedTextColor.GREEN : NamedTextColor.AQUA)
                        .decoration(TextDecoration.ITALIC, false));
            }
            var lore = m.lore() != null ? new ArrayList<>(m.lore()) : new ArrayList<Component>();
            lore.add((owned
                    ? MessageService.component("menu.shop.lore_owned", Map.of())
                    : MessageService.component("menu.shop.lore_cost", Map.of("cost", String.valueOf(cost))))
                    .decoration(TextDecoration.ITALIC, false));
            if (o.description() != null && !o.description().isBlank()) {
                lore.add(Component.text(o.description(), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            }
            m.lore(lore);
            s.setItemMeta(m);
        }
        return s;
    }
}
