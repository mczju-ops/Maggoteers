package io.mczju.maggoteers.listener;

import com.github.mczjuops.mczjugamecore.player.PlayerExt;
import io.mczju.maggoteers.game.MaggoteersGame;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * 局内规则守卫：
 * <ul>
 *   <li>禁止对局内旁观者使用观察者传送（SPECTATE cause）——死亡转观察者只能跟随/固定视角，不可自由瞬移。</li>
 * </ul>
 * <p>末影箱不再禁止打开（地图里可放置末影箱供玩家使用）；跨局残留由 {@code cleanupRun}
 * 在对局结束时清空末影箱解决（见 MaggoteersGame.cleanupRun）。
 */
public final class RunRulesListener implements Listener {

    private static boolean inRun(Player player) {
        return player != null && new PlayerExt(player).isInGame(MaggoteersGame.class);
    }

    /** 旁观者点击实体/传送（SPECTATE cause）一律拦下：玩家已在本局固定观战，不可自由传送。 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpectateTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.SPECTATE) return;
        if (!inRun(player)) return;
        if (player.getGameMode() != GameMode.SPECTATOR) return;
        event.setCancelled(true);
    }
}
