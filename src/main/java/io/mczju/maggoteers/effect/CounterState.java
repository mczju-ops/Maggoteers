package io.mczju.maggoteers.effect;

/** 运行期计数器实例（出生/获取时加载，卸载/移除时丢弃）。 */
public final class CounterState {
    private final CounterSpec spec;
    private int progress;
    /** 墙钟 tick 计数：{@code resetOn} 触发或计满后重启；{@code -1} 表示尚未开始。 */
    private long wallClockStartMs = -1;

    public CounterState(CounterSpec spec) {
        this.spec = spec;
        this.progress = 0;
    }

    public CounterSpec spec() { return spec; }
    public String id() { return spec.id(); }
    public int progress() { return progress; }

    /** 墙钟计数器的起表时刻（毫秒）；整数计数器恒为 -1。 */
    public long wallClockStartMs() { return wallClockStartMs; }

    /**
     * 当前进度比例（0..1），供 UI（物品冷却遮罩）显示计数器完成度。
     * <p>整数计数器 = {@code progress / amount}；{@code count: tick} 且带小数的墙钟计数器按毫秒换算。
     * 未起表（墙钟）或 amount<=0 返回 0。
     */
    public double fraction() {
        if (spec.usesWallClockTick()) {
            if (wallClockStartMs < 0) return 0.0;
            double totalMs = spec.amount() * 1000.0;
            if (totalMs <= 0) return 0.0;
            return clamp01((System.currentTimeMillis() - wallClockStartMs) / totalMs);
        }
        if (spec.amount() <= 0) return 0.0;
        return clamp01(progress / spec.amount());
    }

    private static double clamp01(double v) {
        if (!Double.isFinite(v)) return 0.0;
        return Math.max(0.0, Math.min(1.0, v));
    }

    /** 计满返回 true 并清零（循环语义）；否则 false。 */
    public boolean increment() {
        if (spec.usesWallClockTick()) {
            long now = System.currentTimeMillis();
            if (wallClockStartMs < 0) {
                // 亚秒计数器（count: tick 且 amount 带小数）首个 tick 输入即起表；
                // 否则 wallClockStartMs 永远是 -1，计数器永不开始（P2 一并修掉）。
                wallClockStartMs = now;
                return false;
            }
            if (now - wallClockStartMs >= spec.amount() * 1000.0) {
                wallClockStartMs = now;
                return true;
            }
            return false;
        }
        progress++;
        if (progress >= spec.amount()) {
            progress = 0;
            return true;
        }
        return false;
    }

    public void reset() {
        progress = 0;
        wallClockStartMs = spec.usesWallClockTick() ? System.currentTimeMillis() : -1;
    }
}
