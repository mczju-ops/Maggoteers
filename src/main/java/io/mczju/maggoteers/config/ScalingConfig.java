package io.mczju.maggoteers.config;

import io.mczju.maggoteers.plan.SeededRng;
import io.mczju.maggoteers.util.GameDifficulty;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.EnumMap;
import java.util.Map;

/** 人数缩放配置（借鉴前代 VampireSurvivor ScalingConfig）。onEnable 调一次。 */
public final class ScalingConfig {

    /** 某人数下的缩放快照。 */
    public record Scaling(double mobHp, double mobDamage, double mobSpeed, double mobCount) {
        /** 难度倍率乘法：mobSpeed 不参与难度（保持人数数组原值）。 */
        public Scaling times(DifficultyMultipliers m) {
            if (m == null) return this;
            return new Scaling(mobHp * m.mobHp(), mobDamage * m.mobDamage(), mobSpeed, mobCount * m.mobCount());
        }
    }

    /** 每难度倍率（1.3.3）：乘在人数缩放之上。mob_speed 无难度倍率。 */
    public record DifficultyMultipliers(double mobHp, double mobDamage, double mobCount) {
        public static final DifficultyMultipliers IDENTITY = new DifficultyMultipliers(1.0, 1.0, 1.0);
    }

    private static ScalingConfig INSTANCE;
    private final double[] mobHp, mobDamage, mobSpeed, mobCount;
    private final Map<GameDifficulty, DifficultyMultipliers> difficultyMultipliers;

    public static void load(JavaPlugin plugin) {
        INSTANCE = new ScalingConfig(
                read(plugin, "scaling.mob_hp",        new double[]{1.0, 1.3, 1.6, 2.0}),
                read(plugin, "scaling.mob_damage",   new double[]{1.0, 1.15, 1.3, 1.5}),
                read(plugin, "scaling.mob_speed",    new double[]{1.0, 1.0, 1.0, 1.0}),
                read(plugin, "scaling.mob_count",    new double[]{1.0, 1.0, 1.2, 1.5}),
                readDifficultyMultipliers(plugin));
        plugin.getLogger().info("ScalingConfig 已加载。");
    }

    private static Map<GameDifficulty, DifficultyMultipliers> readDifficultyMultipliers(JavaPlugin plugin) {
        Map<GameDifficulty, DifficultyMultipliers> out = new EnumMap<>(GameDifficulty.class);
        ConfigurationSection root = plugin.getConfig().getConfigurationSection("difficulty.multipliers");
        if (root == null) return out;
        for (String key : root.getKeys(false)) {
            GameDifficulty.parse(key).ifPresent(d -> {
                ConfigurationSection sec = root.getConfigurationSection(key);
                if (sec == null) return;
                out.put(d, new DifficultyMultipliers(
                        sec.getDouble("mob_hp", 1.0),
                        sec.getDouble("mob_damage", 1.0),
                        sec.getDouble("mob_count", 1.0)));
            });
        }
        return out;
    }

    private static double[] read(JavaPlugin plugin, String path, double[] def) {
        var list = plugin.getConfig().getDoubleList(path);
        return list.isEmpty() ? def : list.stream().mapToDouble(Double::doubleValue).toArray();
    }

    public static ScalingConfig getInstance() {
        if (INSTANCE == null) throw new IllegalStateException("ScalingConfig 未加载！");
        return INSTANCE;
    }

    /** 1–4 人，越界 clamp 到端点。默认 NORMAL 难度（倍率恒等）。 */
    public Scaling scaleFor(int playerCount) {
        return scaleFor(playerCount, GameDifficulty.NORMAL);
    }

    /** 1–4 人 × 难度倍率（1.3.3）。 */
    public Scaling scaleFor(int playerCount, GameDifficulty difficulty) {
        GameDifficulty d = difficulty == null ? GameDifficulty.NORMAL : difficulty;
        Scaling base = new Scaling(
                at(mobHp, playerCount), at(mobDamage, playerCount), at(mobSpeed, playerCount),
                at(mobCount, playerCount));
        return base.times(difficultyMultipliers.getOrDefault(d, DifficultyMultipliers.IDENTITY));
    }

    private static double at(double[] arr, int playerCount) {
        int idx = Math.max(0, Math.min(playerCount - 1, arr.length - 1));
        return arr[idx];
    }

    /**
     * count-roll（D6）：{@code floor(base × m) + (rng.nextDouble() < frac ? 1 : 0)}。
     * 补 1 的随机走 {@link SeededRng}，保证同 seed 可重放。
     */
    public static int rollCount(int base, double mobCountMult, SeededRng rng) {
        double scaled = base * mobCountMult;
        int floor = (int) Math.floor(scaled);
        double frac = scaled - floor;
        return Math.max(0, floor + (rng.nextDouble() < frac ? 1 : 0));
    }

    /** 测试用：直接构造（绕过 Bukkit）。默认 NORMAL 倍率恒等。 */
    public static ScalingConfig forTesting(double[] hp, double[] dmg, double[] spd, double[] count) {
        return new ScalingConfig(hp, dmg, spd, count, new EnumMap<>(GameDifficulty.class));
    }

    /** 测试用：带难度倍率的构造（绕过 Bukkit）。 */
    public static ScalingConfig forTesting(double[] hp, double[] dmg, double[] spd, double[] count,
                                           Map<GameDifficulty, DifficultyMultipliers> difficulty) {
        return new ScalingConfig(hp, dmg, spd, count, difficulty);
    }

    private ScalingConfig(double[] mobHp, double[] mobDamage, double[] mobSpeed, double[] mobCount,
                          Map<GameDifficulty, DifficultyMultipliers> difficultyMultipliers) {
        this.mobHp = mobHp;
        this.mobDamage = mobDamage;
        this.mobSpeed = mobSpeed;
        this.mobCount = mobCount;
        this.difficultyMultipliers = difficultyMultipliers == null ? new EnumMap<>(GameDifficulty.class)
                : difficultyMultipliers;
    }
}
