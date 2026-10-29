package io.mczju.maggoteers.state;

import io.mczju.maggoteers.effect.CounterState;
import io.mczju.maggoteers.effect.PlayerEffect;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 单局单玩家**本局真相源**（§19）：reviveCount（剩余自动复活次数）+ alive（冒险=参战 / 观察=倒下）。
 * <p>Plan 5 起在此扩展效果列表；Plan 6 起扩展 acquiredUnique 集。
 */
public final class PlayerState {
    private final UUID uuid;
    private int reviveCount;
    private boolean alive;
    private int killCount = 0;   // 本局击杀数（侧边栏统计 + DYNAMIC_ATTRIBUTE source=kills）
    private int hitsTaken = 0;   // 本局受敌人攻击次数（DYNAMIC_ATTRIBUTE source=hits_taken）
    private int deaths = 0;      // 本局死亡次数（含自动复活，DYNAMIC_ATTRIBUTE source=deaths）
    private int wavesCleared = 0; // 本局清波数（DYNAMIC_ATTRIBUTE source=waves_cleared）
    private int actsCleared = 0;  // 本局已过层数（DYNAMIC_ATTRIBUTE source=acts_cleared）
    private final List<PlayerEffect> effects = new ArrayList<>();
    private final Set<String> acquiredUnique = new HashSet<>();
    private final Map<String, CounterState> counters = new HashMap<>();

    public PlayerState(UUID uuid, int reviveCount) {
        this.uuid = uuid;
        this.reviveCount = Math.max(0, reviveCount);
        this.alive = true;
    }

    public UUID uuid() { return uuid; }
    public int getReviveCount() { return reviveCount; }
    public void setReviveCount(int n) { this.reviveCount = Math.max(0, n); }
    /**
     * 2026-10-28：本次近战是"蓄力那一刀"（票券被消耗）——它不应该再计入下一轮蓄力进度，
     * 否则切肉刀会退化成 A A Q A Q A Q（应为 A A Q A A Q）。由 {@code fireCounter} 消耗一次。
     */
    private boolean skipAttackCounterOnce;

    public void skipAttackCounterOnce() {
        skipAttackCounterOnce = true;
    }

    /** 取走"本次不计入蓄力"标记（消耗式）。 */
    public boolean consumeSkipAttackCounterOnce() {
        boolean v = skipAttackCounterOnce;
        skipAttackCounterOnce = false;
        return v;
    }

    public boolean isAlive() { return alive; }
    public void setAlive(boolean alive) { this.alive = alive; }

    public List<PlayerEffect> effects() { return effects; }
    public void addEffect(PlayerEffect e) { effects.add(e); }
    public void removeEffect(String id) { effects.removeIf(e -> e.id().equals(id)); }
    public void clearEffects() { effects.clear(); acquiredUnique.clear(); }

    public Set<String> acquiredUnique() { return acquiredUnique; }
    public void addAcquiredUnique(String id) { acquiredUnique.add(id); }

    public int killCount() { return killCount; }
    public void incrementKills() { killCount++; }

    public int hitsTaken() { return hitsTaken; }
    public void incrementHitsTaken() { hitsTaken++; }

    public int deaths() { return deaths; }
    public void incrementDeaths() { deaths++; }

    public int wavesCleared() { return wavesCleared; }
    public void incrementWavesCleared() { wavesCleared++; }

    public int actsCleared() { return actsCleared; }

    /** 已过层数由进层序号直接赋值（幂等；调试跳层/重进同层不会重复累计）。 */
    public void setActsCleared(int n) { this.actsCleared = Math.max(0, n); }

    public Map<String, CounterState> counters() { return counters; }

    /**
     * 2026-10-28（吟游诗人「每第四发音波弹 300%」）：本局每件武器 {@code use_ability} 的**成功使用次数**。
     * 只用于"N 发强化"，不写侧边栏、不参与 CounterService。
     */
    private final Map<String, Integer> weaponUses = new HashMap<>();

    /** 已成功使用次数（不含本次）。 */
    public int weaponUses(String itemId) {
        if (itemId == null) return 0;
        return weaponUses.getOrDefault(itemId, 0);
    }

    /** 记一次成功使用，返回累计次数（含本次）。 */
    public int incrementWeaponUse(String itemId) {
        if (itemId == null) return 0;
        return weaponUses.merge(itemId, 1, Integer::sum);
    }

    /** 死亡时调：有复活次数则消耗一次、保持存活，返回 true；否则返回 false（调用方再 markDown）。 */
    public boolean tryAutoRevive() {
        if (reviveCount > 0) {
            reviveCount--;
            alive = true;
            return true;
        }
        return false;
    }

    /** 复活次数耗尽：标记为倒下（观察者）。 */
    public void markDown() { alive = false; }

    // ================================================================ 成就（2026-10-30，单局计数）
    // 这些计数**只活一局**：随 PlayerState 一起销毁，跨局进度由 MGC 的成就存档负责。

    private int coinsSpent = 0;        // 本局花掉的普通货币（3 选 1 商店；被诅咒夺走的不算）
    private int revivesGiven = 0;      // 本局用复活币救起死者的次数
    private double maxHitDealt = 0.0;  // 本局单次最高伤害（物理 + 法术）
    private int firstDownedAct = -1;   // 首次转旁观时"已过层数"（0 = 一层 Boss 未清）；-1 = 没倒下过
    private long lastSelfDamageMs = 0L; // 最近一次被自己的效果打中的时间戳

    public int coinsSpent() { return coinsSpent; }

    public int incrementCoinsSpent() { return ++coinsSpent; }

    public int revivesGiven() { return revivesGiven; }

    public int incrementRevivesGiven() { return ++revivesGiven; }

    public double maxHitDealt() { return maxHitDealt; }

    /** 记一次伤害，返回本局新高（不更新时返回原值）。 */
    public double recordHit(double damage) {
        if (damage > maxHitDealt) maxHitDealt = damage;
        return maxHitDealt;
    }

    public int firstDownedAct() { return firstDownedAct; }

    /** 只记第一次倒下（重进观战不会覆盖）。 */
    public void markFirstDownedAt(int actsCleared) {
        if (firstDownedAct < 0) firstDownedAct = Math.max(0, actsCleared);
    }

    public void markSelfDamage() { lastSelfDamageMs = System.currentTimeMillis(); }

    /** 最近一次自伤是否还在有效窗内（用于"被自己的效果打死"）。 */
    public boolean selfDamageFresh(long windowMs) {
        return lastSelfDamageMs > 0 && System.currentTimeMillis() - lastSelfDamageMs <= windowMs;
    }
}
