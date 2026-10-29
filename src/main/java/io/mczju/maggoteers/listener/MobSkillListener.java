package io.mczju.maggoteers.listener;

import com.github.mczjuops.mczjugamecore.game.AbstractGame;
import io.mczju.maggoteers.game.MaggoteersGame;
import io.mczju.maggoteers.mob.MobSkillService;
import io.mczju.maggoteers.mob.MobTrigger;
import io.mczju.maggoteers.wave.WaveEngine;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;

import java.util.UUID;

/**
 * 怪物技能事件入口：攻击（附带目标）、受击（附带伤害来源）、被击杀（附带击杀者）。
 * <p>LOWEST 优先：先于 MobDeathListener.HIGHEST 的 handleMobDeath（untrack）触发，保证 KILLED 能反查技能表。
 */
public class MobSkillListener implements Listener {

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCombat(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof LivingEntity victim)) return;
        if (e.getDamager() instanceof LivingEntity attacker) {
            MaggoteersGame victimGame = gameOf(victim.getUniqueId());
            if (victimGame != null) {
                // DAMAGE_TAKEN 的 target=攻击者：quicksand（给目标缓慢）/vengeance（反伤）意图作用于攻击者
                MobSkillService.fire(victimGame, victim, MobTrigger.DAMAGE_TAKEN, attacker, attacker);
            }
            MaggoteersGame attackGame = gameOf(attacker.getUniqueId());
            if (attackGame != null) {
                MobSkillService.fire(attackGame, attacker, MobTrigger.ATTACK, victim, null);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onKilled(EntityDeathEvent e) {
        if (!(e.getEntity() instanceof LivingEntity dead)) return;
        MaggoteersGame game = gameOf(dead.getUniqueId());
        if (game == null) return;
        // 复活技能：先 cancel 死亡（阻止实体移除/untrack），再 fire 执行 setHealth 复活
        if (MobSkillService.wantsRevive(dead)) {
            e.setCancelled(true);
        }
        LivingEntity killer = dead.getKiller();
        MobSkillService.fire(game, dead, MobTrigger.KILLED, null, killer);
    }

    private static MaggoteersGame gameOf(UUID uuid) {
        AbstractGame g = WaveEngine.gameOfEntity(uuid);
        return g instanceof MaggoteersGame mg ? mg : null;
    }
}
