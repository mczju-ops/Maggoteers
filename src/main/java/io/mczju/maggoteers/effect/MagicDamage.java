package io.mczju.maggoteers.effect;

import io.mczju.maggoteers.MaggoteersPlugin;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.metadata.FixedMetadataValue;

/**
 * Magic / ability damage that must land even if the target is still in a vanilla
 * invulnerability window from a prior hit (melee, self-cost, etc.).
 *
 * <p>2026-10-26：伤害生效期间给目标打 {@link #MAGIC_MARKER} metadata，供
 * {@code MobDamageReductionListener} 区分「法术伤害」与「物理伤害」。
 */
public final class MagicDamage {

    /** 魔法伤害标记（仅在 {@link #applyIndependent} 调用栈内存在）。 */
    public static final String MAGIC_MARKER = "maggoteers_magic_damage";

    private MagicDamage() {}

    /**
     * Clear no-damage ticks and last-damage so LivingEntity#damage applies fully.
     */
    public static void applyIndependent(LivingEntity target, double amount, Entity source) {
        if (target == null || amount <= 0) return;
        // 2026-10-26：伤害门硬校验（阶段1 全免疫 / 阶段2 6 格外免疫）。
        // 事件监听器在 HIGH 优先级可能被别的插件/武器链路绕过（实测灾变核心打穿了一阶段无敌），
        // 这里在"造成伤害"的唯一入口再挡一次，保证内容侧的无敌一定生效。
        if (io.mczju.maggoteers.wave.WaveEngine.isTracked(target.getUniqueId())) {
            int phase = io.mczju.maggoteers.mob.BossPhaseTracker.phaseOf(target.getUniqueId());
            for (var spec : io.mczju.maggoteers.mob.MobSkillService.skillsOf(target.getUniqueId())) {
                if (!spec.phaseAllowed(phase)) continue;
                var gate = spec.damageGate();
                if (gate == null || !gate.active()) continue;
                if (gate.immuneTo(io.mczju.maggoteers.mob.DamageCategory.MAGIC)) return;
                if (gate.immuneBeyondRadius() > 0 && source != null
                        && source.getLocation().distance(target.getLocation()) > gate.immuneBeyondRadius()) return;
            }
        }
        target.setNoDamageTicks(0);
        target.setLastDamage(0.0);
        MaggoteersPlugin plugin = MaggoteersPlugin.getInstance();
        if (plugin != null) {
            target.setMetadata(MAGIC_MARKER, new FixedMetadataValue(plugin, Boolean.TRUE));
        }
        try {
            if (source != null) {
                target.damage(amount, source);
            } else {
                target.damage(amount);
            }
        } finally {
            if (plugin != null) {
                target.removeMetadata(MAGIC_MARKER, plugin);
            }
        }
    }
}
