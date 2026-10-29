package io.mczju.maggoteers.world;

import com.github.mczjuops.mczjugamecore.game.AbstractGame;
import io.mczju.maggoteers.MaggoteersPlugin;
import io.mczju.maggoteers.plan.ActPlan;
import io.mczju.maggoteers.plan.RunPlanner;
import io.mczju.maggoteers.state.PlayerStateManager;
import io.mczju.maggoteers.wave.Vec3;
import io.mczju.maggoteers.world.MapRepository.MapEntry;
import org.bukkit.Location;
import org.bukkit.World;

/**
 * 进层：粘贴该层 structure.nbt 并传送全员到 playerSpawn（职业选择前的特殊休整期）。
 *
 * <p>层 id 直接取 {@link ActPlan#actId()}（剧本自带），原点取 {@link RunPlanner#loadRunConfig}
 * 的同一份 {@code RunConfig}。历史实现在这里用 {@code "act" + (index + 1)} 现推层 id，并
 * <b>第二次</b>独立读配置、缺键时静默回落 {@code (0,64,0)}（= Act1 原点）——加层时会让新层
 * 结构粘在第一层上，且两处配置解析可能不一致。
 */
public final class ActSpawnHelper {
    private ActSpawnHelper() {}

    /** 已粘贴过的层（每局一份）。 */
    private static final java.util.Map<AbstractGame, java.util.Set<String>> PASTED =
            new java.util.IdentityHashMap<>();

    /** 清掉该局的粘贴记录（收尾时调用，防止 IdentityHashMap 泄漏）。 */
    public static void clearGame(AbstractGame game) {
        PASTED.remove(game);
    }

    /**
     * 2026-10-28：在**休整期**预粘贴下一层（主线程仍会卡 ~1s，但玩家正在看菜单，
     * 而不是传送落地那一瞬间）。进层时 pasteAndTeleport 发现已粘贴就只做传送。
     */
    public static void prepasteNext(AbstractGame game, java.util.List<ActPlan> acts, int currentIndex) {
        if (game == null || acts == null) return;
        int next = currentIndex + 1;
        if (next < 0 || next >= acts.size()) return;
        ActPlan act = acts.get(next);
        if (act == null) return;
        World w = WorldService.get(game);
        if (w == null) return;
        pasteOnce(game, w, act);
    }

    /** 只粘贴、不传送；已粘过则跳过。返回是否新粘贴。 */
    private static boolean pasteOnce(AbstractGame game, World w, ActPlan act) {
        String key = act.actId() + "@" + act.mapId();
        var done = PASTED.computeIfAbsent(game, k -> new java.util.HashSet<>());
        if (!done.add(key)) return false;
        String actId = act.actId();
        Vec3 origin = RunPlanner.loadRunConfig(MaggoteersPlugin.getInstance()).origin(actId);
        boolean fallback = MaggoteersPlugin.getInstance().getConfig().getBoolean("map.fallback_platform", true);
        MapEntry map = MapRepository.getMaps(actId).stream()
                .filter(m -> m.mapId().equals(act.mapId())).findFirst().orElse(null);
        if (map != null) {
            StructurePaster.pasteAct(w, (int) origin.x(), (int) origin.y(), (int) origin.z(),
                    map.dir(), map, fallback);
        }
        return true;
    }

    public static void pasteAndTeleport(AbstractGame game, ActPlan act) {
        World w = WorldService.get(game);
        if (w == null || act == null) return;
        pasteOnce(game, w, act);
        // 2026-10-28：场地锚点必须在**进层**时登记（预粘贴未来层时不能覆盖当前层的锚点）
        String actId0 = act.actId();
        Vec3 origin0 = RunPlanner.loadRunConfig(MaggoteersPlugin.getInstance()).origin(actId0);
        MapEntry map0 = MapRepository.getMaps(actId0).stream()
                .filter(m -> m.mapId().equals(act.mapId())).findFirst().orElse(null);
        if (map0 != null) {
            io.mczju.maggoteers.wave.ArenaPoints.set(game, w, map0.points(), origin0);
        }
        var spawn = act.playerSpawn();
        Location loc = new Location(w,
                io.mczju.maggoteers.util.Coords.blockCenter(spawn.x()), spawn.y(),
                io.mczju.maggoteers.util.Coords.blockCenter(spawn.z()));
        game.getPlayers().forEach(pe -> {
            pe.player().teleport(loc);
            PlayerStateManager.clearFallFire(pe.player());
        });
    }
}
