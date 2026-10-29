package io.mczju.maggoteers.mob;

/**
 * 怪物被动减伤（2026-10-26）：{@code mob_skills.yml} 的技能可写
 * <pre>
 * damage_reduction: { type: magic|physical, percent: 0.5 }
 * </pre>
 * 由 {@code MobDamageReductionListener} 在 {@code EntityDamageEvent}（HIGH）里应用。
 * 「法术伤害」= 经 {@code MagicDamage.applyIndependent} 产生的伤害（标记 metadata）；
 * 其余（近战/弓箭/爆炸）一律视为物理。
 */
public record DamageReduction(String type, double percent) {

    public static final String MAGIC = "magic";
    public static final String PHYSICAL = "physical";

    public DamageReduction {
        String norm = type == null ? "" : type.trim().toLowerCase(java.util.Locale.ROOT);
        type = MAGIC.equals(norm) ? MAGIC : PHYSICAL;   // 未知类型按物理处理（fail-safe）
        percent = Math.max(0.0, Math.min(1.0, percent));
    }

    public boolean magic() {
        return MAGIC.equals(type);
    }

    /** 与本次伤害类型是否匹配（type 非法时按物理处理）。 */
    public boolean matches(boolean magicDamage) {
        return magic() == magicDamage;
    }
}
