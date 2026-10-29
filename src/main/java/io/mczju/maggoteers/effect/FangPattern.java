package io.mczju.maggoteers.effect;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 唤魔尖牙法阵的**纯几何**（2026-10-26，门扉缔造者）。
 *
 * <p>三种预设（用户口径）：
 * <ol>
 *   <li>{@code ring_out} —— 以中心为原点，半径 0-2 / 3-4 / 5-6 三波，依次由内向外，每波间隔 {@code waveTicks}；</li>
 *   <li>{@code ring_in} —— 同上，由外向内；</li>
 *   <li>{@code star} —— 沿 x、z 与两条 45° 斜轴，每格一个，向外延伸 {@code maxRadius} 格，
 *       每次间隔 {@code stepTicks}（用户：2 刻生成 10 次）。</li>
 * </ol>
 *
 * <p>每个 {@link Cell} 带一个**相对 tick**（相对施法瞬间）：施法方要在
 * {@code tick - warnTicks()} 处播预警粒子、在 {@code tick} 处判定命中/生成尖牙。
 * 这样"预警按预设播放"（环状/星形逐格亮起）与"命中按格子判定"天然同步。
 *
 * <p>纯函数，不碰 Bukkit：单测直接断言三种预设的格子数与 tick 分布。
 */
public final class FangPattern {

    /**
     * @param tick  相对施法瞬间的 tick（命中时刻）
     * @param dx    相对中心方块的 x 偏移
     * @param dz    相对中心方块的 z 偏移
     */
    public record Cell(int tick, int dx, int dz) {
    }

    /** 环状三波的默认半径带（用户口径：0-2 / 3-4 / 5-6）。 */
    public static final int[][] RING_BANDS = {{0, 2}, {3, 4}, {5, 6}};

    private FangPattern() {
    }

    /**
     * 生成法阵格子。
     *
     * @param preset      {@code ring_out} / {@code ring_in} / {@code star}
     * @param maxRadius   最大半径（环状默认 6；星形延伸格数默认 10）
     * @param waveTicks   环状：相邻两波的间隔；星形：每次外扩的间隔
     * @param warnTicks   预警提前量（仅用于调用方计算预警时刻，不参与几何）
     * @return 按 tick 升序的格子列表
     */
    public static List<Cell> build(String preset, int maxRadius, int waveTicks, int warnTicks) {
        String p = preset == null ? "ring_out" : preset.trim().toLowerCase(Locale.ROOT);
        int wave = Math.max(1, waveTicks);
        List<Cell> out = switch (p) {
            case "ring_in" -> rings(maxRadius, wave, true);
            case "star" -> star(maxRadius <= 0 ? 10 : maxRadius, wave);
            default -> rings(maxRadius, wave, false);
        };
        out.sort(Comparator.comparingInt(Cell::tick)
                .thenComparingInt(Cell::dx).thenComparingInt(Cell::dz));
        return out;
    }

    /** 环状：三个半径带，每带一波。{@code insideOut=false} 时反转顺序。 */
    private static List<Cell> rings(int maxRadius, int waveTicks, boolean insideOut) {
        List<Cell> out = new ArrayList<>();
        int bands = RING_BANDS.length;
        for (int b = 0; b < bands; b++) {
            int waveIndex = insideOut ? (bands - 1 - b) : b;
            int tick = waveIndex * waveTicks;
            int lo = RING_BANDS[b][0];
            int hi = Math.min(RING_BANDS[b][1], Math.max(0, maxRadius));
            if (lo > hi) continue;
            for (int dx = -hi; dx <= hi; dx++) {
                for (int dz = -hi; dz <= hi; dz++) {
                    double d = Math.sqrt((double) dx * dx + (double) dz * dz);
                    if (d < lo - 1.0e-9 || d > hi + 1.0e-9) continue;
                    out.add(new Cell(tick, dx, dz));
                }
            }
        }
        return out;
    }

    /** 星形：中心 + 四条轴（x、z、两条 45°），每格一次，间隔 {@code stepTicks}。 */
    private static List<Cell> star(int steps, int stepTicks) {
        List<Cell> out = new ArrayList<>();
        out.add(new Cell(0, 0, 0));
        for (int i = 1; i <= steps; i++) {
            int tick = i * stepTicks;
            out.add(new Cell(tick, i, 0));
            out.add(new Cell(tick, -i, 0));
            out.add(new Cell(tick, 0, i));
            out.add(new Cell(tick, 0, -i));
            out.add(new Cell(tick, i, i));
            out.add(new Cell(tick, i, -i));
            out.add(new Cell(tick, -i, i));
            out.add(new Cell(tick, -i, -i));
        }
        return out;
    }

    /** 法阵总时长（tick）：最后一个格子的命中时刻。 */
    public static int durationTicks(List<Cell> cells) {
        int max = 0;
        for (Cell c : cells) max = Math.max(max, c.tick());
        return max;
    }
}
