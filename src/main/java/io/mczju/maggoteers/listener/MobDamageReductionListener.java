package io.mczju.maggoteers.listener;

import io.mczju.maggoteers.effect.MagicDamage;
import io.mczju.maggoteers.mob.DamageCategory;
import io.mczju.maggoteers.mob.DamageReduction;
import io.mczju.maggoteers.mob.MobDamageGate;
import io.mczju.maggoteers.mob.MobSkillService;
import io.mczju.maggoteers.mob.MobSkillSpec;
import io.mczju.maggoteers.wave.WaveEngine;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;

import java.util.List;

/**
 * 怪物被动减伤（2026-10-26）：{@code mob_skills.yml} 的
 * {@code damage_reduction: { type: magic|physical, percent: 0.5 }}。
 *
 * <p>「法术伤害」判定 = 伤害发生时目标身上有 {@link MagicDamage#MAGIC_MARKER}
 * （由 {@code MagicDamage.applyIndependent} 在同一调用栈内打标）。
 * 同一实体多条减伤取最大值（不叠加，避免出现 100% 免伤的组合）。
 */
public class MobDamageReductionListener implements Listener {

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof LivingEntity le)) return;
        if (!WaveEngine.isTracked(le.getUniqueId())) return;
        List<MobSkillSpec> specs = MobSkillService.skillsOf(le.getUniqueId());
        if (specs.isEmpty()) return;
        boolean magic = le.hasMetadata(MagicDamage.MAGIC_MARKER);
        double best = 0.0;
        MobDamageGate gate = null;
        int phase = io.mczju.maggoteers.mob.BossPhaseTracker.phaseOf(le.getUniqueId());
        for (MobSkillSpec spec : specs) {
            if (!spec.phaseAllowed(phase)) continue;
            DamageReduction reduction = spec.damageReduction();
            if (reduction != null && reduction.matches(magic)) {
                best = Math.max(best, reduction.percent());
            }
            // D2（2026-10-26）：伤害门与减伤可并存；多条门取最严格（cap 取更小、免疫取并集）
            MobDamageGate specGate = spec.damageGate();
            if (specGate != null && specGate.active()) {
                gate = (gate == null) ? specGate : merge(gate, specGate);
            }
        }
        // 顺序固定：先减伤（百分比），再伤害门（免疫归零 / 单次封顶 / 超距免疫）
        double damage = best > 0 ? e.getDamage() * (1.0 - Math.min(0.95, best)) : e.getDamage();
        if (gate != null) {
            // 阶段 2「勿听」：攻击者距目标超过 immune_beyond_radius ⇒ 免疫
            double beyond = gate.immuneBeyondRadius();
            if (beyond > 0 && attackerDistance(e, le) > beyond) {
                e.setCancelled(true);
                return;
            }
            DamageCategory category = DamageCategory.of(e.getCause(), magic);
            double gated = gate.apply(damage, category, le.getMaxHealth());
            if (gated <= 0.0 && damage > 0.0) {
                e.setCancelled(true);       // 真免疫：取消事件（不产生 0 伤害的击退/红屏）
                return;
            }
            damage = gated;
        }
        if (Math.abs(damage - e.getDamage()) > 1.0e-9) e.setDamage(damage);
    }

    /**
     * 攻击者到目标的距离（用于 {@code immune_beyond_radius}）：
     * 近战取 damager，投射物取 shooter（玩家射箭时 damager 是箭、shooter 才是人），
     * 拿不到实体（环境伤害）返回 0（＝当作贴身，不被超距门豁免）。
     */
    private static double attackerDistance(EntityDamageEvent e, LivingEntity victim) {
        if (!(e instanceof EntityDamageByEntityEvent byEntity)) return 0.0;
        org.bukkit.entity.Entity damager = byEntity.getDamager();
        if (damager instanceof org.bukkit.entity.Projectile proj
                && proj.getShooter() instanceof org.bukkit.entity.Entity shooter) {
            damager = shooter;
        }
        if (damager == null || damager.getWorld() != victim.getWorld()) return 0.0;
        return damager.getLocation().distance(victim.getLocation());
    }

    /** 两条伤害门合并（cap 取更小，免疫并集，mode 见 {@link MobDamageGate#merge}）。 */
    private static MobDamageGate merge(MobDamageGate a, MobDamageGate b) {
        return MobDamageGate.merge(a, b);
    }
}
