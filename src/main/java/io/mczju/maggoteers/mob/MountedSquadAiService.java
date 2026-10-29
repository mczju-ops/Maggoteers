package io.mczju.maggoteers.mob;

import com.github.mczjuops.mczjugamecore.game.AbstractGame;
import io.mczju.maggoteers.MaggoteersPlugin;
import io.mczju.maggoteers.game.MaggoteersGame;
import io.mczju.maggoteers.state.PlayerStateManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;

import java.util.UUID;

/** 骑乘小队：controller 选目标，root 位移；马/骆驼以专属中间档速度驱动（R4）。 */
public final class MountedSquadAiService {

    private MountedSquadAiService() {}

    public static void tick(AbstractGame game) {
        if (!(game instanceof MaggoteersGame)) return;
        var cfg = MaggoteersPlugin.getInstance().getConfig();
        double targetRange = cfg.getDouble("mob_attributes.squad_target_range", 32.0);
        double moveSpeed = cfg.getDouble("mob_attributes.squad_move_speed", 1.0);
        double horseSpeed = cfg.getDouble("mob_attributes.squad_horse_speed", 2.5);   // 替代旧 chargeSpeed(4.0)
        for (MountedSquadRegistry.Squad squad : MountedSquadRegistry.squadsFor(game)) {
            var rootEnt = Bukkit.getEntity(squad.rootUuid());
            var ctrlEnt = Bukkit.getEntity(squad.controllerUuid());
            if (!(rootEnt instanceof LivingEntity root) || root.isDead() || !root.isValid()) {
                MountedSquadRegistry.removeByRoot(game, squad.rootUuid());
                continue;
            }
            if (!(ctrlEnt instanceof Mob controller) || controller.isDead() || !controller.isValid()) {
                MountedSquadRegistry.removeByRoot(game, squad.rootUuid());
                continue;
            }
            Player target = nearestParticipant(game, root.getLocation(), targetRange);
            if (target == null) continue;
            controller.setTarget(target);
            if (root instanceof Mob rootMob) {
                // 马(含骆驼)用专属中间档，其他坐骑用 moveSpeed（R4 冲刺降速：4.0 太快、1.0 太慢 → 2.5）
                rootMob.getPathfinder().moveTo(target, root instanceof AbstractHorse ? horseSpeed : moveSpeed);
            }
        }
    }

    private static Player nearestParticipant(AbstractGame game, Location from, double range) {
        Player best = null;
        double bestD = range * range;
        if (!(game instanceof MaggoteersGame)) return null;
        for (UUID uuid : PlayerStateManager.uuidsInGame(game)) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null || !p.isOnline()) continue;
            if (!PlayerStateManager.isRunParticipant(game, p)) continue;
            if (!p.getWorld().equals(from.getWorld())) continue;
            double d = p.getLocation().distanceSquared(from);
            if (d <= bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }
}
