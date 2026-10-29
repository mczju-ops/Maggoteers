package io.mczju.maggoteers.menu;

import com.github.mczjuops.mczjugamecore.game.AbstractGame;
import com.github.mczjuops.mczjugamecore.menu.Menu;
import com.github.mczjuops.mczjugamecore.menu.MenuFacade;
import io.mczju.maggoteers.config.MessageService;
import io.mczju.maggoteers.game.MaggoteersGame;
import io.mczju.maggoteers.item.ItemKind;
import io.mczju.maggoteers.item.ItemService;
import io.mczju.maggoteers.reward.RewardOption;
import io.mczju.maggoteers.reward.RewardOptionIcons;
import io.mczju.maggoteers.reward.RewardService;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;

/** 3 选 1 奖励（休整菜单入口；货币在 apply 成功后扣除，保证原子性）。 */
public class PickMenu extends Menu {
    private static final int[] SLOTS = {11, 13, 15};

    private final AbstractGame game;
    private final List<RewardOption> offers;
    private final String poolId;
    private final ItemKind currencyKind;
    private final int cost;

    @SuppressWarnings("unchecked")
    public PickMenu(Player player, Object... args) {
        super(player, args);
        this.game = (AbstractGame) args[0];
        this.offers = (List<RewardOption>) args[1];
        this.poolId = args.length > 2 ? String.valueOf(args[2]) : null;
        this.currencyKind = args.length > 3 ? ItemKind.valueOf(String.valueOf(args[3])) : null;
        this.cost = args.length > 4 ? ((Number) args[4]).intValue() : 0;
    }

    @Override protected String getTitle() {
        return MessageService.raw("menu.pick.title", Map.of());
    }
    @Override protected int getRows() { return 3; }
    @Override protected String getPermission() { return "maggoteers.play"; }

    @Override
    protected void setup() {
        Player viewer = player.player();
        MaggoteersGame mg = game instanceof MaggoteersGame g ? g : null;
        for (int i = 0; i < Math.min(SLOTS.length, offers.size()); i++) {
            RewardOption opt = offers.get(i);
            setSlot(SLOTS[i], icon(opt, viewer, mg), (clicker, ev) -> {
                Player p = clicker.player();
                boolean ok = RewardService.apply(p, opt, game, poolId);
                if (ok) {
                    // 购买成功 → 该池抽签缓存失效，下次重开重新抽（R10）
                    RewardService.invalidateDraw(p.getUniqueId(), poolId);
                    // 成功后扣费（C4：原子性——先 apply 再扣）
                    if (currencyKind != null && cost > 0) {
                        for (int j = 0; j < cost; j++) {
                            if (!ItemService.spendOneKind(p, currencyKind)) {
                                p.sendMessage(MessageService.component("menu.pick.charge_error", Map.of()));
                                break;
                            }
                        }
                    }
                    p.closeInventory();
                    MenuFacade.open("maggoteers-rest", p, game);
                } else {
                    p.sendMessage(MessageService.component("menu.pick.apply_failed", Map.of()));
                }
            });
        }
    }

    private ItemStack icon(RewardOption opt, Player viewer, MaggoteersGame mg) {
        return RewardOptionIcons.preview(opt, viewer, mg);
    }
}
