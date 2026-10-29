package io.mczju.maggoteers.codex;

import io.mczju.maggoteers.MaggoteersPlugin;
import io.mczju.maggoteers.util.PluginFiles;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 图鉴内容配置（{@code codex.yml}）：页序 / Boss 文案 / 词条白名单。
 *
 * <h2>为什么单独一份文件</h2>
 * 图鉴里的玩法描述与风味描述是**内容**（用户会自己改、以后还会扩），不是 UI 文案；
 * 放进 {@code config.yml messages} 会把它埋进一堆界面文案里。故与
 * {@code rewards.yml} / {@code collectibles.yml} 同级，单独一份 {@code codex.yml}。
 *
 * <p>三块都是"只增不减"的覆盖表：缺条目 = 用默认（页序回落 {@code <act>_<weak|strong|boss>}、
 * Boss 名回落 waves.yml 的「关卡名称」、词条不在白名单里就不显示）。
 */
public final class CodexConfig {
    private static final Logger LOG = Logger.getLogger("Maggoteers");

    /** 武器/藏品图鉴的一页 = 一个 reward pool。 */
    public record Page(String poolId, String title) {}

    /**
     * Boss 图鉴条目（只写**文案**；顺序 / 名字 / 图标 / 血量全部从 waves.yml 推）。
     *
     * <p>铁律②「定义唯一处」：Boss 的血量系数在 `waves.yml` 的 `coeff.hp`、图标是它的刷怪蛋——
     * 这些**机制数值**不在图鉴里再造一份，否则改平衡时会漏改。
     *
     * @param name     显示名；{@code null} = 用 waves.yml 的「关卡名称」
     * @param gameplay 玩法描述（一行）
     * @param flavor   风味描述（一行）
     */
    public record BossText(String name, String gameplay, String flavor) {}

    /** 词条图鉴条目（白名单）。{@code icon} 是材质名，{@code null} = 菜单回落 PAPER。 */
    public record AffixText(String skillId, String name, String icon, String gameplay, String flavor) {}

    private static volatile CodexConfig INSTANCE = new CodexConfig(List.of(), Map.of(), List.of());

    private final List<Page> pages;
    private final Map<String, BossText> bosses;
    private final Map<String, Map<String, BossText>> bossUnits;
    private final List<AffixText> affixes;

    private CodexConfig(List<Page> pages, Map<String, BossText> bosses, List<AffixText> affixes) {
        this(pages, bosses, Map.of(), affixes);
    }

    CodexConfig(List<Page> pages, Map<String, BossText> bosses,
                Map<String, Map<String, BossText>> bossUnits, List<AffixText> affixes) {
        this.pages = List.copyOf(pages);
        this.bosses = Map.copyOf(bosses);
        this.bossUnits = Map.copyOf(bossUnits);
        this.affixes = List.copyOf(affixes);
    }

    public static CodexConfig get() {
        return INSTANCE;
    }

    /** 有序页表（已按 codex.yml 的书写顺序）。 */
    public List<Page> pages() {
        return pages;
    }

    /** Boss 文案；没有配置该 strategy 时返回 {@code null}。 */
    public BossText boss(String strategyId) {
        return strategyId == null ? null : bosses.get(strategyId);
    }

    /**
     * 同一关卡里**某一只** Boss 的专属文案；没配 {@code units:} 时返回 {@code null}（调用方回落到关卡级）。
     *
     * @param unitName 怪物自己的显示名（可带 MiniMessage 标签/引号，这里会归一化）
     */
    public BossText bossUnit(String strategyId, String unitName) {
        if (strategyId == null) return null;
        Map<String, BossText> m = bossUnits.get(strategyId);
        if (m == null || m.isEmpty()) return null;
        return m.get(plainName(unitName));
    }

    /** 配了 {@code units:} 的 strategy → 单位名集合（守卫测试用）。 */
    public Map<String, Map<String, BossText>> bossUnits() {
        return bossUnits;
    }

    /** 词条白名单（有序）。 */
    public List<AffixText> affixes() {
        return affixes;
    }

    public static void load(MaggoteersPlugin plugin) {
        PluginFiles.saveResourceIfMissing(plugin, "codex.yml");
        File f = new File(plugin.getDataFolder(), "codex.yml");
        INSTANCE = parse(YamlConfiguration.loadConfiguration(f), defaultPages(plugin));
        plugin.getLogger().info("CodexConfig 已加载：" + INSTANCE.pages().size() + " 页 / "
                + INSTANCE.bosses.size() + " 条 Boss 文案 / "
                + INSTANCE.affixes().size() + " 条词条白名单。");
        warnUnknownAffixes(plugin, INSTANCE.affixes);
    }

    /**
     * 纯解析（单测直接喂 YamlConfiguration）。
     *
     * @param fallbackPages {@code pages:} 段缺失时用的默认页（插件按 {@code config.yml acts} 推导）
     */
    public static CodexConfig parse(YamlConfiguration yaml, List<Page> fallbackPages) {
        if (yaml == null) return new CodexConfig(fallbackPages, Map.of(), List.of());
        List<Page> pages = parsePages(yaml, fallbackPages);
        Map<String, BossText> bosses = parseBosses(yaml.getConfigurationSection("bosses"));
        Map<String, Map<String, BossText>> units = parseBossUnits(yaml.getConfigurationSection("bosses"));
        List<AffixText> affixes = parseAffixes(yaml.getConfigurationSection("affixes"));
        return new CodexConfig(pages, bosses, units, affixes);
    }

    private static List<Page> parsePages(YamlConfiguration yaml, List<Page> fallbackPages) {
        ConfigurationSection sec = yaml.getConfigurationSection("pages");
        List<Page> out = new ArrayList<>();
        if (sec == null) {
            return fallbackPages == null ? List.of() : List.copyOf(fallbackPages);
        }
        for (String poolId : sec.getKeys(false)) {
            String title = sec.getString(poolId, poolId);
            if (poolId == null || poolId.isBlank()) continue;
            out.add(new Page(poolId.trim(), title == null || title.isBlank() ? poolId : title));
        }
        return out.isEmpty() && fallbackPages != null ? List.copyOf(fallbackPages) : out;
    }

    private static Map<String, BossText> parseBosses(ConfigurationSection sec) {
        Map<String, BossText> out = new LinkedHashMap<>();
        if (sec == null) return out;
        for (String id : sec.getKeys(false)) {
            ConfigurationSection s = sec.getConfigurationSection(id);
            if (s == null) continue;
            out.put(id, new BossText(
                    blankToNull(s.getString("name")),
                    s.getString("gameplay", ""),
                    s.getString("flavor", "")));
        }
        return out;
    }

    /**
     * `units:` —— 同一关卡里**每只 Boss 各一段文案**（2026-10-30 用户口径：
     * 寒渊惜别 / 仁·义·武 / 迈入永恒 这种"一关多 Boss"的关，两个蛋不能共用一段话）。
     *
     * <pre>
     * bosses:
     *   b2_frost_farewell:
     *     gameplay: ...      # 关卡级兜底
     *     units:
     *       雪牝: { gameplay: ..., flavor: ... }
     *       霜牡: { gameplay: ..., flavor: ... }
     * </pre>
     *
     * <p>键是**怪物自己的显示名**（`waves.yml` 的 `name:`，比较时会去掉 MiniMessage 标签与引号）。
     */
    private static Map<String, Map<String, BossText>> parseBossUnits(ConfigurationSection sec) {
        Map<String, Map<String, BossText>> out = new LinkedHashMap<>();
        if (sec == null) return out;
        for (String strategyId : sec.getKeys(false)) {
            ConfigurationSection s = sec.getConfigurationSection(strategyId);
            if (s == null) continue;
            ConfigurationSection units = s.getConfigurationSection("units");
            if (units == null) continue;
            Map<String, BossText> m = new LinkedHashMap<>();
            for (String unitName : units.getKeys(false)) {
                ConfigurationSection u = units.getConfigurationSection(unitName);
                String key = plainName(unitName);
                if (key.isEmpty()) continue;
                if (u == null) continue;
                m.put(key, new BossText(null, u.getString("gameplay", ""), u.getString("flavor", "")));
            }
            if (!m.isEmpty()) out.put(strategyId, Map.copyOf(m));
        }
        return out;
    }

    /** 去掉 MiniMessage 标签与中文引号，用于"配置键 ↔ waves.yml 显示名"的比对。 */
    static String plainName(String raw) {
        if (raw == null) return "";
        String s = raw.replaceAll("<[^>]*>", "");
        s = s.replace("“", "").replace("”", "").replace("「", "").replace("」", "");
        s = s.replace("\"", "").replace("'", "");
        return s.trim();
    }

    private static List<AffixText> parseAffixes(ConfigurationSection sec) {
        List<AffixText> out = new ArrayList<>();
        if (sec == null) return out;
        for (String skillId : sec.getKeys(false)) {
            ConfigurationSection s = sec.getConfigurationSection(skillId);
            if (s == null) continue;
            String name = s.getString("name", skillId);
            out.add(new AffixText(skillId.trim(),
                    name == null || name.isBlank() ? skillId : name,
                    blankToNull(s.getString("icon")),
                    s.getString("gameplay", ""),
                    s.getString("flavor", "")));
        }
        return out;
    }

    /** 默认页序：{@code codex.yml} 缺 {@code pages:} 时按层列表推（三档；空池后面会被自动跳过）。 */
    public static List<Page> defaultPages(MaggoteersPlugin plugin) {
        List<Page> out = new ArrayList<>();
        if (plugin == null) return out;
        for (String act : plugin.actIds()) {
            for (String tier : List.of("weak", "strong", "boss")) {
                String poolId = act + "_" + tier;
                out.add(new Page(poolId, poolId));
            }
        }
        return out;
    }

    /** 白名单里写了 mob_skills.yml 不存在的技能 id → 告警（不静默）。 */
    private static void warnUnknownAffixes(MaggoteersPlugin plugin, List<AffixText> affixes) {
        if (plugin == null) return;
        for (AffixText a : affixes) {
            if (io.mczju.maggoteers.mob.MobSkillRegistry.get(a.skillId()).isEmpty()) {
                plugin.getLogger().warning("codex.yml: affixes." + a.skillId()
                        + " 不是 mob_skills.yml 里的技能 id（图鉴里仍会显示，但该词条不会挂在任何怪身上）");
            }
        }
    }

    /**
     * {@code icon:} → Material；非法/空返回 {@code null}（调用方回落默认图标，并告警一次）。
     *
     * <p>先走 {@link Material#valueOf}（纯枚举查询，无服务端依赖，单测可用），
     * 再回落 {@link Material#matchMaterial}（能吃旧版别名，但需要服务端注册表）。
     */
    public static Material materialOrNull(String raw) {
        Material m = materialQuiet(raw);
        if (m == null && raw != null && !raw.isBlank()) {
            LOG.warning("codex.yml: 未知图标材质 " + raw);
        }
        return m;
    }

    /** 同上但不告警（"这个实体本来就没有刷怪蛋"之类的推导用）。 */
    public static Material materialQuiet(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String name = raw.trim().toUpperCase(Locale.ROOT);
        try {
            Material m = Material.valueOf(name);
            if (m.isItem()) return m;
        } catch (IllegalArgumentException ignored) {
            // 不是枚举名 → 试 matchMaterial（旧版别名）
        }
        try {
            Material m = Material.matchMaterial(name);
            if (m != null && m.isItem()) return m;
        } catch (RuntimeException | LinkageError ignored) {
            // 无服务端注册表 / 未知名字
        }
        return null;
    }

    private static String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
