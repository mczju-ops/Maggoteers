package io.mczju.maggoteers.effect;

import com.github.mczjuops.mczjugamecore.game.GameState;
import io.mczju.maggoteers.MaggoteersPlugin;
import io.mczju.maggoteers.game.MaggoteersGame;
import io.mczju.maggoteers.wave.WaveEngine;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.util.Vector;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 弹道 homing：每 tick 把速度重设为目标方向（平滑转向）。
 * <p>homing_target 预设（与 {@link EffectKeys#HOMING_TARGET} 一致）：
 * nearest_enemy（追最近本局怪）| nearest_player（追最近对局玩家）|
 * attack_target / damage_source / killer（追 initialTarget，目标死则回落最近玩家）| 缺省=不追踪。
 * <p>目标死亡/失效/对局结束 → 停止转向（实体保持惯性飞行）。
 */
public final class ProjectileHomingService {

    private static final Map<UUID, HomingEntry> ACTIVE = new ConcurrentHashMap<>();
    private static final double TURN_FACTOR = 0.75;   // 每 tick 目标方向权重（越小越平滑；0.35 实测脱靶，0.75 接近 IM 追踪强度）
    // homing 绝对寿命（tick）：借鉴 IM RangeGhastlySkill entity-lifetime-ticks 默认 100t（5s），取 120t（6s）。
    // 兜底"永远追不上"的弹道：到期停止追踪并移除实体，绝不无限追踪（1.6 speed × 120t = 192 格，足够穿越整层）。
    private static final int MAX_HOMING_AGE_TICKS = 120;

    private record HomingEntry(MaggoteersGame game, String target, LivingEntity initial, double speed, int ageAtHome) {}

    private ProjectileHomingService() {}

    public static void start(MaggoteersPlugin plugin) {
        Bukkit.getScheduler().runTaskTimer(plugin, ProjectileHomingService::homeAll, 1L, 1L);
    }

    /** initialTarget 仅 attack_target/damage_source/killer 预设时使用（可 null）。 */
    public static void home(MaggoteersGame game, Projectile proj,
                            LivingEntity initialTarget, String homingTarget) {
        if (proj == null || game == null) return;
        String t = homingTarget == null ? null : homingTarget.trim().toLowerCase(Locale.ROOT);
        if (t == null || t.isEmpty() || "none".equals(t)) return;
        ACTIVE.put(proj.getUniqueId(),
                new HomingEntry(game, t, initialTarget, proj.getVelocity().length(), proj.getTicksLived()));
    }

    public static void stop(UUID projectileUuid) {
        ACTIVE.remove(projectileUuid);
    }

    private static void homeAll() {
        if (ACTIVE.isEmpty()) return;
        for (var it = ACTIVE.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            UUID pid = e.getKey();
            HomingEntry entry = e.getValue();
            Entity raw = Bukkit.getEntity(pid);
            if (!(raw instanceof Projectile proj) || !proj.isValid() || proj.isDead()) {
                it.remove();
                continue;
            }
            // 弹道不在 WaveEngine.BY_ENTITY（那里只追踪怪）→ 改查发起对局状态：对局已结束则停 homing
            if (entry.game() == null || entry.game().getState() == GameState.END) {
                it.remove();
                continue;
            }
            // Arrow 插地（未命中落墙/落地）：停止追踪，让箭安静插在原地——否则每 tick setVelocity 会把它从地里拽起来乱飞
            if (proj instanceof Arrow arrow && arrow.isInBlock()) {
                it.remove();
                continue;
            }
            // 寿命兜底（借鉴 IM）：追不上目标的弹道到期即止——停止追踪并移除实体，绝不无限追踪
            if (proj.getTicksLived() - entry.ageAtHome() > MAX_HOMING_AGE_TICKS) {
                it.remove();
                proj.remove();
                continue;
            }
            LivingEntity target = resolve(proj.getLocation(), entry);
            if (target == null || target.isDead() || !target.isValid()) {
                continue;   // 无目标则保持当前速度（不再转向）
            }
            Vector aim = aimWithLead(proj.getLocation(), target, entry.speed());
            Vector dir = aim.subtract(proj.getLocation().toVector()).normalize();
            Vector next = steer(proj.getVelocity(), dir, entry.speed(), TURN_FACTOR);
            if (proj instanceof Fireball fb) {
                // Fireball 系列由 direction/acceleration 驱动，setVelocity 不可靠（Paper 会归一化并抖动）
                fb.setDirection(next);
                fb.setAcceleration(next);
            }
            proj.setVelocity(next);
        }
    }

    /**
     * 单 tick 转向：当前速度向目标方向混合 {@code factor} 权重，保持 {@code speed} 大小不变。
     * 纯函数（不修改入参），供 {@link #homeAll} 使用；单独可测。
     */
    static Vector steer(Vector cur, Vector dir, double speed, double factor) {
        return cur.clone().multiply(1 - factor)
                .add(dir.clone().multiply(factor * speed))
                .normalize().multiply(speed);
    }

    /** 目标预测：朝"目标将到位置"瞄准，而非当前位置（追踪移动中玩家）。lead = 距离/速度 的 tick 数。 */
    private static Vector aimWithLead(Location projLoc, LivingEntity target, double speed) {
        Location t = target.getLocation();
        double dist = projLoc.distance(t);
        double leadTicks = Math.max(0.0, dist / speed - 0.5);
        Vector vel = target.getVelocity();
        if (vel == null || vel.lengthSquared() < 1e-4) {
            return t.toVector();
        }
        return t.toVector().clone().add(vel.clone().multiply(leadTicks));
    }

    private static LivingEntity resolve(Location loc, HomingEntry entry) {
        String t = entry.target();
        if ("attack_target".equals(t) || "damage_source".equals(t) || "killer".equals(t)) {
            LivingEntity init = entry.initial();
            if (init != null && !init.isDead() && init.isValid()) return init;
            return nearestPlayer(loc, entry.game());   // 上下文目标已死：回落追最近玩家
        }
        if ("nearest_player".equals(t)) {
            return nearestPlayer(loc, entry.game());
        }
        return WaveEngine.nearestTrackedMob(loc, entry.game(), 64.0);   // nearest_enemy（默认）
    }

    private static LivingEntity nearestPlayer(Location loc, MaggoteersGame game) {
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (var pe : game.getPlayers()) {
            Player p = pe.player();
            if (p == null || !p.isValid() || p.isDead()) continue;
            double d = p.getLocation().distanceSquared(loc);
            if (d < bestDist) {
                bestDist = d;
                best = p;
            }
        }
        return best;
    }
}
