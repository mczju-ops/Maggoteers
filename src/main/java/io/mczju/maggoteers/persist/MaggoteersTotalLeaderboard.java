package io.mczju.maggoteers.persist;

import com.github.mczjuops.mczjugamecore.player.data.AbstractPlayerData;
import com.github.mczjuops.mczjugamecore.score.leaderboard.AutoRefresh;
import com.github.mczjuops.mczjugamecore.score.leaderboard.PlayerDataLeaderboard;
import io.mczju.maggoteers.config.MessageService;

import java.util.Locale;
import java.util.Map;

/** 累计获得（totalEarned）降序排行榜（§13.5）。展示实体由运维 /mgcop leaderboard create 放置。
 *  ⚠️ @AutoRefresh 必需：MGC 的 startAutoRefresh 只刷新标注该注解的榜。 */
@AutoRefresh
public class MaggoteersTotalLeaderboard extends PlayerDataLeaderboard {
    @Override public String getTitle() {
        return MessageService.raw("leaderboard.title", Map.of());
    }

    @Override public String getSubtitle() {
        return MessageService.raw("leaderboard.subtitle", Map.of());
    }

    @Override protected Class<? extends AbstractPlayerData> getPlayerDataClass() { return MaggoteersPlayerData.class; }
    @Override protected String getFieldName() { return "totalEarned"; }

    @Override public String renderLine(int index, String name, double value) {
        boolean op = LeaderboardDisplayText.mgcNameIsOp(name);
        String plainName = LeaderboardDisplayText.stripMgcNameColor(name);
        String score = String.format(Locale.ROOT, "%.0f", value);
        return LeaderboardDisplayText.line(index, plainName, score, op);
    }

    @Override public String renderEmpty() {
        return MessageService.raw("leaderboard.empty", Map.of());
    }
}
