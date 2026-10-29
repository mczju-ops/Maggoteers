package io.mczju.maggoteers.menu;

import com.github.mczjuops.mczjugamecore.menu.Menu;
import com.github.mczjuops.mczjugamecore.menu.MenuFacade;
import com.github.mczjuops.mczjugamecore.player.PlayerExt;
import io.mczju.maggoteers.achievement.AchievementService;
import io.mczju.maggoteers.config.MessageService;
import io.mczju.maggoteers.persist.MaggoteersPlayerData;
import io.mczju.maggoteers.reward.RewardOption;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.Map;

/**
 * 军需处的**二次确认**小窗（3×9）：左「确认」右「取消」。
 *
 * <p>为什么不用 MGC 的 {@code AlertMenu}：它只有一个确认回调，**取消时直接把界面关了**，
 * 玩家就被踢出商店（2026-10-30 用户口径：确认/取消都要回到军需处）。这里自己画一个，
 * 两条路（含按 ESC 关闭）都回到军需处，并停在同一页。
 */
final class ShopConfirmMenu extends Menu {

    private static final int SLOT_OK = 11;      // 第 2 行第 3 格
    private static final int SLOT_CANCEL = 15;  // 第 2 行第 7 格

    private final RewardOption option;
    private final int page;
    /** 已经跳回商店（防止 handleClose 再开一次）。 */
    private boolean navigated;

    ShopConfirmMenu(Player player, RewardOption option, int page, Object... args) {
        super(player, args);
        this.option = option;
        this.page = page;
    }

    @Override protected String getTitle() {
        return MessageService.raw("menu.shop.confirm_title", Map.of());
    }

    @Override protected int getRows() { return 3; }

    @Override protected String getPermission() { return "maggoteers.play"; }

    @Override
    protected void setup() {
        Player p = player.player();
        setSlot(4, previewItem());
        setSlot(SLOT_OK, button(Material.LIME_CONCRETE, "menu.shop.confirm_ok",
                "menu.shop.confirm_ok_lore"), (clicker, ev) -> {
            navigated = true;
            buy(clicker.player());
            backToShop(clicker.player());
        });
        setSlot(SLOT_CANCEL, button(Material.RED_CONCRETE, "menu.shop.confirm_cancel",
                "menu.shop.confirm_cancel_lore"), (clicker, ev) -> {
            navigated = true;
            backToShop(clicker.player());
        });
        // 占位：避免点到空槽以为没反应
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta fm = filler.getItemMeta();
        if (fm != null) {
            fm.displayName(net.kyori.adventure.text.Component.empty());
            filler.setItemMeta(fm);
        }
        for (int i = 0; i < 27; i++) {
            if (i == 4 || i == SLOT_OK || i == SLOT_CANCEL) continue;
            setSlot(i, filler);
        }
        // 静默 p 未用（保留可读性）
        if (p == null) return;
    }

    /** 按 ESC / 切走界面也算「取消」，同样回商店。 */
    @Override
    public void handleClose() {
        super.handleClose();
        if (navigated) return;
        navigated = true;
        backToShop(player.player());
    }

    private void buy(Player p) {
        var data = new PlayerExt(p).getData(MaggoteersPlayerData.class);
        if (data == null) return;
        if (data.unlocks.contains(option.id())) {
            p.sendMessage(MessageService.component("menu.shop.already_owned",
                    Map.of("display", option.displayPlain())));
            return;
        }
        if (!data.spend(option.unlockCost())) {
            p.sendMessage(MessageService.component("menu.shop.not_enough",
                    Map.of("cost", String.valueOf(option.unlockCost()))));
            return;
        }
        data.unlock(option.id());
        AchievementService.onShopPurchase(p);
        p.sendMessage(MessageService.component("menu.shop.unlocked",
                Map.of("display", option.displayPlain())));
    }

    private void backToShop(Player p) {
        if (p == null || !p.isOnline()) return;
        // 延后一 tick：此刻还在 InventoryCloseEvent 里，立刻 open 会被随后的事件关掉
        org.bukkit.Bukkit.getScheduler().runTask(
                io.mczju.maggoteers.MaggoteersPlugin.getInstance(),
                () -> MenuFacade.open("maggoteers-shop", p, page));
    }

    private ItemStack previewItem() {
        ItemStack s = new ItemStack(Material.GOLD_INGOT);
        ItemMeta m = s.getItemMeta();
        if (m != null) {
            m.displayName(MessageService.component("menu.shop.confirm_item",
                            Map.of("display", option.displayPlain(),
                                    "cost", String.valueOf(option.unlockCost())))
                    .decoration(TextDecoration.ITALIC, false));
            m.lore(List.of(MessageService.component("menu.shop.confirm_item_lore", Map.of())
                    .decoration(TextDecoration.ITALIC, false)));
            s.setItemMeta(m);
        }
        return s;
    }

    private static ItemStack button(Material mat, String nameKey, String loreKey) {
        ItemStack s = new ItemStack(mat);
        ItemMeta m = s.getItemMeta();
        if (m != null) {
            m.displayName(MessageService.component(nameKey, Map.of())
                    .decoration(TextDecoration.ITALIC, false));
            m.lore(List.of(MessageService.component(loreKey, Map.of())
                    .decoration(TextDecoration.ITALIC, false)));
            s.setItemMeta(m);
        }
        return s;
    }
}
