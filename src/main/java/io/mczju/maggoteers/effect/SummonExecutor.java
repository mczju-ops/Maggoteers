package io.mczju.maggoteers.effect;

import io.mczju.maggoteers.game.MaggoteersGame;
import io.mczju.maggoteers.mob.MobFactory;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.Wolf;
import org.bukkit.util.Vector;

import java.util.logging.Logger;

/** Spawns and registers SUMMON entities (spec rev.2). */
public final class SummonExecutor {

    private static final Logger LOG = Logger.getLogger("Maggoteers");

    private SummonExecutor() {}

    public static int spawn(Player caster, MaggoteersGame game, SummonParams params, TriggerContext ctx) {
        if (caster == null || game == null || params == null) return 0;
        Location base = resolveAnchorLocation(caster, params.anchor(), ctx);
        if (base == null || base.getWorld() == null) return 0;
        base = base.clone().add(0, params.offsetY(), 0);

        int spawned = 0;
        for (int i = 0; i < params.count(); i++) {
            Location at = MobFactory.spawnPadLocation(base, i);
            Entity entity;
            try {
                entity = at.getWorld().spawnEntity(at, params.entityType());
            } catch (Exception ex) {
                LOG.warning("SUMMON spawn failed: " + ex.getMessage());
                continue;
            }
            if (entity instanceof LivingEntity summoned) {
                io.mczju.maggoteers.mob.SpiderClimbGuard.mark(summoned);   // 召唤的蜘蛛同样禁止爬墙
            }
            applyAttributes(entity, params);
            if (params.tamed() && entity instanceof Tameable tame) {
                tame.setOwner(caster);
                tame.setTamed(true);
                if (entity instanceof Wolf wolf) wolf.setAngry(false);
            }
            applyProjectile(entity, caster, game, params);
            SummonRegistry.register(game, entity, caster.getUniqueId(),
                    params.cleanup(), params.durationSec(), params.friendlyFire());
            spawned++;
        }
        return spawned;
    }

    static Location resolveAnchorLocation(Player caster, String anchor, TriggerContext ctx) {
        if (BuffAreaTargets.ANCHOR_DEATH_SITE.equals(anchor)) {
            if (ctx == null || ctx.eventLocation() == null || ctx.eventLocation().getWorld() == null) {
                return null;
            }
            return ctx.eventLocation();
        }
        if (BuffAreaTargets.TARGET_SELF.equals(anchor)) {
            return TriggerContext.resolveOrigin(caster, ctx);
        }
        if (BuffAreaTargets.TARGET_HIT_TARGET.equals(anchor)) {
            if (ctx == null || ctx.hitTarget() == null) return null;
            return ctx.hitTarget().getLocation();
        }
        if ("attacker".equals(anchor)) {
            if (ctx == null || ctx.attacker() == null) return null;
            return ctx.attacker().getLocation();
        }
        return caster.getLocation();
    }

    private static void applyAttributes(Entity entity, SummonParams params) {
        if (!(entity instanceof LivingEntity le)) return;
        for (var e : params.attributes().entrySet()) {
            AttributeInstance inst = le.getAttribute(e.getKey());
            if (inst == null) {
                LOG.warning("SUMMON: entity " + params.entityType() + " lacks attribute " + e.getKey());
                continue;
            }
            inst.setBaseValue(e.getValue());
        }
    }

    private static void applyProjectile(Entity entity, Player caster, MaggoteersGame game, SummonParams params) {
        SummonParams.ProjectileParams proj = params.projectile();
        if (!(entity instanceof Projectile projectile)) return;
        projectile.setShooter(caster);
        Vector dir = caster.getLocation().getDirection().normalize().multiply(proj.speed());
        projectile.setVelocity(dir);
        if (params.homingTarget() != null) {
            // 玩家右键无触发上下文目标 → initialTarget 传 null
            ProjectileHomingService.home(game, projectile, null, params.homingTarget());
        }
    }
}