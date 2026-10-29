package io.mczju.maggoteers.listener;

import io.mczju.maggoteers.effect.EffectService;
import io.mczju.maggoteers.effect.ParticleLoopService;
import io.mczju.maggoteers.effect.Trigger;
import io.mczju.maggoteers.effect.TriggerContext;
import io.mczju.maggoteers.wave.WaveEngine;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.entity.SlimeSplitEvent;
import org.bukkit.potion.PotionEffect;

import java.util.ArrayList;

/**
 * 怪物死亡监听（借鉴前代 VampireSurvivor MobDeathListener）。
 * <p>处理两类：{@link EntityDeathEvent}（普通死）与 {@link EntityExplodeEvent}（苦力怕等，可能先于 Death 触发）。
 * 两者都转发 {@link WaveEngine#handleMobDeath}；WaveEngine 内部幂等（BY_ENTITY remove 后第二次返回 false）。
 * <p>局内史莱姆/岩浆怪取消分裂，避免 livingMobs 被原版分裂打乱。
 */
public class MobDeathListener implements Listener {

    /**
     * 苦力怕等爆炸前清掉追踪怪身上的药水。原版会把存活药水打成 AreaEffectCloud；
     * 技能/旧词缀常用极大 dur（隐身/致盲/凋零），玩家踩到会挂数小时 buff。
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void beforeTrackedExplode(ExplosionPrimeEvent e) {
        if (!(e.getEntity() instanceof LivingEntity le)) return;
        if (!WaveEngine.isTracked(le.getUniqueId())) return;
        for (PotionEffect pe : new ArrayList<>(le.getActivePotionEffects())) {
            le.removePotionEffect(pe.getType());
        }
    }

    // ignoreCancelled：1up 复活技能 cancel 的死亡不 untrack（复活怪保留波次追踪）
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent e) {
        // 死亡即取消该怪的粒子周期任务（WaveEngine.handleMobDeath→detach 也会 cancelAll，这里直接兜底一次）
        ParticleLoopService.cancelAll(e.getEntity());
        java.util.UUID id = e.getEntity().getUniqueId();
        // 2026-10-28：隐藏层（Act4）最终 Boss 一死 → 直接算通关（不论还剩多少杂兵）。
        // 必须在 handleMobDeath 之前判定——它会把 BossBar/追踪表清掉。
        // ⚠️ 2026-10-31（用户口径）：这条即时胜利**只给隐藏层**。act1–act3 的 Boss 死了
        // 也要把本波剩余杂兵清完才算过波，否则前三层就没有收尾压力了。
        com.github.mczjuops.mczjugamecore.game.AbstractGame deathGame = WaveEngine.gameOfEntity(id);
        boolean bossKill = deathGame != null && WaveEngine.isTrackedBoss(deathGame, id);
        boolean hiddenFinalBoss = bossKill
                && io.mczju.maggoteers.wave.WaveScheduler.isHiddenFinalBossWave(deathGame);
        // 2026-10-28：ON_BOSS_KILL **必须在这里、handleMobDeath 之前**发。
        // handleMobDeath 会 rt.bossBars.remove(uuid) → WaveEngine.isTrackedBoss 立即变 false，
        // 而 EffectListener.onKill 是 MONITOR（比本监听器 HIGHEST 更晚），那时已经认不出 Boss——
        // 霜晶树因此少发一颗星石（Boss 波自带的 1 颗发了、藏的这 1 颗没发）。
        if (bossKill && deathGame instanceof io.mczju.maggoteers.game.MaggoteersGame mg) {
            Player killer = e.getEntity().getKiller();
            if (killer != null) {
                EffectService.fireTriggerPlayer(mg, killer, Trigger.ON_BOSS_KILL,
                        new TriggerContext(Trigger.ON_BOSS_KILL, e.getEntity(), null,
                                e.getEntity().getLocation().clone()));
            }
        }
        if (WaveEngine.handleMobDeath(id, e.getEntity().getLocation())) {
            e.getDrops().clear();
            e.setDroppedExp(0);
        }
        if (hiddenFinalBoss) {
            io.mczju.maggoteers.wave.WaveScheduler.finalBossDefeated(deathGame);
        }
    }

    @EventHandler
    public void onExplode(EntityExplodeEvent e) {
        WaveEngine.handleMobDeath(e.getEntity().getUniqueId(), e.getEntity().getLocation());
        e.setYield(0f);
        e.blockList().clear();
    }

    @EventHandler
    public void onSlimeSplit(SlimeSplitEvent e) {
        // 三层防线，任何一层命中即取消：
        //  1) 实体自带 COMBAT_CUBE 标记（MobFactory.ensureCombatSlimeSize 生成时打标）——源头、与事件顺序/追踪状态无关；
        //  2) 仍被追踪（split 先于 untrack 触发时的兜底）；
        //  3) 消费 handleMobDeath 预打的 PENDING_SPLIT_CANCEL（EntityDeathEvent 先于 split 触发时的旧路径）。
        boolean tagged = e.getEntity().getPersistentDataContainer()
                .has(io.mczju.maggoteers.mob.MobFactory.combatCubeKey(),
                        org.bukkit.persistence.PersistentDataType.BOOLEAN);
        boolean tracked = WaveEngine.isTracked(e.getEntity().getUniqueId());
        boolean consume = WaveEngine.consumeSplitCancel(e.getEntity().getUniqueId());
        boolean cancel = tagged || tracked || consume;
        if (cancel) {
            e.setCancelled(true);
        } else {
            // 冒烟诊断（2026-08-27）：正常应永远打不到（三层防线全覆盖）；打出来 = 分裂未被取消的异常，保留线索
            io.mczju.maggoteers.MaggoteersPlugin.getInstance().getLogger()
                    .warning("[slimeSplit] 未取消！" + e.getEntity().getType() + " size=" + e.getCount()
                            + " tagged=" + tagged + " tracked=" + tracked + " consume=" + consume);
        }
    }
}
