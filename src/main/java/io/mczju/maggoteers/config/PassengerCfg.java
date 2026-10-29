package io.mczju.maggoteers.config;

import io.mczju.maggoteers.wave.MobEquipment;
import org.bukkit.entity.EntityType;

import java.util.List;

/** waves.yml step.passengers[] 条目（可嵌套 passengers / on_death）。skills 为技能 id 列表。 */
public record PassengerCfg(EntityType type, int count, CoeffCfg coeff, List<String> skills,
                           List<MobEquipment> equipment, List<DeathSpawnCfg> onDeath,
                           List<PassengerCfg> passengers,
                           String name, boolean bossBar, boolean controller) {
    public PassengerCfg {
        skills = skills == null ? List.of() : List.copyOf(skills);
        equipment = equipment == null ? List.of() : List.copyOf(equipment);
        onDeath = onDeath == null ? List.of() : List.copyOf(onDeath);
        passengers = passengers == null ? List.of() : List.copyOf(passengers);
        if (count < 1) count = 1;
        if (name != null) {
            name = name.trim();
            if (name.isEmpty()) name = null;
        }
    }

    /** 扁平条目（无嵌套）。 */
    public PassengerCfg(EntityType type, int count, CoeffCfg coeff, List<String> skills) {
        this(type, count, coeff, skills, List.of(), List.of(), List.of(), null, false, false);
    }

    /** 兼容旧 8 参构造。 */
    public PassengerCfg(EntityType type, int count, CoeffCfg coeff, List<String> skills,
                        List<MobEquipment> equipment, List<DeathSpawnCfg> onDeath,
                        List<PassengerCfg> passengers) {
        this(type, count, coeff, skills, equipment, onDeath, passengers, null, false, false);
    }
}
