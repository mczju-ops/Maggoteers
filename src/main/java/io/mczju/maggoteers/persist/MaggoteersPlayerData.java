package io.mczju.maggoteers.persist;

import com.github.mczjuops.mczjugamecore.player.data.JsonPlayerData;
import java.util.ArrayList;
import java.util.List;

/**
 * 跨局持久化（§13.1，MGC JsonPlayerData 落盘到 player_data/maggoteers/<uuid>.json）。
 * <p>字段必须 public（Gson 反射 + 排行榜按字段名 totalEarned 取值）。改完务必 {@link #setModified(true)}。
 */
public class MaggoteersPlayerData extends JsonPlayerData {
    public int balance = 0;            // 可花费账户货币
    public int totalEarned = 0;        // 累计获得（只增不减 = 排行榜积分）
    public List<String> unlocks = new ArrayList<>();   // 已解锁的 reward option.id
    /** 最佳通关时间(ms)，越小越好；Long.MAX_VALUE = 尚未通关（无记录）。排行榜按此字段升序。 */
    public long bestRunMs = Long.MAX_VALUE;

    // ===== 图鉴（/maggoteers codex，2026-10-30）：账号级进度，跨局累积 =====
    /** 已获得过的武器 option.id（图鉴第三态）。 */
    public List<String> obtainedWeapons = new ArrayList<>();
    /** 已获得过的藏品 option.id（有护符映射的 STAT）。 */
    public List<String> obtainedCollectibles = new ArrayList<>();
    /** 遭遇过的 Boss strategy id（图鉴第二态）。 */
    public List<String> metBosses = new ArrayList<>();
    /** 进入过的层 id（隐藏层 Act4 只在进过之后才在图鉴里出现）。 */
    public List<String> visitedActs = new ArrayList<>();

    public void grant(int amount) {
        if (amount <= 0) return;
        balance += amount;
        totalEarned += amount;
        setModified(true);
    }

    /** 调试：直接设余额（不改 totalEarned）。 */
    public void setBalance(int amount) {
        balance = Math.max(0, amount);
        setModified(true);
    }

    public boolean spend(int amount) {
        if (amount <= 0 || balance < amount) return false;
        balance -= amount;
        setModified(true);
        return true;
    }

    public boolean unlock(String optionId) {
        if (optionId == null || unlocks.contains(optionId)) return false;
        unlocks.add(optionId);
        setModified(true);
        return true;
    }

    /** 更新最佳通关时间。返回是否真的刷新了记录（供"刷新个人记录"通知判定）。 */
    public boolean updateBestRun(long ms) {
        if (ms <= 0 || ms >= bestRunMs) return false;
        bestRunMs = ms;
        setModified(true);
        return true;
    }
}
