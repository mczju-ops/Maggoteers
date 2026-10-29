package io.mczju.maggoteers.achievement;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;

/**
 * 伤害相关的成就挂点。
 *
 * <p>两个用途：
 * <ul>
 *   <li><b>会心一击</b>：玩家对敌人造成的单次伤害（物理/箭矢/法术都走
 *       {@code EntityDamageEvent}，因此一个监听器全包；法术侧由
 *       {@code MagicDamage.applyIndependent} 触发同一事件）。</li>
 *   <li><b>魔芋爽</b>：给"被自己的效果打中"打时间戳，死亡时判定。</li>
 * </ul>
 */
public final class AchievementListener implements Listener {

    /** 统计"我打出的伤害"：只在真的扣血时算（被无敌/免疫吃掉的伤害不算）。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamageDealt(EntityDamageEvent e) {
        if (!(e instanceof EntityDamageByEntityEvent byEntity)) return;
        if (e.getEntity() instanceof Player victim && isSelfHit(byEntity, victim)) return;
        Player attacker = attackerOf(byEntity.getDamager());
        if (attacker == null) return;
        if (e.getEntity().getUniqueId().equals(attacker.getUniqueId())) return;   // 打自己不算输出
        double damage = e.getFinalDamage();
        if (damage <= 0) return;
        AchievementService.onDamageDealt(attacker, damage);
    }

    /**
     * 给自伤打时间戳。不要求事件未被取消（被无敌挡下也说明"这个效果确实打到自己身上了"）。
     *
     * <p>判据只看 **施法者 == 受害者**：本插件的自伤效果都带 source（`MagicDamage.applyIndependent` /
     * `victim.damage(-heal, victim)`），环境伤害（摔落、火）没有 damager，**不会**误判。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onSelfDamage(EntityDamageEvent e) {
        if (!(e instanceof EntityDamageByEntityEvent byEntity)) return;
        if (!(e.getEntity() instanceof Player victim)) return;
        if (!isSelfHit(byEntity, victim)) return;
        AchievementService.onSelfDamage(victim);
    }

    private static boolean isSelfHit(EntityDamageByEntityEvent e, Player victim) {
        return e.getDamager().getUniqueId().equals(victim.getUniqueId());
    }

    /** 直接命中或弹射物（箭/火球）命中都算到射手头上。 */
    private static Player attackerOf(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile proj && proj.getShooter() instanceof Player shooter) return shooter;
        return null;
    }
}
