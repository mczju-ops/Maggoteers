package io.mczju.maggoteers.config;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 层（Act）列表的单一真相源：{@code config.yml acts: [act1, act2, act3]}。
 *
 * <p>历史问题：层列表曾是 {@code RunPlanner} 里的硬编码数组 {@code {"act1","act2","act3"}}，
 * 且 {@code act_origins} / {@code waves_per_act} 也只按这个数组读取 —— 加第 4 层只改配置**无效**。
 * 现在顺序即推进顺序，读写两侧都只看这里。
 *
 * <p>校验失败一律抛 {@link IllegalStateException}（宁可开局报错，也不静默少一层/原点重叠）。
 */
public final class ActsConfig {

    /** 缺省层列表（老配置没有 acts: 键时的回落，保持历史行为）。 */
    public static final List<String> DEFAULT_IDS = List.of("act1", "act2", "act3");

    private static final Pattern ID_PATTERN = Pattern.compile("act[1-9][0-9]*");

    /** 读配置：缺 {@code acts} 键 → {@link #DEFAULT_IDS}；存在但非法 → 抛错。 */
    public static List<String> fromConfig(ConfigurationSection cfg) {
        if (cfg == null || !cfg.contains("acts")) return DEFAULT_IDS;
        return parse(cfg.getStringList("acts"));
    }

    /**
     * 纯函数：校验并冻结层 id 列表（顺序即推进顺序）。
     * 空列表 / 非法 id / 重复 id → 抛错，绝不静默回落。
     */
    public static List<String> parse(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            throw new IllegalStateException("config.yml acts 为空：至少要有一层，如 acts: [act1, act2, act3]");
        }
        List<String> out = new ArrayList<>(raw.size());
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < raw.size(); i++) {
            String id = raw.get(i) == null ? "" : raw.get(i).trim();
            if (!ID_PATTERN.matcher(id).matches()) {
                throw new IllegalStateException("config.yml acts[" + i + "] 非法层 id：'" + raw.get(i)
                        + "'（应形如 act1 / act2 / act3）");
            }
            if (!seen.add(id)) {
                throw new IllegalStateException("config.yml acts 重复层 id：" + id);
            }
            out.add(id);
        }
        return List.copyOf(out);
    }

    /** 按层序号取 id；越界抛错（避免历史 {@code "act" + (i+1)} 式的静默错层）。 */
    public static String idAt(List<String> ids, int index) {
        if (ids == null || index < 0 || index >= ids.size()) {
            throw new IllegalStateException("层序号越界：index=" + index + ", acts=" + ids);
        }
        return ids.get(index);
    }

    private ActsConfig() {}
}
