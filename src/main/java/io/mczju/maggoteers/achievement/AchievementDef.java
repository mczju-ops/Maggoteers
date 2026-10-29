package io.mczju.maggoteers.achievement;

import java.util.List;

/**
 * 一条成就的**配置侧定义**（`achievements.yml` 的一段）。
 *
 * @param id          成就 id（`maggoteers:<id>` 的 <id>）
 * @param name        成就名（纯文本）
 * @param description 描述（纯文本；隐藏成就靠描述模糊，不靠隐藏字段）
 * @param iconKey     图标材质名（运行时解析成 Material）
 * @param rule        判定规则 id（见 {@link AchievementRules}；必须是已实现的规则）
 * @param value       阈值（按规则解释）
 * @param stat        仅 `rule: statistic` 用：MGC 统计项名（WINS / KILLS / …）
 * @param required    部分规则需要的 id 列表（如"所有国王藏品"）
 * @param rarity      `common` / `legendary`：传说成就解锁时放音乐 + 全队广播
 */
public record AchievementDef(
        String id,
        String name,
        String description,
        String iconKey,
        String rule,
        double value,
        String stat,
        List<String> required,
        String rarity) {

    public AchievementDef {
        required = required == null ? List.of() : List.copyOf(required);
        rarity = rarity == null || rarity.isBlank() ? "common" : rarity.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public long longTarget() {
        return value <= 0 ? 1L : Math.max(1L, Math.round(value));
    }

    /** 传说成就（解锁时放音乐 + 全队广播）。 */
    public boolean legendary() {
        return "legendary".equals(rarity);
    }
}
