package io.mczju.maggoteers.listener;

import io.mczju.maggoteers.effect.ItemCritRegistry;
import io.mczju.maggoteers.item.ItemService;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;

/**
 * 近战「元素暴击」结算（2026-10-29）。
 *
 * <p>条件与倍率来自物品 YAML 的 {@code crit:} 段（见 {@link ItemCritRegistry}）。
 * 命中时若目标处于对应状态（燃烧 / 寒冷 / 在空中 / 凋零），按 **mul 算法**乘伤害并播对应音效。
 * 只在**主手武器满足配置**且目标是敌对生物（非玩家）时触发，避免误伤队友/自己。
 */
public final class MeleeCritListener implements Listener {

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMeleeCrit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        if (!(event.getEntity() instanceof LivingEntity target)) return;
        if (target instanceof Player) return;                       // 玩家之间不暴击（PvE 设计）
        ItemStack hand = player.getInventory().getItemInMainHand();
        ItemCritRegistry.Spec spec = ItemCritRegistry.critOf(ItemService.itemIdOf(hand));
        if (spec == null) return;
        if (!matches(spec.condition(), target)) return;

        event.setDamage(event.getDamage() * spec.multiplier());
        Location at = target.getLocation();
        if (at.getWorld() != null) {
            at.getWorld().playSound(at, spec.resolveSound(), spec.volume(), spec.pitch());
        }
    }

    /** 纯状态判定（不碰事件对象，方便单测喂假状态走 {@link #stateMatches}）。 */
    static boolean matches(ItemCritRegistry.Condition condition, LivingEntity target) {
        if (condition == null || target == null) return false;
        return stateMatches(condition, target.getFireTicks() > 0, target.getFreezeTicks() > 0,
                !target.isOnGround(), target.hasPotionEffect(PotionEffectType.WITHER));
    }

    /** 纯函数：条件 ↔ 四种状态位。 */
    static boolean stateMatches(ItemCritRegistry.Condition condition,
                                boolean burning, boolean frozen, boolean airborne, boolean wither) {
        if (condition == null) return false;
        return switch (condition) {
            case BURNING -> burning;
            case FROZEN -> frozen;
            case AIRBORNE -> airborne;
            case WITHER -> wither;
        };
    }
}
