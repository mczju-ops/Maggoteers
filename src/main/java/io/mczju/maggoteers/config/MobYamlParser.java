package io.mczju.maggoteers.config;

import io.mczju.maggoteers.util.GameRegistries;
import io.mczju.maggoteers.wave.MobEquipment;
import org.bukkit.entity.EntityType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public final class MobYamlParser {
    private static final int MAX_NEST_DEPTH = 8;

    private MobYamlParser() {}

    public static CoeffCfg parseCoeff(Map<?, ?> m) {
        Map<?, ?> c = m.get("coeff") instanceof Map<?, ?> cm ? cm : Map.of();
        return new CoeffCfg(
                ConfigParse.num(c.get("hp"), 1.0).doubleValue(),
                ConfigParse.num(c.get("dmg"), 1.0).doubleValue(),
                ConfigParse.num(c.get("speed"), 1.0).doubleValue(),
                ConfigParse.num(c.get("scale"), 1.0).doubleValue(),
                ConfigParse.num(c.get("follow_range"), 1.0).doubleValue());
    }

    /**
     * 技能 id 列表，fallback：{@code skills} → 旧 {@code affixes} → 旧 {@code infernal.affixes}。
     * <p>迁移（Task 8）前旧 waves.yml 的 affix/infernal 引用仍能加载，id 直传 skill id（mob_skills.yml 含同名）。
     */
    public static List<String> parseSkills(Map<?, ?> m, String context) {
        List<String> out = new ArrayList<>();
        if (m.get("skills") instanceof List<?> skills) {
            for (Object o : skills) out.add(String.valueOf(o));
            return out;
        }
        if (m.get("affixes") instanceof List<?> aff) {
            for (Object o : aff) out.add(String.valueOf(o));
            return out;
        }
        if (m.get("infernal") instanceof Map<?, ?> im && im.get("affixes") instanceof List<?> il) {
            for (Object o : il) out.add(String.valueOf(o));
        }
        return out;
    }

    /** MiniMessage display name; omitted/blank (after trim) → null. Non-string → hard-fail. */
    public static String parseName(Map<?, ?> m, String context) {
        if (!m.containsKey("name") || m.get("name") == null) return null;
        Object raw = m.get("name");
        if (!(raw instanceof String s)) {
            throw new IllegalStateException("name must be string (" + context + ")");
        }
        return ConfigParse.trimToNull(s);
    }

    /** Opt-in Maggoteers boss bar. Omitted → false. Non-boolean (incl. string "true") → hard-fail. */
    public static boolean parseBossBar(Map<?, ?> m, String context) {
        if (!m.containsKey("boss_bar") || m.get("boss_bar") == null) return false;
        Object raw = m.get("boss_bar");
        if (!(raw instanceof Boolean b)) {
            throw new IllegalStateException("boss_bar must be boolean (" + context + ")");
        }
        return b;
    }

    /**
     * D11（2026-10-26）：Boss 血条颜色（{@code bar_color: PURPLE}）/样式（{@code bar_overlay: NOTCHED_10}）。
     * 宽松解析：写错的名字不在加载期崩，由 {@code BossBarStyle.parse} 运行时降级为默认红并保持可玩。
     */
    public static String parseBarColor(Map<?, ?> m, String context) {
        return parseLooseString(m, "bar_color", context);
    }

    /** D11：血条样式（进度条分段）。 */
    public static String parseBarOverlay(Map<?, ?> m, String context) {
        return parseLooseString(m, "bar_overlay", context);
    }

    private static String parseLooseString(Map<?, ?> m, String key, String context) {
        if (!m.containsKey(key) || m.get(key) == null) return null;
        Object raw = m.get(key);
        if (!(raw instanceof String s)) {
            throw new IllegalStateException(key + " must be string (" + context + ")");
        }
        return ConfigParse.trimToNull(s);
    }

    /** Opt-in 骑乘控制器。Omitted → false. Non-boolean → hard-fail. */
    public static boolean parseController(Map<?, ?> m, String context) {
        if (!m.containsKey("controller") || m.get("controller") == null) return false;
        Object raw = m.get("controller");
        if (!(raw instanceof Boolean b)) {
            throw new IllegalStateException("controller must be boolean (" + context + ")");
        }
        return b;
    }

    public static List<MobEquipment> parseEquipment(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) return List.of();
        List<MobEquipment> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> map)) continue;
            String slot = String.valueOf(map.get("slot"));
            String item = map.get("item") != null ? String.valueOf(map.get("item"))
                    : (map.get("item_id") != null ? String.valueOf(map.get("item_id")) : "");
            if (!item.isBlank()) out.add(new MobEquipment(slot, item));
        }
        return out;
    }

    public static List<PassengerCfg> parsePassengers(Object raw) {
        List<PassengerCfg> out = parsePassengersDepth(raw, 1, "step");
        warnOnMultipleControllers(out, "step");
        return out;
    }

    public static List<DeathSpawnCfg> parseOnDeath(Object raw) {
        List<DeathSpawnCfg> out = parseOnDeathDepth(raw, 1, "on_death");
        for (DeathSpawnCfg dc : out) warnOnMultipleControllers(dc.passengers(), "on_death");
        return out;
    }

    private static void warnOnMultipleControllers(List<PassengerCfg> nodes, String context) {
        int n = countControllers(nodes);
        if (n > 1) {
            Logger.getLogger("Maggoteers")
                    .warning(context + " 乘客树声明了 " + n + " 个 controller（首个生效）");
        }
    }

    private static int countControllers(List<PassengerCfg> nodes) {
        int n = 0;
        for (PassengerCfg pc : nodes) {
            if (pc.controller()) n++;
            n += countControllers(pc.passengers());
        }
        return n;
    }

    /** passengers / on_death 两类节点共享的节点级解析结果（字段 + 两层嵌套 + name/bossBar/controller）。 */
    private record MobNode(EntityType type, int count, CoeffCfg coeff, List<String> skills,
                           List<MobEquipment> equipment,
                           List<PassengerCfg> passengers, List<DeathSpawnCfg> onDeath,
                           String name, boolean bossBar, boolean controller) {}

    private static MobNode parseMobNode(Map<?, ?> m, int depth, String context) {
        EntityType type = GameRegistries.entityType(String.valueOf(m.get("type")));
        int count = ConfigParse.num(m.get("count"), 1).intValue();
        CoeffCfg coeff = parseCoeff(m);
        List<String> skills = parseSkills(m, context);
        List<MobEquipment> equipment = parseEquipment(m.get("equipment"));
        List<PassengerCfg> passengers = parsePassengersDepth(m.get("passengers"), depth + 1, type.name());
        List<DeathSpawnCfg> onDeath = parseOnDeathDepth(m.get("on_death"), depth + 1, type.name());
        String name = parseName(m, context + "/" + type.name());
        boolean bossBar = parseBossBar(m, context + "/" + type.name());
        boolean controller = parseController(m, context + "/" + type.name());
        return new MobNode(type, count, coeff, skills, equipment, passengers, onDeath, name, bossBar, controller);
    }

    private static List<PassengerCfg> parsePassengersDepth(Object raw, int depth, String context) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) return List.of();
        if (depth > MAX_NEST_DEPTH) {
            throw new IllegalStateException("passengers nest depth > " + MAX_NEST_DEPTH + " (" + context + ")");
        }
        List<PassengerCfg> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            MobNode n = parseMobNode(m, depth, context);
            out.add(new PassengerCfg(n.type(), n.count(), n.coeff(), n.skills(), n.equipment(),
                    n.onDeath(), n.passengers(), n.name(), n.bossBar(), n.controller()));
        }
        return out;
    }

    private static List<DeathSpawnCfg> parseOnDeathDepth(Object raw, int depth, String context) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) return List.of();
        if (depth > MAX_NEST_DEPTH) {
            throw new IllegalStateException("on_death nest depth > " + MAX_NEST_DEPTH + " (" + context + ")");
        }
        List<DeathSpawnCfg> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            MobNode n = parseMobNode(m, depth, context);
            out.add(new DeathSpawnCfg(n.type(), n.count(), n.coeff(), n.skills(), n.equipment(),
                    n.passengers(), n.onDeath(), n.name(), n.bossBar()));
        }
        return out;
    }
}