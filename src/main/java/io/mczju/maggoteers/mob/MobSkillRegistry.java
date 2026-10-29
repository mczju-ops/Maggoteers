package io.mczju.maggoteers.mob;

import io.mczju.maggoteers.MaggoteersPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/** 加载 mob_skills.yml（技能 id → MobSkillSpec）。定义唯一处：怪物技能只在 mob_skills.yml。 */
public final class MobSkillRegistry {

    private static final Logger LOG = Logger.getLogger("Maggoteers");
    private static final Map<String, MobSkillSpec> BY_ID = new HashMap<>();

    private MobSkillRegistry() {}

    public static void load(MaggoteersPlugin plugin) {
        BY_ID.clear();
        File file = new File(plugin.getDataFolder(), "mob_skills.yml");
        if (!file.isFile()) {
            plugin.saveResource("mob_skills.yml", false);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection skills = yaml.getConfigurationSection("skills");
        if (skills == null) {
            LOG.warning("mob_skills.yml 缺 skills 段");
            return;
        }
        AtomicInteger loaded = new AtomicInteger();
        for (String key : skills.getKeys(false)) {
            Object raw = skills.get(key);
            if (raw instanceof Map<?, ?> m) {
                java.util.Map<Object, Object> copy = new java.util.HashMap<>(m);
                if (!copy.containsKey("id")) {
                    copy.put("id", key);
                }
                MobSkillSpecParser.parse(copy).ifPresentOrElse(
                        spec -> { BY_ID.put(key, spec); loaded.incrementAndGet(); },
                        () -> LOG.warning("mob_skills.yml 技能 " + key + " 解析失败，跳过"));
            } else if (raw instanceof ConfigurationSection sec) {
                // ConfigurationSection → Map（递归展平嵌套段/列表，保持 parser 纯 Map 输入；
                // 否则 condition 等嵌套 Map 会以 MemorySection 形态进入 parser 而被丢弃）
                Map<String, Object> flat = new HashMap<>();
                for (String k : sec.getKeys(false)) {
                    flat.put(k, toPlain(sec.get(k)));
                }
                if (!flat.containsKey("id")) {
                    flat.put("id", key);
                }
                MobSkillSpecParser.parse(flat).ifPresentOrElse(
                        spec -> { BY_ID.put(key, spec); loaded.incrementAndGet(); },
                        () -> LOG.warning("mob_skills.yml 技能 " + key + " 解析失败，跳过"));
            }
        }
        LOG.info("MobSkillRegistry: loaded " + loaded.get() + " skills");
        warnMobUnsupported();
    }

    /**
     * 加载期告警：mob_skills.yml 里写了"怪物侧空实现"的 effect（{@link MobEffectSupport}）
     * 或无法解析的 targets（如 {@code allies}）。这类配置过去是**静默无效果**，最难排查。
     */
    private static void warnMobUnsupported() {
        for (var e : BY_ID.entrySet()) {
            String id = e.getKey();
            MobSkillSpec spec = e.getValue();
            List<String> bad = MobEffectSupport.unsupportedIn(spec);
            if (!bad.isEmpty()) {
                LOG.warning("mob_skills.yml 技能 " + id + " 的 effect 在怪物侧为空实现，实际无效果：" + bad
                        + "（玩家专属或不支持；需要怪物侧生效请改为 HEAL/DAMAGE_AREA/ADD_POTION targets:self 等）");
            }
            for (MobEffectSpec es : spec.effects()) {
                String targets = es.params().get(io.mczju.maggoteers.effect.EffectKeys.TARGETS);
                if (!MobEffectSupport.isSupportedTargets(targets)) {
                    LOG.warning("mob_skills.yml 技能 " + id + " 的 targets=" + targets
                            + " 在怪物侧不受支持（只支持 self/target/hit_target/attacker/area/players），会被当作 self");
                }
            }
        }
    }

    /** 把 Bukkit {@link ConfigurationSection} 递归展平成纯 Map/List/标量，供纯逻辑 parser 消费。 */
    private static Object toPlain(Object v) {
        if (v instanceof ConfigurationSection cs) {
            Map<String, Object> m = new HashMap<>();
            for (String k : cs.getKeys(false)) {
                m.put(k, toPlain(cs.get(k)));
            }
            return m;
        }
        if (v instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object o : list) {
                out.add(toPlain(o));
            }
            return out;
        }
        if (v instanceof Map<?, ?> m) {
            Map<Object, Object> out = new HashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(e.getKey(), toPlain(e.getValue()));
            }
            return out;
        }
        return v;
    }

    public static Optional<MobSkillSpec> get(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(BY_ID.get(id));
    }

    public static Map<String, MobSkillSpec> all() {
        return Map.copyOf(BY_ID);
    }
}
