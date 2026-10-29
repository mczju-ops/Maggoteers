package io.mczju.maggoteers.mob;

import io.mczju.maggoteers.effect.ProjectileHomingService;
import io.mczju.maggoteers.effect.SummonParams;
import io.mczju.maggoteers.game.MaggoteersGame;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.SmallFireball;
import org.bukkit.entity.Snowball;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.WitherSkull;
import org.bukkit.util.Vector;

import java.util.Locale;

/** 怪物侧弹道发射：按实体类型分派发射（箭/火球/雪球可 homing；TNT 抛射不 homing）。 */
public final class MobProjectileService {

    private MobProjectileService() {}

    /** initialTarget：attack_target/damage_source/killer 预设的触发上下文目标（可 null）。 */
    public static Projectile shoot(LivingEntity shooter, MaggoteersGame game, EntityType type,
                                   SummonParams.ProjectileParams pp,
                                   String homingTarget, LivingEntity initialTarget) {
        if (shooter == null || shooter.getWorld() == null || pp == null) return null;
        double speed = Math.max(0.1, pp.speed());
        Projectile proj = launch(shooter, type, speed);
        if (proj != null) {
            String t = homingTarget == null ? null : homingTarget.trim().toLowerCase(Locale.ROOT);
            ProjectileHomingService.home(game, proj, initialTarget,
                    t == null ? "nearest_player" : t);
        }
        return proj;
    }

    private static Projectile launch(LivingEntity shooter, EntityType type, double speed) {
        Vector vel = forward(shooter, speed);
        if (type == EntityType.TNT) {
            var world = shooter.getWorld();
            TNTPrimed tnt = world.spawn(shooter.getLocation(), TNTPrimed.class);
            tnt.setFuseTicks(40);
            tnt.setVelocity(vel);
            return null;   // TNT 非 Projectile，不参与 homing
        }
        Class<? extends Projectile> cls = projectileClass(type);
        if (cls == null) return null;
        return shooter.launchProjectile(cls, vel);
    }

    private static Class<? extends Projectile> projectileClass(EntityType type) {
        return switch (type) {
            case ARROW -> Arrow.class;
            case FIREBALL -> Fireball.class;
            case SMALL_FIREBALL -> SmallFireball.class;
            case SNOWBALL -> Snowball.class;
            case WITHER_SKULL -> WitherSkull.class;
            default -> null;
        };
    }

    private static Vector forward(LivingEntity shooter, double speed) {
        return shooter.getLocation().getDirection().normalize().multiply(speed);
    }
}
