package io.mczju.maggoteers.config;

import io.mczju.maggoteers.wave.MobEquipment;
import org.bukkit.entity.EntityType;

import java.util.List;

public record DeathSpawnCfg(EntityType type, int count, CoeffCfg coeff, List<String> skills,
                            List<MobEquipment> equipment, List<PassengerCfg> passengers,
                            List<DeathSpawnCfg> onDeath,
                            String name, boolean bossBar) {
    public DeathSpawnCfg {
        skills = skills == null ? List.of() : List.copyOf(skills);
        equipment = equipment == null ? List.of() : List.copyOf(equipment);
        passengers = passengers == null ? List.of() : List.copyOf(passengers);
        onDeath = onDeath == null ? List.of() : List.copyOf(onDeath);
        if (count < 1) count = 1;
        if (name != null) {
            name = name.trim();
            if (name.isEmpty()) name = null;
        }
    }

    /** 扁平条目（无嵌套）。 */
    public DeathSpawnCfg(EntityType type, int count, CoeffCfg coeff, List<String> skills) {
        this(type, count, coeff, skills, List.of(), List.of(), List.of(), null, false);
    }

    /** 兼容旧 8 参构造。 */
    public DeathSpawnCfg(EntityType type, int count, CoeffCfg coeff, List<String> skills,
                         List<MobEquipment> equipment, List<PassengerCfg> passengers,
                         List<DeathSpawnCfg> onDeath) {
        this(type, count, coeff, skills, equipment, passengers, onDeath, null, false);
    }
}
