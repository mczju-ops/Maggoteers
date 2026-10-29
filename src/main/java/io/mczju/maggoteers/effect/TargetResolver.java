package io.mczju.maggoteers.effect;

import io.mczju.maggoteers.game.AllyTargeting;
import io.mczju.maggoteers.game.MaggoteersGame;
import io.mczju.maggoteers.wave.WaveEngine;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Resolves magic effect targets (AURA-aligned allies / enemies rules). */
public final class TargetResolver {

    private TargetResolver() {}

    public static List<LivingEntity> collect(MaggoteersGame game, LivingEntity source, Location center,
                                               double radius, Effect effect, EffectContext params) {
        EffectContext p = MagicEffectParams.withDefaults(effect, params);
        List<LivingEntity> out = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        if (AuraParams.targetsAllies(p) && source instanceof Player player) {
            boolean includeSelf = AuraParams.includeSelf(p);
            for (Player ally : AllyTargeting.alliesInRadius(game, player, radius, includeSelf)) {
                if (seen.add(ally.getUniqueId())) {
                    out.add(ally);
                }
            }
        }
        if (AuraParams.targetsEnemies(p)) {
            for (LivingEntity mob : enemiesInRange(source, center, radius, AuraParams.enemyScope(p))) {
                if (seen.add(mob.getUniqueId())) {
                    out.add(mob);
                }
            }
        }
        return out;
    }

    /** Single-entity check for beam sampling (same rules as {@link #collect}). */
    public static boolean isTarget(MaggoteersGame game, LivingEntity source, LivingEntity candidate, EffectContext params) {
        if (source == null || candidate == null || params == null) return false;
        EffectContext p = params;
        if (candidate instanceof Player pl) {
            if (!AuraParams.targetsAllies(p)) return false;
            if (!(source instanceof Player player)) return false;
            return AllyTargeting.alliesInRadius(game, player, Double.MAX_VALUE, AuraParams.includeSelf(p))
                    .stream().anyMatch(a -> a.getUniqueId().equals(pl.getUniqueId()));
        }
        if (!AuraParams.targetsEnemies(p)) return false;
        if (AuraParams.ENEMY_TRACKED.equals(AuraParams.enemyScope(p))) {
            return WaveEngine.isTracked(candidate.getUniqueId());
        }
        return true;
    }

    private static List<LivingEntity> enemiesInRange(LivingEntity carrier, Location center, double radius, String scope) {
        List<LivingEntity> out = new ArrayList<>();
        if (center == null || center.getWorld() == null) {
            return out;
        }
        for (Entity en : center.getWorld().getNearbyEntities(center, radius, radius, radius)) {
            if (!(en instanceof LivingEntity le) || en instanceof Player) {
                continue;
            }
            if (carrier != null && en.equals(carrier)) {
                continue;
            }
            if (AuraParams.ENEMY_TRACKED.equals(scope) && !WaveEngine.isTracked(le.getUniqueId())) {
                continue;
            }
            out.add(le);
        }
        return out;
    }
}