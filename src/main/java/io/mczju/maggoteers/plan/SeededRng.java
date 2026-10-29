package io.mczju.maggoteers.plan;

import java.util.Random;

/**
 * 确定性随机：包装 {@link Random}，同 seed 同序列，供 RunPlanner 种子化预生成（可重放/可调试）。
 * <p>{@link #derive(long)} 用 {@code seed ^ salt} 派生独立子序列，让各层/各池 roll 互不干扰且稳定。
 */
public final class SeededRng {
    private final long seed;
    private final Random random;

    public SeededRng(long seed) {
        this.seed = seed;
        this.random = new Random(seed);
    }

    public long seed() { return seed; }

    public int nextInt(int bound) { return random.nextInt(bound); }

    public double nextDouble() { return random.nextDouble(); }

    /** 加权随机挑选索引（weights 全 0 或空 → 0）。 */
    public int weightedIndex(double[] weights) {
        double total = 0;
        for (double w : weights) total += Math.max(0, w);
        if (total <= 0) return 0;
        double r = random.nextDouble() * total;
        double acc = 0;
        for (int i = 0; i < weights.length; i++) {
            double w = Math.max(0, weights[i]);
            if (w <= 0) continue;
            acc += w;
            if (r < acc) return i;
        }
        return weights.length - 1;
    }

    /** 派生子序列：混合 seed 与 salt，降低 {@code seed ^ salt} 跨层/跨波碰撞。 */
    public SeededRng derive(long salt) {
        return new SeededRng(mix64(seed + salt * 0x9E3779B97F4A7C15L));
    }

    private static long mix64(long z) {
        z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
        z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
        return z ^ (z >>> 33);
    }
}
