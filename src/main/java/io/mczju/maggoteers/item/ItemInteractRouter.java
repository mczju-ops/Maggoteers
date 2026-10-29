package io.mczju.maggoteers.item;

import com.github.mczjuops.mczjugamecore.game.AbstractGame;
import com.github.mczjuops.mczjugamecore.player.PlayerExt;
import com.github.mczjuops.mczjugamecore.menu.MenuFacade;
import io.mczju.maggoteers.MaggoteersPlugin;
import io.mczju.maggoteers.config.MessageService;
import io.mczju.maggoteers.effect.EffectService;
import io.mczju.maggoteers.game.MaggoteersGame;
import io.mczju.maggoteers.item.interact.ItemUseHandler;
import io.mczju.maggoteers.item.interact.OpenClassMenuHandler;
import io.mczju.maggoteers.item.interact.OpenRestMenuHandler;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * 物品交互统一路由（§10.4）：右键 → 三层分发。
 *
 * <h2>三层分发顺序</h2>
 * <ol>
 *   <li><b>ItemKind 分发</b> — 读 PDC {@code maggoteers:kind}，查 {@link #HANDLERS}
 *       （货币/职业券/复活币等已知类别）。</li>
 *   <li><b>Weapon ID 分发</b> — kind 无命中时，读 PDC {@code maggoteers:id}，
 *       查 {@link #WEAPON_HANDLERS}（可选 Java handler 覆盖）。</li>
 *   <li><b>use_ability 配置</b> — 无 registerHandler 时，读 items/*.yml {@code use_ability}
 *       经 {@link io.mczju.maggoteers.item.interact.ConfigMagicHandler} 执行。</li>
 * </ol>
 *
 * <h2>CD 门禁（§10.3 D2）</h2>
 * <p>第二层/第三层 handler 的 CD 在 {@code onUse} 内调用
 * {@link CooldownService#tryUse(java.util.UUID, String, double)} 管控（按 PDC id，非材质）。
 * 魔法武器右击的 CD 现支持冷却减免（Spec2）：自定义 {@link ItemUseHandler} 需自行
 * {@code PlayerCombatStats.effectiveCooldownSec(mg, uuid, baseSec)} 把基础秒折算成有效秒
 * （执行前快照）再传给 {@code tryUse}（{@code io.mczju.maggoteers.item.interact.ConfigMagicHandler}
 * 是标准范例）。</p>
 *
 * <h2>扩展指南（给未来 agent）</h2>
 * <p>添加一把魔法武器（如 {@code maggoteers:fire_sword}）的右键效果：</p>
 * <ol>
 *   <li>在 {@code items/*.yml} 里定义物品，带上 PDC {@code maggoteers:id = "maggoteers:fire_sword"}。</li>
 *   <li>写一个实现 {@link ItemUseHandler} 的 handler 类，在其 {@code onUse} 内调用
 *       {@link CooldownService#tryUse(java.util.UUID, String, double)} 做门禁；若该武器
 *       支持 COOLDOWN_REDUCTION，先经 {@code PlayerCombatStats.effectiveCooldownSec} 折算。</li>
 *   <li>在 {@code onEnable}（{@link ItemService#init} 之后）调用：
 *       {@code ItemInteractRouter.registerHandler("maggoteers:fire_sword", new FireSwordHandler());}</li>
 * </ol>
 * <p>无需修改路由核心代码。</p>
 */
public final class ItemInteractRouter implements Listener {

    private static final Map<ItemKind, ItemUseHandler> HANDLERS = new EnumMap<>(ItemKind.class);

    /** weapon_id（maggoteers:id）→ handler。可选覆盖 config use_ability。 */
    private static final Map<String, ItemUseHandler> WEAPON_HANDLERS = new HashMap<>();

    static {
        OpenRestMenuHandler rest = new OpenRestMenuHandler();
        HANDLERS.put(ItemKind.CURRENCY_NORMAL, rest);
        HANDLERS.put(ItemKind.CURRENCY_BOSS, rest);
        HANDLERS.put(ItemKind.CLASS_TICKET, new OpenClassMenuHandler());
        HANDLERS.put(ItemKind.REVIVE_COIN, (player, game, stack) ->
                MenuFacade.open("maggoteers-revive", player, game));
        HANDLERS.put(ItemKind.SHOP_EMERALD, (player, game, stack) ->
                // 2026-10-27：随时可开，商店按钮由菜单按休整相位灰显
                MenuFacade.open("maggoteers-rest", player, game));
        // 隐藏结局层钥匙（2026-10-26 阶段 5）：门控/扣物品/进入都在 HiddenActGate 里
        HANDLERS.put(ItemKind.HIDDEN_KEY,
                (player, game, stack) -> io.mczju.maggoteers.wave.HiddenActGate.tryEnter(player, game, stack));
        HANDLERS.put(ItemKind.SUPPLY_HEALING, (player, game, stack) -> {
            double healed = EffectService.heal(player, 12.0);
            if (healed <= 0) {
                player.sendMessage(MessageService.component("item.heal_full", Map.of()));
                return;
            }
            player.sendMessage(MessageService.component("item.heal_amount", Map.of(
                    "hearts", String.valueOf((int) (healed / 2)))));
            ItemService.spendOneFromMainHand(player);
        });
    }

    /**
     * 注册一把魔法武器的右键 handler（供未来 agent 扩展）。
     * <p>weaponId 必须与 items/*.yml 里的 {@code maggoteers:id} 一致。
     * 例：{@code ItemInteractRouter.registerHandler("maggoteers:fire_sword", new FireSwordHandler());}
     * 在 {@code onEnable}（ItemService init 之后）调用一次即可。
     */
    public static void registerHandler(String weaponId, ItemUseHandler handler) {
        if (weaponId != null && handler != null) WEAPON_HANDLERS.put(weaponId, handler);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != EquipmentSlot.HAND) return;

        ItemStack stack = event.getItem();
        ItemKind kind = ItemService.readKind(stack);

        // 1) 已知 kind（货币/职业券/复活币…）→ 专用 handler
        ItemUseHandler handler = (kind == null) ? null : HANDLERS.get(kind);

        // 2) 否则：按 weapon_id（maggoteers:id）查注册表（handler 或 use_ability）
        String weaponId = null;
        if (handler == null) {
            weaponId = ItemService.itemIdOf(stack);
            if (weaponId != null) handler = WEAPON_HANDLERS.get(weaponId);
        }

        // 3) 配置驱动 use_ability（无 registerHandler 时）
        if (handler == null && weaponId != null
                && io.mczju.maggoteers.effect.ItemAbilityRegistry.get(weaponId).isPresent()) {
            handler = new io.mczju.maggoteers.item.interact.ConfigMagicHandler();
        }

        if (handler == null) {
            if (weaponId == null) weaponId = ItemService.itemIdOf(stack);
            if (weaponId == null) return;
            return;
        }

        event.setCancelled(true);
        PlayerExt pe = new PlayerExt(event.getPlayer());
        if (!pe.isInGame()) return;
        AbstractGame game = pe.getGame();
        MaggoteersGame mg = io.mczju.maggoteers.state.PlayerStateManager.gameForPlayer(event.getPlayer().getUniqueId());
        if (mg == null) mg = io.mczju.maggoteers.game.AllyTargeting.resolveForPlayer(event.getPlayer(), game);
        if (mg == null) return;
        var ps = io.mczju.maggoteers.state.PlayerStateManager.get(mg, event.getPlayer().getUniqueId());
        if (ps == null || !ps.isAlive()) return;
        handler.onUse(event.getPlayer(), mg, stack);
    }
}
