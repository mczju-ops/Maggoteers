package io.mczju.maggoteers.util;

import java.util.Locale;
import java.util.Optional;

/**
 * 本局难度分级（1.3.3）：普通 / 困难 / 专家。
 * <p>命名 GameDifficulty 避开 {@code org.bukkit.Difficulty}（世界难度枚举）。三处消费：
 * {@link io.mczju.maggoteers.config.ScalingConfig}（难度倍率乘数）、
 * {@link io.mczju.maggoteers.reward.CurseService}（进层负面奖励）、
 * {@link io.mczju.maggoteers.mob.MobSkillService}（技能难度过滤）。
 */
public enum GameDifficulty {
    NORMAL(0), HARD(1), EXPERT(2);

    private final int rank;

    GameDifficulty(int rank) {
        this.rank = rank;
    }

    /** 难度档位：NORMAL&lt;HARD&lt;EXPERT。 */
    public int rank() {
        return rank;
    }

    /** 本难度是否 ≥ other（含 other==null，即"不限难度"恒满足）。技能过滤用。 */
    public boolean atLeast(GameDifficulty other) {
        return other == null || rank >= other.rank;
    }

    /** 解析 "normal|hard|expert" 或 0|1|2；未知返回 empty。 */
    public static Optional<GameDifficulty> parse(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        String s = raw.trim();
        try {
            return Optional.of(GameDifficulty.valueOf(s.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return switch (s) {
                case "0" -> Optional.of(NORMAL);
                case "1" -> Optional.of(HARD);
                case "2" -> Optional.of(EXPERT);
                default -> Optional.empty();
            };
        }
    }
}
