package io.mczju.maggoteers.menu;

import com.github.mczjuops.mczjugamecore.game.AbstractGame;
import com.github.mczjuops.mczjugamecore.menu.Menu;
import com.github.mczjuops.mczjugamecore.player.PlayerExt;
import io.mczju.maggoteers.config.MessageService;
import io.mczju.maggoteers.effect.PlayerCombatStats;
import io.mczju.maggoteers.game.MaggoteersGame;
import io.mczju.maggoteers.state.PlayerState;
import io.mczju.maggoteers.state.PlayerStateManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 「查看玩家数值」GUI（Task 4 / Bug #3-B）：逐参与者一个 PLAYER_HEAD 槽位，
 * lore 显示 6 项数值——攻击力 / 魔攻倍率 / 速度 / 冷却减免倍率 / 生命上限 / 生物触及距离。
 * <p>目的：给玩家提供观察 counter-arm 武器（魔法增伤 / 冷却减免等）是否生效的手段。
 * 只读展示，无点击行为。
 */
public class StatsMenu extends Menu {
    private final AbstractGame game;

    public StatsMenu(Player player, Object... args) {
        super(player, args);
        this.game = (AbstractGame) args[0];
    }

    @Override protected String getTitle() {
        return MessageService.raw("menu.stats.title", Map.of());
    }
    @Override protected int getRows() { return 3; }
    @Override protected String getPermission() { return "maggoteers.play"; }

    @Override
    protected void setup() {
        int slot = 10;
        for (PlayerExt pe : game.getPlayers()) {
            Player target = pe.player();
            if (target == null) continue;
            setSlot(slot, headOf(target));
            slot += 2;
            if (slot > 16) break;
        }
    }

    private static ItemStack headOf(Player target) {
        ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) skull.getItemMeta();
        if (meta == null) return skull;
        meta.setPlayerProfile(target.getPlayerProfile());
        meta.displayName(MessageService.component("menu.stats.head", Map.of("name", target.getName()))
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(renderLore(target));
        skull.setItemMeta(meta);
        return skull;
    }

    private static List<Component> renderLore(Player target) {
        MaggoteersGame mg = targetGame(target);
        PlayerState st = PlayerStateManager.getByUuid(target.getUniqueId());
        double atkMul = mg == null ? 1.0 : PlayerCombatStats.attackMultiplier(mg, target.getUniqueId());
        double magicMul = mg == null ? 1.0 : PlayerCombatStats.magicDamageMultiplier(mg, target.getUniqueId());
        double cdMul = mg == null ? 1.0 : PlayerCombatStats.reloadMultiplier(mg, target.getUniqueId());
        double healMul = st == null ? 1.0 : PlayerCombatStats.healMultiplierFromEffects(st.effects(), target);
        double rangeMul = st == null ? 1.0 : PlayerCombatStats.rangeMultiplierFromEffects(st.effects(), target);
        // 速度倍率：原版基础 MOVEMENT_SPEED = 0.1
        double speedMul = attrOf(target, Attribute.MOVEMENT_SPEED, 0.1) / 0.1;
        List<String> lines = StatsRender.renderLines(
                atkMul, magicMul, cdMul, healMul, rangeMul, speedMul,
                attrOf(target, Attribute.MAX_HEALTH, 20.0),
                attrOf(target, Attribute.ENTITY_INTERACTION_RANGE, 3.0));
        List<Component> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            out.add(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                    .deserialize(line).decoration(TextDecoration.ITALIC, false));
        }
        return out;
    }

    /** 属性值（getAttribute 可能返回 null → 兜底）。 */
    private static double attrOf(Player p, Attribute attr, double fallback) {
        AttributeInstance inst = p.getAttribute(attr);
        return inst == null ? fallback : inst.getValue();
    }

    private static MaggoteersGame targetGame(Player target) {
        UUID uuid = target.getUniqueId();
        PlayerState st = PlayerStateManager.getByUuid(uuid);
        return st == null ? null : PlayerStateManager.gameForPlayer(uuid);
    }
}
