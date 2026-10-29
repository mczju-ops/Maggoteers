package io.mczju.maggoteers.game;

import com.github.mczjuops.mczjugamecore.game.AbstractGame;
import com.github.mczjuops.mczjugamecore.game.room.JsonGameRoom;
import io.mczju.maggoteers.MaggoteersPlugin;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * MGC 房间数据（{@code plugins/MCZJUGameCore/rooms/maggoteers/<name>.json}）。
 * <p>字段须 {@code public}，类型仅 Location / 包装类。MGC 1.0.7 编辑菜单不认 {@code @FieldDescription}。
 * 运维：{@code /mgcop room create maggoteers <名>}，{@code /mgcop room edit maggoteers <名>}。
 */
public final class MaggoteersRoom extends JsonGameRoom {

    /** 等待阶段集合点；未设置则 join 后不传送。 */
    public Location waitLocation;

    /** 离开对局 / 局末回程点（可跨世界，须指向 lobby 等已加载世界）；未设置则回退 waitLocation。 */
    public Location returnLocation;

    public static MaggoteersRoom of(AbstractGame game) {
        if (game == null) return null;
        return game.getGameRoom() instanceof MaggoteersRoom r ? r : null;
    }

    static void teleportWait(AbstractGame game, Player player) {
        MaggoteersRoom room = of(game);
        teleport(player, room == null ? null : room.waitLocation);
    }

    /**
     * 送回大厅：优先 {@link #returnLocation}，否则 {@link #waitLocation}，再否则 {@code fallback}（玩家入局前位置），
     * 最后兜底主世界 spawn。Bukkit {@code teleport(Location)} 可跨世界（源世界可为 maggoteers_*）。
     * <p>⚠️ 回退链必要：房间未配 return/wait 时（default.json 为空）若不加兜底，玩家会留在即将被卸载/删除的
     * maggoteers 世界，世界销毁时被 Bukkit 自动送回主世界 spawn（历史 bug：abort/清理后落主世界）。
     */
    static void teleportReturn(AbstractGame game, Player player) {
        teleportReturn(game, player, null);
    }

    /** 带回退位置的回程；{@code fallback} 通常为 {@code MaggoteersGame.playerReturnLocation(uuid)}。 */
    static void teleportReturn(AbstractGame game, Player player, Location fallback) {
        Location dest = null;
        MaggoteersRoom room = of(game);
        if (room != null) {
            if (room.returnLocation != null && room.returnLocation.getWorld() != null) dest = room.returnLocation;
            else if (room.waitLocation != null && room.waitLocation.getWorld() != null) dest = room.waitLocation;
        }
        if (dest == null && fallback != null && fallback.getWorld() != null) dest = fallback;
        if (dest == null) {
            var worlds = org.bukkit.Bukkit.getWorlds();
            if (!worlds.isEmpty()) dest = worlds.get(0).getSpawnLocation();
        }
        teleport(player, dest);
    }

    private static void teleport(Player player, Location loc) {
        if (player == null || loc == null) return;
        // Gson 反序列化后若世界名无效，getWorld() 为 null，跨世界 tp 会静默失败
        if (loc.getWorld() == null) {
            MaggoteersPlugin plug = MaggoteersPlugin.getInstance();
            if (plug != null) {
                plug.getLogger().warning("房间传送目标世界未加载，跳过: " + loc);
            }
            return;
        }
        player.teleport(loc);
    }
}
