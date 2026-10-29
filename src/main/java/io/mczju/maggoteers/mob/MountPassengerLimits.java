package io.mczju.maggoteers.mob;

import org.bukkit.entity.AbstractCubeMob;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;

/** 坐骑族：直接乘客上限与 spawn 后处理。 */
public final class MountPassengerLimits {

    private MountPassengerLimits() {}

    public static int directPassengerLimit(EntityType type) {
        // Camel 族座位 2（CAMEL_HUSK 是 Camel 子类，须随族）；其余含全部 AbstractHorse 子类/Strider 默认 1。
        // 未来新增 Camel 族实体类型须同步加入本分支与 MountPassengerLimitsTest（spec §8）。
        if (type == EntityType.CAMEL || type == EntityType.CAMEL_HUSK) return 2;
        return 1;
    }

    public static int directPassengerLimit(LivingEntity carrier) {
        return carrier == null ? 0 : directPassengerLimit(carrier.getType());
    }

    public static void prepareMount(LivingEntity le) {
        if (le instanceof Mob m) {
            m.setAware(true);
            le.setRemoveWhenFarAway(false);
        }
        if (le instanceof AbstractHorse horse) {
            horse.setAdult();
            // 不再 setTamed(true)：原版僵尸骑士/骆驼队为未驯服态；车辆由 AiService moveTo 驱动（R4）
        }
        // 26.2 起 MagmaCube 不再继承 Slime；AbstractCubeMob 统一史莱姆/岩浆怪
        if (le instanceof AbstractCubeMob cube && cube.getSize() < 2) {
            cube.setSize(2);
        }
    }
}
