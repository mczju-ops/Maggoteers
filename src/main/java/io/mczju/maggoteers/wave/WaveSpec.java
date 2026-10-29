package io.mczju.maggoteers.wave;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 一波：steps 按序配置；整体可 strategy-{@code repeat} 轮。
 * <p>展开语义：每个刷怪点（{@link SpawnStep#point()}）各有独立时间轴；
 * 同点上的 step（含 {@link SpawnStep#repeat()}）串行累加 {@code delay}；
 * 不同点并行。strategy-{@code repeat} 每一轮内各点时钟从本轮起点归零，
 * 下一轮起点 = 上一轮最晚刷怪时刻。
 */
public record WaveSpec(
        String strategyId,
        String displayName,
        List<SpawnStep> steps,
        int repeat,
        List<RewardItem> clearReward,
        /** 2026-10-26：Boss 阶段表（空 = 单阶段）。 */
        List<io.mczju.maggoteers.config.BossPhaseCfg> phases
) {
    /** 兼容旧构造（无阶段）。 */
    public WaveSpec(String strategyId, String displayName, List<SpawnStep> steps, int repeat,
                    List<RewardItem> clearReward) {
        this(strategyId, displayName, steps, repeat, clearReward, List.of());
    }
    public WaveSpec {
        phases = phases == null ? List.of() : List.copyOf(phases);
        strategyId = strategyId == null ? "" : strategyId;
        displayName = displayName == null ? "" : displayName;
        steps = List.copyOf(steps);
        clearReward = clearReward == null ? List.of() : List.copyOf(clearReward);
        repeat = Math.max(1, repeat);
    }

    /** 测试/占位：无 strategy id。 */
    public WaveSpec(List<SpawnStep> steps, int repeat, List<RewardItem> clearReward) {
        this("", "", steps, repeat, clearReward, List.of());
    }

    /** 关卡显示名；缺省回落 strategy id。 */
    public String displayNameOrId() {
        return displayName.isBlank() ? strategyId : displayName;
    }

    /**
     * 展平成有序 spawn 序列（按绝对 tick 排序，同刻保持配置出现顺序）。
     * <p>配置里 {@code delay}（秒→tick）表示相对<strong>同刷怪点</strong>上一刷怪时刻的等待
     *（该点尚无事件时相对本轮起点）。展开后 {@link SpawnStep#delayTicks()} 为距本波开始的绝对 tick；
     * {@link SpawnStep#repeat()} 为 1。
     */
    public List<SpawnStep> expand() {
        List<SpawnStep> out = new ArrayList<>();
        int roundStart = 0;
        for (int r = 0; r < repeat; r++) {
            Map<Vec3, Integer> lastAt = new HashMap<>();
            int roundEnd = roundStart;
            for (SpawnStep s : steps) {
                int stepRepeat = Math.max(1, s.repeat());
                for (int sr = 0; sr < stepRepeat; sr++) {
                    int prev = lastAt.getOrDefault(s.point(), roundStart);
                    int at = prev + s.delayTicks();
                    lastAt.put(s.point(), at);
                    if (at > roundEnd) roundEnd = at;
                    out.add(new SpawnStep(
                            s.point(), s.type(), s.count(),
                            s.hpMult(), s.dmgMult(), s.speedMult(),
                            s.scaleMult(), s.followRangeMult(),
                            at, s.skills(),
                            s.equipment(), s.onDeath(), s.passengers(), 1,
                            s.name(), s.bossBar(), s.barColor(), s.barOverlay()));
                }
            }
            roundStart = roundEnd;
        }
        out.sort(Comparator.comparingInt(SpawnStep::delayTicks));
        return out;
    }
}
