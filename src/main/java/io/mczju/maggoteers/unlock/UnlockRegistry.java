package io.mczju.maggoteers.unlock;

import com.github.mczjuops.mczjugamecore.player.PlayerExt;
import io.mczju.maggoteers.persist.MaggoteersPlayerData;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Set;

/** 读玩家持久 unlocks（局外商店买的），供 RewardService.draw 过滤 requires_unlock。 */
public final class UnlockRegistry {
    private UnlockRegistry() {}

    /** 玩家已解锁的 reward option.id 集（无数据返回空）。 */
    public static Set<String> unlocksOf(Player p) {
        var data = new PlayerExt(p).getData(MaggoteersPlayerData.class);
        return data == null ? new HashSet<>() : new HashSet<>(data.unlocks);
    }
}
