package io.mczju.maggoteers.listener;

import com.github.mczjuops.mczjugamecore.player.PlayerExt;
import io.mczju.maggoteers.effect.DamageScalingPolicy;
import io.mczju.maggoteers.game.MaggoteersGame;
import io.mczju.maggoteers.state.PlayerStateManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;

/**
 * 玩家受伤倍率（2026-10-26）。
 *
 * <p>历史行为：`config.yml scaling.mob_damage`（人数系数）× `difficulty.multipliers.*.mob_damage`（难度系数）
 * 只乘进怪物的 `ATTACK_DAMAGE` 属性 —— 也就是**只放大近战伤害**，远程/爆炸/技能伤害完全不吃这个系数。
 *
 * <p>现在改为：这两个系数合成 {@link MaggoteersGame#getDamageTakenMultiplier()}，
 * 在玩家受伤事件里统一放大**所有来源**的伤害。按用户口径豁免两类：
 * ① 自伤（damager == 受害者本人）恒为固定值；② 摔落伤害（且安全摔落高度已在局内抬到 40 格）。
 * {@link DamageScalingPolicy} 是纯函数判定，单测覆盖。
 *
 * <p>只作用于**本局参与者**（有 PlayerState 的局内玩家），大厅/旁观不受影响。
 */
public class PlayerDamageTakenListener implements Listener {

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player player)) return;
        PlayerExt ext = new PlayerExt(player);
        if (!(ext.getGame() instanceof MaggoteersGame game)) return;
        double mult = game.getDamageTakenMultiplier();
        if (mult <= 0 || Math.abs(mult - 1.0) < 1e-9) return;
        if (PlayerStateManager.get(game, player.getUniqueId()) == null) return;
        boolean self = e instanceof EntityDamageByEntityEvent by
                && by.getDamager().getUniqueId().equals(player.getUniqueId());
        if (!DamageScalingPolicy.shouldScale(e.getCause(), self)) return;
        e.setDamage(e.getDamage() * mult);
    }
}
