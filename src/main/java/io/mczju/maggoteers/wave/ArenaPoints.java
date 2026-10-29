package io.mczju.maggoteers.wave;

import com.github.mczjuops.mczjugamecore.game.AbstractGame;
import io.mczju.maggoteers.config.MapPoints;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 本层可用"场地锚点"（{@code points.yml} 里的全部刷怪点 + boss 点，绝对坐标）。
 *
 * <p>供 {@code TELEPORT anchor: arena_point} 使用：随机传送到一个"不是自己脚下"的点
 * （哨兵/克雷松的瞬移机制）。进层时由 {@code ActSpawnHelper} 写入，退层/结束时清空。
 */
public final class ArenaPoints {

    private static final Map<AbstractGame, List<Location>> BY_GAME = new IdentityHashMap<>();
    private static final Random RNG = new Random();

    private ArenaPoints() {}

    /** 进层写入：把 points.yml 的全部相对点按层原点转成绝对坐标。 */
    public static void set(AbstractGame game, World world, MapPoints points, Vec3 origin) {
        if (game == null || world == null || points == null) return;
        List<Location> out = new ArrayList<>();
        for (Vec3 rel : points.spawnPoints().values()) {
            // 与刷怪一致：点位是方块坐标，传送到方块中心（否则落在方块角上）
            out.add(new Location(world,
                    io.mczju.maggoteers.util.Coords.blockCenter(origin.x() + rel.x()),
                    origin.y() + rel.y(),
                    io.mczju.maggoteers.util.Coords.blockCenter(origin.z() + rel.z())));
        }
        BY_GAME.put(game, List.copyOf(out));
    }

    public static void clear(AbstractGame game) {
        if (game != null) BY_GAME.remove(game);
    }

    /** 随机一个"离 from 最近的那个点以外"的锚点；没有可用点时返回 null。 */
    public static Location randomOther(AbstractGame game, Location from) {
        List<Location> all = BY_GAME.get(game);
        if (all == null || all.isEmpty()) return null;
        List<Location> candidates = others(all, from);
        if (candidates.isEmpty()) return null;
        return candidates.get(RNG.nextInt(candidates.size()));
    }

    /** 纯逻辑：排除"离 from 最近的那个点"，其余全部作为候选（同距离只排除一个）。 */
    public static List<Vec3> others(List<Vec3> points, Vec3 from) {
        List<Vec3> out = new ArrayList<>();
        if (points == null || points.isEmpty()) return out;
        int closest = -1;
        double best = Double.MAX_VALUE;
        if (from != null) {
            for (int i = 0; i < points.size(); i++) {
                Vec3 p = points.get(i);
                double dx = p.x() - from.x();
                double dz = p.z() - from.z();
                double d = dx * dx + dz * dz;
                if (d < best) {
                    best = d;
                    closest = i;
                }
            }
        }
        for (int i = 0; i < points.size(); i++) {
            if (i == closest) continue;
            out.add(points.get(i));
        }
        return out;
    }

    private static List<Location> others(List<Location> points, Location from) {
        List<Location> out = new ArrayList<>();
        if (points.isEmpty()) return out;
        int closest = -1;
        double best = Double.MAX_VALUE;
        if (from != null) {
            for (int i = 0; i < points.size(); i++) {
                Location l = points.get(i);
                if (from.getWorld() != null && l.getWorld() != null && !from.getWorld().equals(l.getWorld())) continue;
                double d = l.distanceSquared(from);
                if (d < best) {
                    best = d;
                    closest = i;
                }
            }
        }
        for (int i = 0; i < points.size(); i++) {
            if (i == closest) continue;
            out.add(points.get(i));
        }
        return out;
    }
}
