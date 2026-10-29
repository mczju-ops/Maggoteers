package io.mczju.maggoteers.achievement;

import java.util.List;
import java.util.Set;

/**
 * 成就判定的**唯一求值处**——全是纯函数，没有任何 Bukkit 依赖，所以能被单测逐条钉住。
 *
 * <p>阈值一律从 `achievements.yml` 的 `value` 传进来；口径与玩家看到的数值面板一致
 * （例如"加成 x3.0 算、x2.9 不算"）。
 */
public final class AchievementRules {

    /** 原版移动速度基础值：200% 加成 = 这个值的 2 倍。 */
    public static final double BASE_MOVEMENT_SPEED = 0.1;

    // ---------------------------------------------------------------- 规则 id（配置里写这些）
    public static final String SPEND_COINS = "spend_coins";
    public static final String REVIVES_AT_WIN = "revives_at_win";
    public static final String TRIPLE_MULTIPLIER = "triple_multiplier";
    public static final String FOUR_ALIVE_EXPERT = "four_alive_expert";
    public static final String ALL_KING_COLLECTIBLES = "all_king_collectibles";
    public static final String RUN_TIME_UNDER_SEC = "run_time_under_sec";
    public static final String MAX_HEALTH_AT_LEAST = "max_health_at_least";
    public static final String SPEED_AT_LEAST = "speed_at_least";
    public static final String UNLOCK_ALL = "unlock_all";
    public static final String SPECTATOR_WIN = "spectator_win";
    public static final String SAVE_TEAMMATES = "save_teammates";
    public static final String HIT_AT_LEAST = "hit_at_least";
    public static final String DEFEAT_HIDDEN_BOSS = "defeat_hidden_boss";
    public static final String COINS_AT_WIN = "coins_at_win";
    public static final String STATISTIC = "statistic";
    public static final String SELF_KILL_NO_REVIVE = "self_kill_no_revive";
    public static final String SOLO_EXPERT = "solo_expert";
    public static final String POTION_COUNT = "potion_count";

    /** 已实现的规则全集（`AchievementConfig` 用它拦下写错的 rule）。 */
    public static final Set<String> HANDLED_RULES = Set.of(
            SPEND_COINS, REVIVES_AT_WIN, TRIPLE_MULTIPLIER, FOUR_ALIVE_EXPERT,
            ALL_KING_COLLECTIBLES, RUN_TIME_UNDER_SEC, MAX_HEALTH_AT_LEAST, SPEED_AT_LEAST,
            UNLOCK_ALL, SPECTATOR_WIN, SAVE_TEAMMATES, HIT_AT_LEAST, DEFEAT_HIDDEN_BOSS,
            COINS_AT_WIN, STATISTIC, SELF_KILL_NO_REVIVE, SOLO_EXPERT, POTION_COUNT);

    private AchievementRules() {}

    // ---------------------------------------------------------------- 通用
    /** 到达阈值（含边界）。 */
    public static boolean atLeast(double actual, double need) {
        return actual >= need;
    }

    // ---------------------------------------------------------------- 通关类
    /** 用时短于阈值（毫秒 vs 秒）。计时缺失（0）不算——宁可不给，也不要发错。 */
    public static boolean runTimeUnderSec(long elapsedMs, double needSec) {
        return elapsedMs > 0 && needSec > 0 && elapsedMs < (long) (needSec * 1000.0);
    }

    /** 物攻 / 魔攻 / 装填速度三个倍率同时达标。 */
    public static boolean tripleMultiplier(double attack, double magic, double reload, double need) {
        return atLeast(attack, need) && atLeast(magic, need) && atLeast(reload, need);
    }

    /** 速度加成倍率（相对原版 0.1 基础值）。 */
    public static boolean speedAtLeast(double movementSpeed, double needMultiplier) {
        if (!(movementSpeed > 0) || !(needMultiplier > 0)) return false;
        return movementSpeed / BASE_MOVEMENT_SPEED >= needMultiplier - 1.0e-9;
    }

    /** 专家 + 开局 4 人 + 通关瞬间 4 人全活。 */
    public static boolean fourAliveExpert(boolean expert, int startedPlayers, int aliveCount) {
        return expert && startedPlayers == 4 && aliveCount == 4;
    }

    /**
     * 天才少年：专家 + 开局 4 人 + 通关瞬间只剩自己活着 + 其余 3 人**都在一层 Boss 倒下前**转为旁观。
     *
     * @param othersDownedInAct1 其余 3 人里"首次倒下时已过层数 == 0"的人数（一层 Boss 未清）
     */
    public static boolean soloExpert(boolean expert, int startedPlayers, int aliveCount,
                                     boolean selfAlive, int othersDownedInAct1, int otherCount) {
        return expert && startedPlayers == 4 && otherCount == 3
                && aliveCount == 1 && selfAlive
                && othersDownedInAct1 == 3;
    }

    /** 集齐指定的藏品（bundle 已折回父项）。 */
    public static boolean kingsCollected(Set<String> owned, List<String> required) {
        if (required == null || required.isEmpty()) return false;
        return owned != null && owned.containsAll(required);
    }

    /** 军需处可解锁项全部已解锁。 */
    public static boolean allUnlocked(Set<String> owned, Set<String> allUnlockable) {
        if (allUnlockable == null || allUnlockable.isEmpty()) return false;
        return owned != null && owned.containsAll(allUnlockable);
    }

    // ---------------------------------------------------------------- 数值类
    public static boolean coinsAtLeast(int coins, double need) {
        return atLeast(coins, need);
    }

    public static boolean savesAtLeast(int saves, double need) {
        return atLeast(saves, need);
    }

    public static boolean revivesAtLeast(int revives, double need) {
        return atLeast(revives, need);
    }

    public static boolean potionCountAtLeast(int count, double need) {
        return atLeast(count, need);
    }

    public static boolean hitAtLeast(double damage, double need) {
        return atLeast(damage, need);
    }

    public static boolean maxHealthAtLeast(double maxHealth, double need) {
        return atLeast(maxHealth, need);
    }

    /** 单局花费金币是否达标。 */
    public static boolean spendCoins(int spentThisRun, double need) {
        return atLeast(spentThisRun, need);
    }
}
