package io.mczju.maggoteers.effect;

/**
 * 一条效果实例（§10.3）。生命周期=事件到期（trigger 驱动）。
 *
 * <ul>
 *   <li>{@code fireTrigger=null} → 常驻型（apply 时把属性/药水写进派生视图）。
 *   <li>{@code fireTrigger≠null} → 触发型（该 trigger 触发时执行 effect）。</li>
 *   <li>{@code expiryTrigger=null} → 整局有效；非空 → 该 trigger 触发时按 {@code expiryCharges} 扣除/移除。</li>
 *   <li>{@code expiryCharges=-1} → 首次 expiryTrigger 触发即移除；>0 → 倒计到 0 移除。</li>
 *   <li>{@code recurringIntervalSec>0} → 由每秒心跳（{@code EffectService.tickGame} → {@link RecurringClock}）
 *       按内部计时累计，每满一个间隔生成 {@code recurringSpawn} 子效果。</li>
 * </ul>
 */
public final class PlayerEffect {
    private final String id;
    private final Effect effect;
    private final EffectContext params;
    private final Trigger fireTrigger;
    private final Trigger expiryTrigger;
    private int expiryCharges;
    private final int recurringIntervalSec;
    private final PlayerEffect recurringSpawn;
    private final Stack stack;
    /** Raised when a higher-cap pool re-applies the same UPGRADE_LEVEL id. */
    private int upgradeMax;
    private int level;
    private final int cooldownSec;

    public PlayerEffect(String id, Effect effect, EffectContext params, Trigger fireTrigger,
                        Trigger expiryTrigger, int expiryCharges,
                        int recurringIntervalSec, PlayerEffect recurringSpawn,
                        Stack stack, int upgradeMax, int cooldownSec) {
        this.id = id; this.effect = effect; this.params = params; this.fireTrigger = fireTrigger;
        this.expiryTrigger = expiryTrigger; this.expiryCharges = expiryCharges;
        this.recurringIntervalSec = recurringIntervalSec; this.recurringSpawn = recurringSpawn;
        this.stack = stack; this.upgradeMax = upgradeMax; this.level = 1; this.cooldownSec = cooldownSec;
    }

    // 只读
    public String id() { return id; }
    public Effect effect() { return effect; }
    public EffectContext params() { return params; }
    public Trigger fireTrigger() { return fireTrigger; }
    public Trigger expiryTrigger() { return expiryTrigger; }
    public int expiryCharges() { return expiryCharges; }
    public int recurringIntervalSec() { return recurringIntervalSec; }
    public PlayerEffect recurringSpawn() { return recurringSpawn; }
    public Stack stack() { return stack; }
    public int upgradeMax() { return upgradeMax; }
    public int cooldownSec() { return cooldownSec; }

    // 可变（堆叠/到期用）
    public int level() { return level; }
    public void setLevel(int l) { this.level = l; }
    public void setUpgradeMax(int max) { this.upgradeMax = Math.max(1, max); }
    public void setExpiryCharges(int c) { this.expiryCharges = c; }

    public boolean isPermanent() { return fireTrigger == null; }  // 常驻型
}
