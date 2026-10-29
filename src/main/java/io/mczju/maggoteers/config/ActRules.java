package io.mczju.maggoteers.config;

import io.mczju.maggoteers.util.GameDifficulty;
import org.bukkit.configuration.ConfigurationSection;

import java.util.List;

/**
 * 层相关的"特例规则"单一真相源（{@code config.yml act_rules.*}）。
 *
 * <p>这些规则原先写死在 Java 里，全部以 {@code actIndex == 2} 的形式绑定第 3 层：
 * <ul>
 *   <li>{@code RewardService.normalShopTier}：第 3 层普通商店恒 {@code strong} 池；</li>
 *   <li>{@code WaveScheduler}：第 3 层清掉 weak（奖励关）即把 Boss 池粘滞切到本层；</li>
 *   <li>{@code CurseService}：困难仅进 Act2 发 {@code curse_hard}；专家每层发 {@code curse_expert_<act>}。</li>
 * </ul>
 * 改为显式 act id 列表后，加第 4 层（含隐藏结局层）只改配置，不再需要改 Java 常量。
 *
 * <p>缺键回落 {@link #defaults()}，与历史行为逐条等价（老服务器配置无需升级）。
 */
public record ActRules(
        List<String> shopAlwaysStrong,
        List<String> bossPoolEarly,
        List<String> curseHard,
        List<String> curseExpert
) {

    public static final String HARD_CURSE_POOL = "curse_hard";
    public static final String EXPERT_CURSE_PREFIX = "curse_expert_";

    public ActRules {
        shopAlwaysStrong = copy(shopAlwaysStrong);
        bossPoolEarly = copy(bossPoolEarly);
        curseHard = copy(curseHard);
        curseExpert = copy(curseExpert);
    }

    private static List<String> copy(List<String> in) {
        return in == null ? List.of() : List.copyOf(in);
    }

    /** 与 1.3.x 一致的历史行为：Act3 恒 strong/提前切 Boss 池；困难在 Act2、专家每层发诅咒。 */
    public static ActRules defaults() {
        return new ActRules(
                List.of("act3"),
                List.of("act3"),
                List.of("act2"),
                List.of("act1", "act2", "act3"));
    }

    /** 读 {@code act_rules}；缺键逐项回落默认值（方便只覆盖一部分）。 */
    public static ActRules fromConfig(ConfigurationSection cfg) {
        ActRules def = defaults();
        if (cfg == null || !cfg.isConfigurationSection("act_rules")) return def;
        ConfigurationSection s = cfg.getConfigurationSection("act_rules");
        if (s == null) return def;
        return new ActRules(
                listOr(s, "shop_always_strong", def.shopAlwaysStrong),
                listOr(s, "boss_pool_early", def.bossPoolEarly),
                listOr(s, "curse_hard", def.curseHard),
                listOr(s, "curse_expert", def.curseExpert));
    }

    private static List<String> listOr(ConfigurationSection s, String key, List<String> fallback) {
        if (!s.contains(key)) return fallback;   // 缺键 → 默认；写了 [] → 显式"无"（返回空列表）
        return s.getStringList(key);
    }

    /** 该层普通商店是否恒用 strong 池。 */
    public boolean alwaysStrongShop(String actId) {
        return actId != null && shopAlwaysStrong.contains(actId);
    }

    /** 该层清掉 weak（奖励关）后是否提前把 Boss 池粘滞切到本层。 */
    public boolean bossPoolEarly(String actId) {
        return actId != null && bossPoolEarly.contains(actId);
    }

    /** 纯函数：难度 × 层 id → 负面（诅咒）池 id；该难度该层不发诅咒返回 null。 */
    public String cursePool(GameDifficulty difficulty, String actId) {
        if (difficulty == null || actId == null) return null;
        return switch (difficulty) {
            case NORMAL -> null;
            case HARD -> curseHard.contains(actId) ? HARD_CURSE_POOL : null;
            case EXPERT -> curseExpert.contains(actId) ? EXPERT_CURSE_PREFIX + actId : null;
        };
    }
}
