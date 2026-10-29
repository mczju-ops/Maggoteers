package io.mczju.maggoteers.mob;

/**
 * 怪物计数器规格（对称于玩家 CounterSpec，count 用 MobTrigger）。
 * <p>count 取 ATTACK/DAMAGE_TAKEN/KILLED/TICK；SPAWN 无意义（出生即计满）；
 * COUNTER 用于串联（上游计满信号驱动 count==COUNTER 的其它计数器，跳过自身）。
 */
public record MobCounterSpec(
        String id,
        MobTrigger count,
        int amount,
        MobTrigger resetOn,
        MobCondition condition,
        boolean requireArm
) {
    public MobCounterSpec(String id, MobTrigger count, int amount,
                          MobTrigger resetOn, MobCondition condition) {
        this(id, count, amount, resetOn, condition, false);
    }

    public MobCounterSpec {
        id = id == null ? "" : id.trim();
        amount = Math.max(1, amount);
    }

    public boolean triggeredBy(MobTrigger t) {
        return count == t;
    }
}
