package io.mczju.maggoteers.item;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.UseCooldown;
import net.kyori.adventure.key.Key;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * 物品 CD 门禁（§10.3 D2）：按 **PDC id（maggoteers:id）** 的时间戳表。
 * 视觉 CD 走 ItemCreator {@code useCooldown} 组件 + Paper {@link Player#setCooldown(Key, int)}。
 * <p>主线程同步访问，无需加锁。时钟可注入（{@link #useTestClock(LongSupplier)}）便于单测。
 * <p>Spec2：逻辑 CD 与视觉时长均用 {@code double} 秒（{@code Math.round} 毫秒/ tick，无截断错位）。
 */
public final class CooldownService {
    private static final Map<UUID, Map<String, Long>> EXPIRE = new HashMap<>();
    private static LongSupplier clock = System::currentTimeMillis;

    /** 放行且计 CD -> true；在 CD 内 -> false。cooldownSec<=0 视为无 CD（恒放行）。 */
    public static boolean tryUse(UUID player, String pdcId, double cooldownSec) {
        if (cooldownSec <= 0) return true;
        long now = clock.getAsLong();
        long exp = EXPIRE.getOrDefault(player, Map.of()).getOrDefault(pdcId, 0L);
        if (now < exp) return false;
        EXPIRE.computeIfAbsent(player, k -> new HashMap<>()).put(pdcId, now + Math.round(cooldownSec * 1000.0));
        return true;
    }

    /** 逻辑 CD + ItemCreator 使用冷却条（若物品带 useCooldown 组件）。 */
    public static boolean tryUse(Player player, ItemStack stack, String pdcId, double cooldownSec) {
        if (!tryUse(player.getUniqueId(), pdcId, cooldownSec)) return false;
        applyVisualUseCooldown(player, stack, cooldownSec);
        return true;
    }

    /**
     * 外显冷却：组件 group 兜底强制 = PDC id（1.21 客户端遮罩按组件 group 关联）；时长用 effectiveSec。
     * 只改主手真实物品（event.getItem() 可能是副本，改副本不生效）。
     * 正常路径：物品发放时 ItemService 已规范化 group = PDC id，此处只在组件缺失/不符时兜底改写。
     */
    public static void applyVisualUseCooldown(Player player, ItemStack stack, double effectiveSec) {
        if (player == null || stack == null || stack.getType().isAir() || effectiveSec <= 0) return;
        int ticks = Math.max(1, (int) Math.round(effectiveSec * 20.0));
        String pdcId = ItemService.itemIdOf(stack);
        if (pdcId == null) {
            player.setCooldown(stack, ticks);                 // 无 PDC（货币/菜单物品）回落 Material 级
            return;
        }
        Key key;
        try {
            key = Key.key(pdcId);
        } catch (Exception e) {
            player.setCooldown(stack, ticks);                 // 非法 key 回落 Material 级
            return;
        }
        // 主手真实物品；确认非空气且仍是同一把武器，防极端情况改错槽
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held == null || held.getType().isAir() || !pdcId.equals(ItemService.itemIdOf(held))) {
            player.setCooldown(key, ticks);
            return;
        }
        UseCooldown uc = held.getData(DataComponentTypes.USE_COOLDOWN);
        float seconds = (float) effectiveSec;
        // 2026-10-28：必须把组件的 seconds 也改成实际冷却，否则客户端/原版冷却仍按 IC 的整数秒遮罩——
        // 表现就是「0.1s 冷却按不出去」。（IC 的 useCooldown.seconds 只能是整数，这里运行期重建不受限）
        if (uc == null || !key.equals(uc.cooldownGroup())
                || Math.abs(uc.seconds() - seconds) > 1e-4) {
            UseCooldown rebuilt = UseCooldown.useCooldown(seconds)
                    .cooldownGroup(key)
                    .build();
            held.setData(DataComponentTypes.USE_COOLDOWN, rebuilt);
            player.updateInventory();      // 同 tick 改组件后强制客户端刷新，避免第一下遮罩不同步
        }
        player.setCooldown(key, ticks);
    }

    public static boolean onCooldown(UUID player, String pdcId) {
        return clock.getAsLong() < EXPIRE.getOrDefault(player, Map.of()).getOrDefault(pdcId, 0L);
    }

    /** 玩家退出/对局结束清理。 */
    public static void clear(UUID player) { EXPIRE.remove(player); }

    /** 单测注入时钟。 */
    static void useTestClock(LongSupplier c) { clock = c; }
    static void resetClock() { clock = System::currentTimeMillis; }

    private CooldownService() {}
}
