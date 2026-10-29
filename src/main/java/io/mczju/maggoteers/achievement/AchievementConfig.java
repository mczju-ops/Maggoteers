package io.mczju.maggoteers.achievement;

import io.mczju.maggoteers.MaggoteersPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * `achievements.yml` 的加载器。
 *
 * <p>成就的**元数据**（名字/描述/图标/阈值）全在配置里；判定逻辑在 {@link AchievementRules}。
 * 规则 id 写错时**告警并跳过那一条**（不让一个错字把整张表拖垮）。
 */
public final class AchievementConfig {

    private static final Logger LOG = Logger.getLogger("Maggoteers");

    private final Map<String, AchievementDef> defs;

    private AchievementConfig(Map<String, AchievementDef> defs) {
        this.defs = Map.copyOf(defs);
    }

    public Map<String, AchievementDef> defs() {
        return defs;
    }

    public AchievementDef get(String id) {
        return defs.get(id);
    }

    /** 从插件数据目录读；文件不存在则先从 jar 里释放默认文件。 */
    public static AchievementConfig load(MaggoteersPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "achievements.yml");
        if (!file.isFile()) {
            plugin.saveResource("achievements.yml", false);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        // 兜底：用户把文件写坏/删空时，用 jar 内的默认值补（不覆盖用户的文件）
        if (yaml.getConfigurationSection("achievements") == null) {
            try (InputStream in = plugin.getResource("achievements.yml")) {
                if (in != null) {
                    yaml = YamlConfiguration.loadConfiguration(
                            new InputStreamReader(in, StandardCharsets.UTF_8));
                    LOG.warning("achievements.yml 里没有 achievements 段，已临时改用内置默认值"
                            + "（你的文件没有被改动）");
                }
            } catch (Exception ex) {
                LOG.warning("读取内置 achievements.yml 失败：" + ex.getMessage());
            }
        }
        return parse(yaml);
    }

    /** 纯解析（单测直接用 YamlConfiguration 调这个）。 */
    public static AchievementConfig parse(ConfigurationSection root) {
        Map<String, AchievementDef> out = new LinkedHashMap<>();
        ConfigurationSection sec = root == null ? null : root.getConfigurationSection("achievements");
        if (sec == null) return new AchievementConfig(out);
        for (String id : sec.getKeys(false)) {
            ConfigurationSection s = sec.getConfigurationSection(id);
            if (s == null) {
                LOG.warning("成就 " + id + " 不是一段配置，已跳过");
                continue;
            }
            String rule = s.getString("rule", "");
            if (!AchievementRules.HANDLED_RULES.contains(rule)) {
                LOG.warning("成就 " + id + " 的 rule=" + rule + " 没有实现，已跳过（可用："
                        + AchievementRules.HANDLED_RULES + "）");
                continue;
            }
            String name = s.getString("name", id);
            String desc = s.getString("description", "");
            String icon = s.getString("icon", "");
            if (icon.isBlank()) {
                LOG.warning("成就 " + id + " 没有 icon，已跳过");
                continue;
            }
            List<String> required = new ArrayList<>(s.getStringList("required"));
            out.put(id, new AchievementDef(id, name, desc, icon.trim(), rule,
                    s.getDouble("value", 0.0), s.getString("stat", ""), required,
                    s.getString("rarity", "common")));
        }
        return new AchievementConfig(out);
    }
}
