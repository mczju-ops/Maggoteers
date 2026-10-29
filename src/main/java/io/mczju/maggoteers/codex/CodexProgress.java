package io.mczju.maggoteers.codex;

import com.github.mczjuops.mczjugamecore.game.AbstractGame;
import com.github.mczjuops.mczjugamecore.player.PlayerExt;
import io.mczju.maggoteers.persist.MaggoteersPlayerData;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 图鉴进度（跨局持久化）：见过哪些武器/藏品、遭遇过哪些 Boss、进过哪几层。
 *
 * <p>写在 {@link MaggoteersPlayerData}（MGC 的 JSON 档，每 30 分钟 + 退游戏 + 关服落盘），
 * 所以"获得过"是**账号级**的：本局没拿到、以前拿到过，图鉴里照样显示已获得。
 *
 * <p>记账点只有三处（避免到处开花）：
 * <ul>
 *   <li>{@link io.mczju.maggoteers.reward.RewardService#apply} 成功时 → 武器/藏品"已获得"；</li>
 *   <li>{@link io.mczju.maggoteers.wave.WaveScheduler#enterAct} → 全员"进过这层"；</li>
 *   <li>{@link io.mczju.maggoteers.wave.WaveScheduler#beginWave} 的 boss 波 → 全员"遭遇过这 Boss"。</li>
 * </ul>
 * 全部在主线程调用（菜单与波次状态机都在主线程），无需加锁。
 */
public final class CodexProgress {

    /** 四本账（对应 {@link MaggoteersPlayerData} 的四个列表字段）。 */
    enum Ledger { WEAPON, COLLECTIBLE, BOSS, ACT }

    private CodexProgress() {}

    /** 读玩家持久档；没装 MGC / 玩家无档时返回 {@code null}。 */
    public static MaggoteersPlayerData dataOf(Player player) {
        if (player == null) return null;
        try {
            return new PlayerExt(player).getData(MaggoteersPlayerData.class);
        } catch (RuntimeException ex) {
            return null;   // 大厅未初始化 / 测试环境：图鉴仍能开，只是全部显示"未获得"
        }
    }

    // ---------------------------------------------------------------- 读

    public static Set<String> obtainedWeapons(MaggoteersPlayerData d) {
        return toSet(list(d, Ledger.WEAPON));
    }

    public static Set<String> obtainedCollectibles(MaggoteersPlayerData d) {
        return toSet(list(d, Ledger.COLLECTIBLE));
    }

    public static Set<String> metBosses(MaggoteersPlayerData d) {
        return toSet(list(d, Ledger.BOSS));
    }

    public static Set<String> visitedActs(MaggoteersPlayerData d) {
        return toSet(list(d, Ledger.ACT));
    }

    // ---------------------------------------------------------------- 写（单人，可单测）

    public static boolean markWeapon(MaggoteersPlayerData d, String optionId) {
        return mark(d, Ledger.WEAPON, optionId);
    }

    public static boolean markCollectible(MaggoteersPlayerData d, String optionId) {
        return mark(d, Ledger.COLLECTIBLE, optionId);
    }

    public static boolean markBoss(MaggoteersPlayerData d, String strategyId) {
        return mark(d, Ledger.BOSS, strategyId);
    }

    public static boolean markAct(MaggoteersPlayerData d, String actId) {
        return mark(d, Ledger.ACT, actId);
    }

    /** 幂等追加；返回是否新记了一笔。 */
    static boolean mark(MaggoteersPlayerData d, Ledger ledger, String value) {
        if (d == null || ledger == null || value == null || value.isBlank()) return false;
        List<String> cur = list(d, ledger);
        if (cur == null) {
            cur = new ArrayList<>();
            store(d, ledger, cur);
        }
        if (cur.contains(value)) return false;
        cur.add(value);
        d.setModified(true);
        return true;
    }

    // ---------------------------------------------------------------- 写（整局）

    /** 记"这把武器/这个藏品本局拿到过"给某个玩家（{@code RewardService.apply} 用）。 */
    public static void markWeapon(Player player, String optionId) {
        markWeapon(dataOf(player), optionId);
    }

    public static void markCollectible(Player player, String optionId) {
        markCollectible(dataOf(player), optionId);
    }

    /** 本局全员记"遭遇过这个 Boss"（Boss 波开始时调用）。 */
    public static void markBossForAll(AbstractGame game, String strategyId) {
        if (game == null || strategyId == null || strategyId.isBlank()) return;
        for (MaggoteersPlayerData d : allData(game)) markBoss(d, strategyId);
    }

    /** 本局全员记"进过这一层"（{@code WaveScheduler.enterAct} 调用）。 */
    public static void markActForAll(AbstractGame game, String actId) {
        if (game == null || actId == null || actId.isBlank()) return;
        for (MaggoteersPlayerData d : allData(game)) markAct(d, actId);
    }

    /** 清空某人的图鉴进度（调试用：{@code /maggoteers debug codex reset}）。 */
    public static void reset(MaggoteersPlayerData d) {
        if (d == null) return;
        d.obtainedWeapons = new ArrayList<>();
        d.obtainedCollectibles = new ArrayList<>();
        d.metBosses = new ArrayList<>();
        d.visitedActs = new ArrayList<>();
        d.setModified(true);
    }

    private static List<MaggoteersPlayerData> allData(AbstractGame game) {
        List<MaggoteersPlayerData> out = new ArrayList<>();
        for (PlayerExt pe : game.getPlayers()) {
            try {
                Player p = pe.player();
                if (p == null || !p.isOnline()) continue;
                MaggoteersPlayerData d = dataOf(p);
                if (d != null) out.add(d);
            } catch (RuntimeException ignored) {
                // 单个玩家读档失败不影响其余人
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- 内部

    private static List<String> list(MaggoteersPlayerData d, Ledger ledger) {
        if (d == null || ledger == null) return null;
        return switch (ledger) {
            case WEAPON -> d.obtainedWeapons;
            case COLLECTIBLE -> d.obtainedCollectibles;
            case BOSS -> d.metBosses;
            case ACT -> d.visitedActs;
        };
    }

    private static void store(MaggoteersPlayerData d, Ledger ledger, List<String> value) {
        switch (ledger) {
            case WEAPON -> d.obtainedWeapons = value;
            case COLLECTIBLE -> d.obtainedCollectibles = value;
            case BOSS -> d.metBosses = value;
            case ACT -> d.visitedActs = value;
        }
    }

    private static Set<String> toSet(List<String> list) {
        return list == null || list.isEmpty() ? Set.of() : Set.copyOf(list);
    }
}
