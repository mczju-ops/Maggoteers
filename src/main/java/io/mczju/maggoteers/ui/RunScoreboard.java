package io.mczju.maggoteers.ui;

import com.github.mczjuops.mczjugamecore.game.AbstractGame;
import com.github.mczjuops.mczjugamecore.player.PlayerExt;
import io.mczju.maggoteers.MaggoteersPlugin;
import io.mczju.maggoteers.config.MessageService;
import io.mczju.maggoteers.effect.CounterDisplayService;
import io.mczju.maggoteers.game.ClassSelectGate;
import io.mczju.maggoteers.game.MaggoteersGame;
import io.mczju.maggoteers.state.PlayerState;
import io.mczju.maggoteers.state.PlayerStateManager;
import io.mczju.maggoteers.wave.WaveEngine;
import io.mczju.maggoteers.wave.WaveScheduler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.*;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 局内 sidebar：玩家/复活/层/地图/波次/剩余怪 + 自己的计数器。 */
public final class RunScoreboard {
    private static final Map<AbstractGame, BukkitTask> TASKS = new IdentityHashMap<>();

    /**
     * 2026-10-28：sidebar 内容里现在有**每人自己的计数器**，所以必须**每个玩家一块** scoreboard
     * （同一块 board 的 sidebar 内容对所有人相同，无法按玩家区分）。
     * 每块 board 只创建一次并复用（不每秒 `getNewScoreboard()`），只是每秒重建 objective 行。
     */
    private static final Map<UUID, Scoreboard> BOARDS = new java.util.HashMap<>();

    private static Scoreboard boardOf(Player p) {
        return BOARDS.computeIfAbsent(p.getUniqueId(),
                k -> Bukkit.getScoreboardManager().getNewScoreboard());
    }

    /** 清掉面板上的旧条目再重建（文案每秒可能变，不 reset 会残留历史行）。 */
    private static Objective resetSidebar(Scoreboard sb, String title) {
        Objective old = sb.getObjective("maggoteers");
        if (old != null) {
            old.unregister();
        }
        for (String entry : new java.util.ArrayList<>(sb.getEntries())) {
            sb.resetScores(entry);
        }
        Objective obj = sb.registerNewObjective("maggoteers", Criteria.DUMMY, title);
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);
        return obj;
    }

    private RunScoreboard() {}

    public static void start(AbstractGame game) {
        stop(game);
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(MaggoteersPlugin.getInstance(),
                () -> refresh(game), 10L, 20L);
        TASKS.put(game, task);
    }

    public static void stop(AbstractGame game) {
        BukkitTask t = TASKS.remove(game);
        if (t != null) t.cancel();
        for (PlayerExt pe : game.getPlayers()) {
            clear(pe.player());
        }
    }

    /** 单人离开对局时复位 sidebar（此时已不在 {@code game.getPlayers()}）。 */
    public static void clear(Player p) {
        if (p == null) return;
        BOARDS.remove(p.getUniqueId());
        p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
    }

    private static void refresh(AbstractGame game) {
        if (game instanceof MaggoteersGame mg && mg.isInClassSelectPhase()) {
            refreshClassSelect(game);
            return;
        }
        var snap = WaveScheduler.snapshot(game);
        if (snap == null) return;
        int mobs = WaveEngine.livingCount(game);
        int restSec = snap.restSecondsLeft();

        for (PlayerExt pe : game.getPlayers()) {
            Player p = pe.player();
            if (p == null) continue;
            Scoreboard sb = boardOf(p);
            Objective obj = resetSidebar(sb, MessageService.legacy("scoreboard.title", Map.of()));

            int line = 15;
            obj.getScore(MessageService.legacy("scoreboard.line_act", Map.of(
                    "act", String.valueOf(snap.actIndex() + 1),
                    "map", snap.mapId()))).setScore(line--);

            String phase = WaveScheduler.phaseName(snap.phase());
            String waveLine = restSec > 0
                    ? MessageService.legacy("scoreboard.line_wave_resting", Map.of(
                            "wave", String.valueOf(snap.waveIndex() + 1),
                            "wave_count", String.valueOf(snap.waveCount()),
                            "phase", phase,
                            "rest", String.valueOf(restSec)))
                    : MessageService.legacy("scoreboard.line_wave", Map.of(
                            "wave", String.valueOf(snap.waveIndex() + 1),
                            "wave_count", String.valueOf(snap.waveCount()),
                            "phase", phase));
            obj.getScore(waveLine).setScore(line--);
            if (snap.strategyDisplayName() != null && !snap.strategyDisplayName().isBlank()) {
                obj.getScore(MessageService.legacy("scoreboard.line_strategy", Map.of(
                        "strategy", snap.strategyDisplayName()))).setScore(line--);
            }
            obj.getScore(MessageService.legacy("scoreboard.line_mobs", Map.of(
                    "mobs", String.valueOf(mobs)))).setScore(line--);
            obj.getScore(MessageService.legacy("scoreboard.separator", Map.of())).setScore(line--);

            for (PlayerExt o : game.getPlayers()) {
                Player op = o.player();
                if (op == null) continue;
                PlayerState st = PlayerStateManager.get(game, op.getUniqueId());
                int rev = st == null ? 0 : st.getReviveCount();
                int kills = st == null ? 0 : st.killCount();
                String statusKey = statusKeyFor(st);
                obj.getScore(playerLine(statusKey, op.getName(), rev, kills)).setScore(line--);
            }

            // 计数器段（2026-10-28）：只显示**该玩家自己**的计数器，追加在玩家列表下方。
            PlayerState mine = PlayerStateManager.get(game, p.getUniqueId());
            List<String> counters = CounterDisplayService.sidebarLines(p, mine);
            if (!counters.isEmpty()) {
                // 和上面的分隔线同文案会合并成同一 entry → 末尾加不可见后缀做唯一键
                obj.getScore(uniqueEntry(MessageService.legacy("scoreboard.separator", Map.of()), 1))
                        .setScore(line--);
                int i = 0;
                for (String text : counters) {
                    if (line < 0) break;
                    // entry 唯一化：不同计数器可能同名同值，靠不可见后缀区分
                    obj.getScore(uniqueEntry(text, ++i)).setScore(line--);
                }
            }

            p.setScoreboard(sb);
        }
    }

    /**
     * 2026-10-28：sidebar entry 的唯一化后缀。
     * <p>以前用 **U+200B 零宽空格**——Minecraft 没有这个字形，会画成 {@code ZWSP} 的
     * **缺失字形占位框**（玩家截图里数字/“完好”右边的方框，行 1 一个、行 2 两个、行 3 三个）。
     * 改成重复原版颜色码 {@code §r}：渲染上零宽度，但字符串不同，
     * {@code Objective#getScore} 仍会当成不同 entry（同一文案不会互相覆盖）。
     */
    static String uniqueEntry(String text, int n) {
        String base = text == null ? "" : text;
        return n <= 0 ? base : base + "§r".repeat(n);
    }

    /**
     * 侧边栏状态位：倒下（观察者）→ {@code [观]}；存活 → 职业标记
     *（acquiredUnique 里的 {@code class_*} bundle id → config {@code scoreboard.<id>}，如 class_vanguard→[战]）。
     * 无职业/未配置标记时回落 {@code [战]}。
     */
    static String statusKeyFor(PlayerState st) {
        if (st == null || !st.isAlive()) return "scoreboard.status_spec";
        for (String id : st.acquiredUnique()) {
            if (id.startsWith("class_")) {
                String key = "scoreboard." + id;
                if (MessageService.hasKey(key)) return key;
            }
        }
        return "scoreboard.status_fight";
    }

    private static void refreshClassSelect(AbstractGame game) {
        for (PlayerExt pe : game.getPlayers()) {
            Player p = pe.player();
            if (p == null) continue;
            Scoreboard sb = boardOf(p);
            Objective obj = resetSidebar(sb, MessageService.legacy("scoreboard.title", Map.of()));
            int line = 10;
            obj.getScore(MessageService.legacy("scoreboard.class_header", Map.of())).setScore(line--);
            obj.getScore(MessageService.legacy("scoreboard.class_hint", Map.of())).setScore(line--);
            obj.getScore(MessageService.legacy("scoreboard.separator", Map.of())).setScore(line--);
            for (PlayerExt o : game.getPlayers()) {
                Player op = o.player();
                if (op == null) continue;
                PlayerState st = PlayerStateManager.get(game, op.getUniqueId());
                int rev = st == null ? 0 : st.getReviveCount();
                String statusKey = ClassSelectGate.hasChosen(game, op.getUniqueId())
                        ? "scoreboard.class_chosen" : "scoreboard.class_pending";
                obj.getScore(playerLine(statusKey, op.getName(), rev, 0)).setScore(line--);
            }
            p.setScoreboard(sb);
        }
    }

    /**
     * Status keys carry MiniMessage colors; nesting them into {@code player_line} would
     * escape those tags. Legacy-render status, then append player_line with empty status.
     */
    private static String playerLine(String statusKey, String name, int revives, int kills) {
        String status = MessageService.legacy(statusKey, Map.of());
        String rest = MessageService.legacy("scoreboard.player_line", Map.of(
                "status", "",
                "name", name,
                "revives", String.valueOf(revives),
                "kills", String.valueOf(kills)));
        return status + rest;
    }
}
