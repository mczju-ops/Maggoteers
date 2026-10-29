package io.mczju.maggoteers.effect;

/**
 * 计数器规格（effect + trigger 中继器）。
 * <p>{@code countTrigger} 为输入；计满<b>只输出 {@code ON_COUNTER}</b>（由 CounterService
 * 对绑定该计数器 id 的被动 fire）。{@code resetOn} 为该 trigger 触发时清零（null=不自动清零；
 * 但计满即清零——秒表/循环语义）。{@code condition} 为该 trigger 计数时才评估（不满足则本次不计数）。
 */
public record CounterSpec(
        String id,
        CounterCountTrigger countTrigger,
        double amount,
        Trigger resetOn,
        CounterCondition condition
) {
    public CounterSpec {
        id = id == null ? "" : id.trim();
        if (usesWallClockTick(countTrigger, amount)) {
            amount = Math.max(0.001, amount);
        } else {
            amount = Math.max(1, (int) Math.round(amount));
        }
    }

    public boolean triggeredBy(CounterCountTrigger t) {
        return countTrigger == t;
    }

    /** {@code count:tick} 且 amount 带小数时用墙钟秒数（支持 3.2s 等亚秒精度）。 */
    public boolean usesWallClockTick() {
        return usesWallClockTick(countTrigger, amount);
    }

    private static boolean usesWallClockTick(CounterCountTrigger trigger, double threshold) {
        return trigger == CounterCountTrigger.TICK && threshold != Math.floor(threshold);
    }
}
