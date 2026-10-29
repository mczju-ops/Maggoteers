package io.mczju.maggoteers.game;

import com.github.mczjuops.mczjugamecore.game.AbstractGame;
import com.github.mczjuops.mczjugamecore.player.PlayerExt;
import com.github.mczjuops.mczjugamecore.player.strategy.AbstractPlayerQuitStrategy;
import com.github.mczjuops.mczjugamecore.player.strategy.PlayerQuitReason;
import io.mczju.maggoteers.config.MessageService;
import io.mczju.maggoteers.effect.EffectService;
import io.mczju.maggoteers.state.PlayerState;
import io.mczju.maggoteers.state.PlayerStateManager;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

import java.util.Map;

/**
 * 中途退出策略——覆盖 MGC 默认「任何一人退出即整局 abort」（{@code DefaultPlayerQuitStrategy}）。
 * <p>改为：退出玩家标记为「倒下」（alive=false，等同死亡耗尽转观察者），其余玩家继续。
 * 若退出后场上已无人、或局已开局且无存活玩家，则失败结算。
 * <p>⚠️ 退出玩家不在 cleanupRun 的 {@code getPlayers()} 里；MGC {@code leaveGame} 只切 profile
 * （物品栏/XP），<b>不</b>重置本插件的派生效果与游戏模式——故退出时须在此补 per-player 清理：
 * 剥属性/药水效果（防 MAX_HEALTH 加成跨局残留）、观察者模式复位为生存、血量/饥饿复位。
 * 但<b>不能</b>清物品栏：此刻当前 profile 已是 lobby，清空会导致下次 {@code joinGame} 的
 * {@code switchProfile} 把空物品栏写进 lobby profile，永久清空大厅物品。
 */
public class MaggoteersPlayerQuitStrategy extends AbstractPlayerQuitStrategy {

    public MaggoteersPlayerQuitStrategy(AbstractGame game) {
        super(game);
    }

    @Override
    public void onPlayerQuit(PlayerExt player, PlayerQuitReason reason) {
        if (!(game instanceof MaggoteersGame mg)) return;
        var uuid = player.player().getUniqueId();
        Player pl = player.player();

        // 退出玩家不参与 cleanupRun 的 getPlayers() 清理，须在此清 CD、奖励抽签缓存与派生效果，防跨局泄漏（R9/R10/R11）
        io.mczju.maggoteers.item.CooldownService.clear(uuid);
        io.mczju.maggoteers.reward.RewardService.clearDraws(uuid);
        io.mczju.maggoteers.ui.RunScoreboard.clear(pl);
        // 显式传对局：退出玩家已不在 game map，EffectService.currentGame() 查不到 PlayerState
        io.mczju.maggoteers.wave.WaveEngine.hideBossBarsFor(pl);
        EffectService.removeAllFor(pl, mg);
        pl.setGameMode(GameMode.SURVIVAL);   // 死亡转观察者后退出仍处于观察者模式
        MaggoteersGame.resetLobbyVitality(pl);
        // 须在 finishGame/卸图之前送出 maggoteers_*（MGC leave 本身不传送；可跨世界到 lobby）
        // 回退链：房间 return/wait → 入局位置（R：房间未配置时退游不再落主世界）
        MaggoteersRoom.teleportReturn(mg, pl, mg.playerReturnLocation(uuid));

        PlayerState st = PlayerStateManager.get(mg, uuid);
        if (st != null && st.isAlive()) {
            st.markDown();
            mg.sender().warn(MessageService.raw("death.to_down_quit", Map.of("player", pl.getName())));
        }

        // 已无人（含 STATING 窗口 world/PlayerState 尚未初始化的极端情况）→ 真正结束 + 删房
        if (mg.getPlayers().isEmpty()) {
            mg.finishGame();
            return;
        }
        // 局已开局（有 PlayerState）且场上再无存活玩家 → 标记失败、保留房间观战
        if (st != null && !PlayerStateManager.isAnyAlive(mg)) {
            mg.markDefeated();
        }
    }
}
