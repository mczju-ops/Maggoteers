package io.mczju.maggoteers.menu;

import com.github.mczjuops.mczjugamecore.game.AbstractGame;
import com.github.mczjuops.mczjugamecore.menu.Menu;
import com.github.mczjuops.mczjugamecore.player.PlayerExt;
import io.mczju.maggoteers.config.MessageService;
import io.mczju.maggoteers.item.ItemKind;
import io.mczju.maggoteers.item.ItemService;
import io.mczju.maggoteers.state.PlayerState;
import io.mczju.maggoteers.state.PlayerStateManager;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.Map;

/**
 * 复活币 GUI（§11）：本局玩家头像列表。点击头像：
 * <ul>
 *   <li>死者（观察者）→ {@link PlayerStateManager#revivePlayer}（仅复活，不加次数）</li>
 *   <li>活者（冒险）→ {@link PlayerStateManager#addReviveCount}(+1)</li>
 * </ul>
 * 成功后扣点击者 1 枚复活币。需在休整期/任意时（对局内）可用。
 */
public class ReviveMenu extends Menu {
    private final AbstractGame game;

    public ReviveMenu(Player player, Object... args) {
        super(player, args);
        this.game = (AbstractGame) args[0];
    }

    @Override protected String getTitle() {
        return MessageService.raw("menu.revive.title", Map.of());
    }
    @Override protected int getRows() { return 3; }
    @Override protected String getPermission() { return "maggoteers.play"; }

    @Override
    protected void setup() {
        int slot = 10;
        for (PlayerExt pe : game.getPlayers()) {
            Player target = pe.player();
            PlayerState st = PlayerStateManager.get(game, target.getUniqueId());
            boolean dead = st == null || !st.isAlive();
            setSlot(slot, headOf(target, dead), (clicker, ev) -> onPick(clicker.player(), target, dead));
            slot += 2;
            if (slot > 16) break;
        }
    }

    private void onPick(Player clicker, Player target, boolean targetDead) {
        // 扣 1 复活币
        if (!ItemService.spendOneKind(clicker, ItemKind.REVIVE_COIN)) {
            clicker.sendMessage(MessageService.component("menu.revive.no_coin", Map.of()));
            return;
        }
        if (targetDead) {
            boolean ok = PlayerStateManager.revivePlayer(game, target.getUniqueId());
            if (ok) {
                // 成就「再世华佗」：只算"用复活币把死者救起来"，给活人 +1 次数不算
                io.mczju.maggoteers.achievement.AchievementService.onTeammateSaved(clicker);
                game.sender().info(MessageService.raw("menu.revive.revived", Map.of(
                        "actor", clicker.getName(),
                        "target", target.getName())));
            } else {
                // 失败：退还（理论不发生，targetDead 已判定）
                ItemService.giveKind(clicker, ItemKind.REVIVE_COIN, 1);
                clicker.sendMessage(MessageService.component("menu.revive.not_needed", Map.of()));
                return;
            }
        } else {
            PlayerStateManager.addReviveCount(game, target.getUniqueId(), 1);
            game.sender().info(MessageService.raw("menu.revive.add_life", Map.of(
                    "actor", clicker.getName(),
                    "target", target.getName())));
        }
        clicker.closeInventory();
    }

    private static ItemStack headOf(Player target, boolean dead) {
        ItemStack skull = new ItemStack(dead ? Material.SKELETON_SKULL : Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) skull.getItemMeta();
        if (meta != null) {
            meta.setPlayerProfile(target.getPlayerProfile());
            String key = dead ? "menu.revive.head_down" : "menu.revive.head_alive";
            meta.displayName(MessageService.component(key, Map.of("name", target.getName()))
                    .decoration(TextDecoration.ITALIC, false));
            skull.setItemMeta(meta);
        }
        return skull;
    }
}
