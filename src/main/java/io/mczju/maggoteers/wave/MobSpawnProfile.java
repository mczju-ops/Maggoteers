package io.mczju.maggoteers.wave;

import java.util.List;

/** 运行时怪物档案：技能 id 列表 + 死亡召唤列表 + 是否挂 Boss 血条。 */
public record MobSpawnProfile(List<String> skills, List<DeathSpawn> onDeath, boolean bossBar,
                              /** D11：血条颜色/样式名；null = 默认。 */
                              String barColor, String barOverlay) {
    public MobSpawnProfile {
        skills = skills == null ? List.of() : List.copyOf(skills);
        onDeath = onDeath == null ? List.of() : List.copyOf(onDeath);
    }

    /** 旧 3 参构造（默认血条外观）。 */
    public MobSpawnProfile(List<String> skills, List<DeathSpawn> onDeath, boolean bossBar) {
        this(skills, onDeath, bossBar, null, null);
    }

    public MobSpawnProfile(List<DeathSpawn> onDeath) {
        this(List.of(), onDeath, false, null, null);
    }

    /** 血条外观（D11）；未配置时回落默认红/普通进度条。 */
    public io.mczju.maggoteers.mob.BossBarStyle barStyle() {
        return io.mczju.maggoteers.mob.BossBarStyle.parse(barColor, barOverlay);
    }

    public static MobSpawnProfile fromStep(SpawnStep step) {
        return new MobSpawnProfile(step.skills(), step.onDeath(), step.bossBar(),
                step.barColor(), step.barOverlay());
    }

    public static MobSpawnProfile fromPassenger(PassengerSpawn spawn) {
        return new MobSpawnProfile(spawn.skills(), spawn.onDeath(), spawn.bossBar(), null, null);
    }

    public static MobSpawnProfile fromDeathSpawn(DeathSpawn spawn) {
        return new MobSpawnProfile(spawn.skills(), spawn.onDeath(), spawn.bossBar(), null, null);
    }
}
