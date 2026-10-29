package io.mczju.maggoteers.persist;

import com.github.mczjuops.mczjugamecore.player.data.AbstractPlayerData;
import com.github.mczjuops.mczjugamecore.score.leaderboard.AutoRefresh;
import com.github.mczjuops.mczjugamecore.score.leaderboard.LeaderboardEntry;
import com.github.mczjuops.mczjugamecore.score.leaderboard.PlayerDataLeaderboard;
import com.github.mczjuops.mczjugamecore.score.leaderboard.SortOrder;
import io.mczju.maggoteers.config.MessageService;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 最佳通关时间排行榜（bestRunMs 升序，越小越好）。展示实体由运维
 * /mgcop leaderboard create maggoteers_time &lt;displayId&gt; 放置，Axiom 客户端可微调。
 * <p>⚠️ @AutoRefresh 必需：MGC 的 startAutoRefresh 只刷新标注该注解的榜；
 * 缺注解则永不自动更新（仅 /mgcop leaderboard edit 保存时刷一次）。
 */
@AutoRefresh
public class MaggoteersRunTimeLeaderboard extends PlayerDataLeaderboard {
    /** 反射取到的 double 中，Long.MAX_VALUE 近似此值；>= 它视为"无记录"。 */
    public static final double NO_RECORD = 9e17;

    @Override public String getTitle() {
        return MessageService.raw("leaderboard.time_title", Map.of());
    }

    @Override public String getSubtitle() {
        return MessageService.raw("leaderboard.time_subtitle", Map.of());
    }

    @Override protected Class<? extends AbstractPlayerData> getPlayerDataClass() { return MaggoteersPlayerData.class; }
    @Override protected String getFieldName() { return "bestRunMs"; }

    @Override public SortOrder getSortOrder() {
        return SortOrder.ASCENDING;
    }

    /** 仅展示有通关记录的玩家（与 {@code /maggoteers time} 一致，排除 bestRunMs=MAX）。 */
    @Override public List<LeaderboardEntry> fetchEntries() {
        List<LeaderboardEntry> out = new ArrayList<>();
        for (LeaderboardEntry e : super.fetchEntries()) {
            if (e.value() >= NO_RECORD) {
                continue;
            }
            out.add(new LeaderboardEntry(e.playerName(), e.value()));
        }
        return out;
    }

    @Override public String renderLine(int index, String name, double value) {
        boolean op = LeaderboardDisplayText.mgcNameIsOp(name);
        String plainName = LeaderboardDisplayText.stripMgcNameColor(name);
        String time = formatMs((long) value);
        return LeaderboardDisplayText.line(index, plainName, time, op);
    }

    @Override public String renderEmpty() {
        return MessageService.raw("command.time_empty", Map.of());
    }

    /** 毫秒 → 时:分:秒（h:mm:ss）。 */
    public static String formatMs(long ms) {
        long totalSec = Math.max(0, ms) / 1000;
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        return String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s);
    }
}
