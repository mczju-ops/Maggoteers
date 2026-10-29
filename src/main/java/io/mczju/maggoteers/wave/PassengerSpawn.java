package io.mczju.maggoteers.wave;

import org.bukkit.entity.EntityType;

import java.util.List;

/** 已解析的乘客怪树节点（coeff×scaling）。skills 为 mob_skills.yml 技能 id 列表。 */
public record PassengerSpawn(EntityType type,
                             double hpMult, double dmgMult, double speedMult,
                             double scaleMult, double followRangeMult,
                             List<String> skills,
                             List<MobEquipment> equipment, List<DeathSpawn> onDeath,
                             List<PassengerSpawn> passengers,
                             String name, boolean bossBar, boolean controller) {
    public PassengerSpawn {
        skills = skills == null ? List.of() : List.copyOf(skills);
        equipment = equipment == null ? List.of() : List.copyOf(equipment);
        onDeath = onDeath == null ? List.of() : List.copyOf(onDeath);
        passengers = passengers == null ? List.of() : List.copyOf(passengers);
        if (name != null) {
            name = name.trim();
            if (name.isEmpty()) name = null;
        }
    }

    /** 无嵌套乘客（兼容旧构造）。 */
    public PassengerSpawn(EntityType type, double hpMult, double dmgMult, double speedMult,
                          List<String> skills) {
        this(type, hpMult, dmgMult, speedMult, 1.0, 1.0,
                skills, List.of(), List.of(), List.of(), null, false, false);
    }

    public PassengerSpawn(EntityType type,
                          double hpMult, double dmgMult, double speedMult,
                          double scaleMult, double followRangeMult,
                          List<String> skills,
                          List<MobEquipment> equipment, List<DeathSpawn> onDeath,
                          List<PassengerSpawn> passengers) {
        this(type, hpMult, dmgMult, speedMult, scaleMult, followRangeMult,
                skills, equipment, onDeath, passengers, null, false, false);
    }
}
