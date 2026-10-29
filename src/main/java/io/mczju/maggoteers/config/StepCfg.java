package io.mczju.maggoteers.config;

import io.mczju.maggoteers.wave.MobEquipment;
import org.bukkit.entity.EntityType;

import java.util.List;

/** waves.yml 一条 step（未解析）。skills 为技能 id 列表（RunPlanner 直传）。 */
public record StepCfg(String point, EntityType type, int count, CoeffCfg coeff,
                      int delaySec, List<String> skills,
                      List<MobEquipment> equipment, List<DeathSpawnCfg> onDeath,
                      List<PassengerCfg> passengers, int repeat,
                      String name, boolean bossBar,
                      /** D11（2026-10-26）：血条颜色/样式（BossBar.Color/Overlay 名）；null = 默认 RED/PROGRESS。 */
                      String barColor, String barOverlay) {
    public StepCfg(String point, EntityType type, int count, CoeffCfg coeff,
                   int delaySec, List<String> skills) {
        this(point, type, count, coeff, delaySec, skills,
                List.of(), List.of(), List.of(), 1, null, false, null, null);
    }

    /** 兼容旧调用：无 step 级 repeat / name / boss_bar 时默认。 */
    public StepCfg(String point, EntityType type, int count, CoeffCfg coeff,
                   int delaySec, List<String> skills,
                   List<MobEquipment> equipment, List<DeathSpawnCfg> onDeath,
                   List<PassengerCfg> passengers) {
        this(point, type, count, coeff, delaySec, skills,
                equipment, onDeath, passengers, 1, null, false, null, null);
    }

    public StepCfg(String point, EntityType type, int count, CoeffCfg coeff,
                   int delaySec, List<String> skills,
                   List<MobEquipment> equipment, List<DeathSpawnCfg> onDeath,
                   List<PassengerCfg> passengers, int repeat) {
        this(point, type, count, coeff, delaySec, skills,
                equipment, onDeath, passengers, repeat, null, false, null, null);
    }

    public StepCfg {
        skills = skills == null ? List.of() : List.copyOf(skills);
        equipment = equipment == null ? List.of() : List.copyOf(equipment);
        onDeath = onDeath == null ? List.of() : List.copyOf(onDeath);
        passengers = passengers == null ? List.of() : List.copyOf(passengers);
        repeat = Math.max(1, repeat);
        if (name != null) {
            name = name.trim();
            if (name.isEmpty()) name = null;
        }
    }
}
