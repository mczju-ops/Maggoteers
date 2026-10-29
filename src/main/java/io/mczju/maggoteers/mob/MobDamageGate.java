package io.mczju.maggoteers.mob;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 伤害门（D2 / 2026-10-26；门扉缔造者的阶段 1/2/3 全靠它）。
 *
 * <p>五种"硬防护"，可同时声明：
 * <ul>
 *   <li>{@code damage_cap: 40} —— **单次**伤害绝对值上限；</li>
 *   <li>{@code damage_cap_percent: 0.6667} —— **单次**上限 = 目标最大生命 × 百分比
 *       （阶段 3：3000 血 × 0.667% = 20/次；4 人 ×5.0 血量时自动变 100/次）；</li>
 *   <li>{@code immune_damage: [projectile, magic]} —— 按类别免疫（{@code all}/{@code any} = 全免疫）；</li>
 *   <li>{@code immune_all: true} —— 全免疫（阶段 1 的 60 秒无敌）；</li>
 *   <li>{@code immune_beyond_radius: 6} —— **攻击者**距目标超过该半径则免疫（阶段 2「勿听」）。</li>
 * </ul>
 *
 * <p>与 {@code damage_reduction}（乘系数）的顺序：先减伤，再门（免疫归零 / 封顶）。
 * {@link #apply(double, DamageCategory, double)} 是纯函数，单测覆盖全部分支。
 */
public record MobDamageGate(double cap, Set<DamageCategory> immune, boolean immuneAll,
                            double capPercent, double immuneBeyondRadius) {

    public static final MobDamageGate NONE = new MobDamageGate(0.0, Set.of(), false, 0.0, 0.0);

    public MobDamageGate {
        cap = Math.max(0.0, cap);
        immune = immune == null ? Set.of() : Set.copyOf(immune);
        capPercent = Math.max(0.0, capPercent);
        immuneBeyondRadius = Math.max(0.0, immuneBeyondRadius);
    }

    /** 兼容旧构造：只有绝对值 cap + 免疫类别。 */
    public MobDamageGate(double cap, Set<DamageCategory> immune) {
        this(cap, immune, false, 0.0, 0.0);
    }

    public static MobDamageGate of(double cap, List<String> immuneNames) {
        return new MobDamageGate(cap, DamageCategory.parseSet(immuneNames), false, 0.0, 0.0);
    }

    /** 从技能 YAML 顶层键解析；一个门都没有时返回 null。 */
    public static MobDamageGate parse(Map<?, ?> m) {
        if (m == null) return null;
        double cap = num(m.get("damage_cap"));
        double capPercent = num(m.get("damage_cap_percent"));
        double beyond = num(m.get("immune_beyond_radius"));
        boolean immuneAll = Boolean.parseBoolean(String.valueOf(m.get("immune_all")));
        List<String> immune = new ArrayList<>();
        if (m.get("immune_damage") instanceof List<?> list) {
            for (Object o : list) if (o != null) immune.add(String.valueOf(o));
        } else if (m.get("immune_damage") != null) {
            immune.add(String.valueOf(m.get("immune_damage")));
        }
        List<String> cats = new ArrayList<>();
        for (String s : immune) {
            String t = s == null ? "" : s.trim().toLowerCase(java.util.Locale.ROOT);
            if ("all".equals(t) || "any".equals(t)) immuneAll = true;
            else cats.add(s);
        }
        Set<DamageCategory> parsed = DamageCategory.parseSet(cats);
        if (cap <= 0 && capPercent <= 0 && beyond <= 0 && !immuneAll && parsed.isEmpty()) return null;
        return new MobDamageGate(cap, parsed, immuneAll, capPercent, beyond);
    }

    private static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0.0;
    }

    public boolean active() {
        return cap > 0.0 || capPercent > 0.0 || immuneAll || immuneBeyondRadius > 0.0 || !immune.isEmpty();
    }

    public boolean immuneTo(DamageCategory category) {
        return immuneAll || (category != null && immune.contains(category));
    }

    /**
     * 施加伤害门。
     *
     * @param damage      已过减伤的伤害
     * @param category    伤害分类（null 视为 OTHER）
     * @param targetMaxHp 目标最大生命（{@code capPercent} 用；≤0 时忽略该条）
     * @return 最终伤害（免疫 = 0）
     */
    public double apply(double damage, DamageCategory category, double targetMaxHp) {
        if (damage <= 0) return damage;
        if (immuneTo(category)) return 0.0;
        if (capPercent > 0 && targetMaxHp > 0) {
            damage = Math.min(damage, targetMaxHp * capPercent / 100.0);
        }
        if (cap > 0) damage = Math.min(damage, cap);
        return damage;
    }

    /** 旧签名（无血量上下文）：{@code capPercent} 不生效。 */
    public double apply(double damage, DamageCategory category) {
        return apply(damage, category, 0);
    }

    /** 合并两条门：cap/percent/radius 各取"更小非零值"，免疫并集，全免疫取或。 */
    public static MobDamageGate merge(MobDamageGate a, MobDamageGate b) {
        if (a == null) return b;
        if (b == null) return a;
        Set<DamageCategory> immune = EnumSet.noneOf(DamageCategory.class);
        immune.addAll(a.immune());
        immune.addAll(b.immune());
        return new MobDamageGate(
                minNonZero(a.cap(), b.cap()),
                immune,
                a.immuneAll() || b.immuneAll(),
                minNonZero(a.capPercent(), b.capPercent()),
                minNonZero(a.immuneBeyondRadius(), b.immuneBeyondRadius()));
    }

    private static double minNonZero(double x, double y) {
        if (x <= 0) return y;
        if (y <= 0) return x;
        return Math.min(x, y);
    }

    public String describe() {
        return "gate{cap=" + cap + ",cap%=" + capPercent + ",all=" + immuneAll
                + ",immune=" + immune + ",beyond=" + immuneBeyondRadius + "}";
    }
}
