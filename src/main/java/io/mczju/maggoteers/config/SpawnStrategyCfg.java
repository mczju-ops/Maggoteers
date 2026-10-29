package io.mczju.maggoteers.config;

import java.util.List;

/** waves.yml 一条 strategy。 */
public record SpawnStrategyCfg(
        String id,
        String displayName,
        List<StepCfg> steps,
        int repeat,
        List<RewardItemCfg> clearReward,
        /** 2026-10-26：Boss 阶段表（空 = 单阶段，行为与旧版一致）。 */
        List<BossPhaseCfg> phases
) {
    /** 旧 5 参构造（无阶段）。 */
    public SpawnStrategyCfg(String id, String displayName, List<StepCfg> steps, int repeat,
                            List<RewardItemCfg> clearReward) {
        this(id, displayName, steps, repeat, clearReward, List.of());
    }

    public SpawnStrategyCfg {
        phases = phases == null ? List.of() : List.copyOf(phases);
        displayName = displayName == null ? "" : displayName;
        steps = List.copyOf(steps);
        clearReward = List.copyOf(clearReward);
    }

    /** 关卡显示名；缺省回落 strategy id。 */
    public String displayNameOrId() {
        return displayName.isBlank() ? id : displayName;
    }
}
