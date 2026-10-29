package io.mczju.maggoteers.effect;

import org.bukkit.attribute.Attribute;
import org.bukkit.potion.PotionEffectType;

import java.util.List;

/** Effect 参数的标准键（§10.2 各 Effect 的 params 契约）。 */
public final class EffectKeys {
    public static final EffectKey<Attribute> ATTR = new EffectKey<>("attr");
    /** YAML 原始属性名（onEnable 解析失败时 refresh 再解析）。 */
    public static final EffectKey<String> ATTR_NAME = new EffectKey<>("attr_name");
    public static final EffectKey<String> POTION_NAME = new EffectKey<>("potion_name");
    public static final EffectKey<String> OP = new EffectKey<>("op");           // "PERCENT" | "FLAT" | "MULTIPLY" | "REVOKE_GRANTS"
    /** REVOKE_GRANTS: template id whose {@code :grant} layers to remove. */
    public static final EffectKey<String> SOURCE_ID = new EffectKey<>("source_id");
    public static final EffectKey<Double> VALUE = new EffectKey<>("value");
    /** DYNAMIC_ATTRIBUTE: 动态来源（coin_count | revive_zero | kills | hits_taken | deaths | waves_cleared | acts_cleared）。 */
    public static final EffectKey<String> SOURCE = new EffectKey<>("source");
    /** DYNAMIC_ATTRIBUTE: 每单位（每枚金币 / 每达标一份计数）的加成值。 */
    public static final EffectKey<Double> PER_UNIT = new EffectKey<>("per_unit");
    /** DYNAMIC_ATTRIBUTE: 累计类来源的折叠阈值（每累计 N 次算一份，默认 10）。 */
    public static final EffectKey<Integer> THRESHOLD = new EffectKey<>("threshold");
    public static final EffectKey<PotionEffectType> POTION = new EffectKey<>("potion");
    public static final EffectKey<Integer> AMP = new EffectKey<>("amp");
    public static final EffectKey<Integer> DURATION_TICKS = new EffectKey<>("duration_ticks"); // 0=常驻
    public static final EffectKey<Double> AMOUNT = new EffectKey<>("amount");   // HEAL
    /** 2026-10-29（CONE）：扇形张角（度，整角；半角 = angle/2）。**不吃 RANGE**。 */
    public static final EffectKey<Double> ANGLE = new EffectKey<>("angle");
    /** 2026-10-29（CONE）：顺带施加的冻结 tick（0/缺省 = 不冻结）。 */
    public static final EffectKey<Integer> FREEZE_TICKS = new EffectKey<>("freeze_ticks");
    /** 2026-10-29（CONE）：顺带点燃的 tick（0/缺省 = 不点燃；20 tick = 1 秒）。 */
    public static final EffectKey<Integer> IGNITE_TICKS = new EffectKey<>("ignite_ticks");
    public static final EffectKey<Double> RADIUS = new EffectKey<>("radius");   // DAMAGE_AREA
    public static final EffectKey<Double> DAMAGE = new EffectKey<>("damage");   // DAMAGE_AREA / BEAM
    public static final EffectKey<Double> RAY_LENGTH = new EffectKey<>("ray_length");
    public static final EffectKey<Double> BEAM_RADIUS = new EffectKey<>("beam_radius");
    /** DAMAGE_BEAM: heal allies sampled along the ray (same path as damage). */
    public static final EffectKey<Double> HEAL_AMOUNT = new EffectKey<>("heal_amount");
    public static final EffectKey<Integer> COUNT = new EffectKey<>("count");    // GRANT_REVIVE / SUMMON
    /** 2026-10-28（魔王的旗帜）：GRANT_REVIVE 的前置门槛——剩余复活次数 ≥ 该值才发放（缺省 0 = 无门槛）。 */
    public static final EffectKey<Integer> MIN_REVIVES = new EffectKey<>("min_revives");
    public static final EffectKey<Double> OFFSET_Y = new EffectKey<>("offset_y");
    public static final EffectKey<Double> PROJECTILE_SPEED = new EffectKey<>("projectile_speed");
    public static final EffectKey<String> TARGETS = new EffectKey<>("targets");           // AURA
    public static final EffectKey<String> ENEMY_SCOPE = new EffectKey<>("enemy_scope");     // AURA
    public static final EffectKey<Boolean> INCLUDE_SELF = new EffectKey<>("include_self");  // AURA
    public static final EffectKey<Effect> GRANT_EFFECT = new EffectKey<>("grant_effect");   // AURA
    public static final EffectKey<EffectContext> GRANT_PARAMS = new EffectKey<>("grant_params");
    public static final EffectKey<Integer> GRANT_PULSE_SEC = new EffectKey<>("grant_pulse_sec");
    /** 2026-10-27：DAMAGE_BEAM 贯穿开关（缺省 false = 只伤害最近的一个目标；治疗不受影响）。 */
    public static final EffectKey<Boolean> PIERCE = new EffectKey<>("pierce");
    /**
     * 2026-10-28：只在「本次命中已有蓄力 grant」时才生效（乌萨斯切肉刀：
     * 普攻不带范围，只有蓄力那一下才是范围强力击）。
     */
    public static final EffectKey<Boolean> ONLY_WITH_GRANT = new EffectKey<>("only_with_grant");
    /**
     * 2026-10-28：蓄力 grant 附带的"那一刀的范围伤害"参数（乌萨斯切肉刀）。
     * 只在票券被消耗的那一次近战里执行，从而得到 A A Q 的循环。
     */
    public static final EffectKey<EffectContext> BONUS_AREA = new EffectKey<>("bonus_area");
    public static final EffectKey<Boolean> MARK_HEAD = new EffectKey<>("mark_head");
    /** 携带者脚下每秒播放的粒子（preset/particle/radius/density 等）。 */
    public static final EffectKey<EffectContext> CARRIER_FX = new EffectKey<>("carrier_fx");
    public static final EffectKey<EffectContext> MARK_FX = new EffectKey<>("mark_fx");
    public static final EffectKey<EffectContext> FX = new EffectKey<>("fx");
    public static final EffectKey<String> MARK_ON = new EffectKey<>("mark_on");
    public static final EffectKey<java.util.List<BuffPotionSpec>> POTIONS = new EffectKey<>("potions");
    /** One-shot strip list for DAMAGE_AREA / BUFF_AREA ({@code clear_potions}). */
    public static final EffectKey<java.util.List<PotionEffectType>> CLEAR_POTIONS =
            new EffectKey<>("clear_potions");
    /** Run-long potion immunity for ADD_POTION ({@code immunity: true}). */
    public static final EffectKey<Boolean> IMMUNITY = new EffectKey<>("immunity");
    /** GRANT_ITEM: ItemCreator item id (YAML key {@code item}). */
    public static final EffectKey<String> ITEM_ID = new EffectKey<>("item_id");
    public static final EffectKey<String> ENTITY = new EffectKey<>("entity");
    /** SUMMON 弹道 homing 目标预设：nearest_enemy | nearest_player | attack_target | damage_source | killer | none/缺省=不追踪。 */
    public static final EffectKey<String> HOMING_TARGET = new EffectKey<>("homing_target");
    public static final EffectKey<String> ANCHOR = new EffectKey<>("anchor");
    public static final EffectKey<String> CLEANUP = new EffectKey<>("cleanup");
    public static final EffectKey<Integer> DURATION_SEC = new EffectKey<>("duration_sec");
    public static final EffectKey<java.util.Map<String, Double>> ATTRIBUTES = new EffectKey<>("attributes");
    public static final EffectKey<Boolean> TAMED = new EffectKey<>("tamed");
    public static final EffectKey<Boolean> FRIENDLY_FIRE = new EffectKey<>("friendly_fire");
    public static final EffectKey<EffectContext> PROJECTILE = new EffectKey<>("projectile");
    public static final EffectKey<String> SLOT = new EffectKey<>("slot");
    public static final EffectKey<java.util.Map<Integer, String>> ITEMS_BY_LEVEL =
            new EffectKey<>("items_by_level");
    /** 计数器 id（ON_COUNTER 信号的来源；绑定被动的 params.counter.id 与之匹配）。 */
    public static final EffectKey<String> COUNTER_ID = new EffectKey<>("counter_id");
    /** DISPLACE（怪拽玩家，IM RangeTosserSkill）：水平拉扯力度 / 上抛幅度。 */
    public static final EffectKey<Double> FORCE = new EffectKey<>("force");
    public static final EffectKey<Double> UPWARD = new EffectKey<>("upward");
    /** 怪物技能：粒子名（如 end_rod）、音效名、扫射延时/间隔。 */
    public static final EffectKey<String> PARTICLE_NAME = new EffectKey<>("particle_name");
    public static final EffectKey<String> SOUND_NAME = new EffectKey<>("sound_name");
    public static final EffectKey<Float> SOUND_VOLUME = new EffectKey<>("sound_volume");
    public static final EffectKey<Float> SOUND_PITCH = new EffectKey<>("sound_pitch");
    /** 2026-10-26：法阵预警提前量（tick）。 */
    public static final EffectKey<Integer> WARN_TICKS = new EffectKey<>("warn_ticks");
    /** 2026-10-26：SUMMON 生成物的技能/体型/血量倍率（恼鬼 2.0 尺寸 + thief）。 */
    public static final EffectKey<java.util.List<String>> SUMMON_SKILLS = new EffectKey<>("skills");
    public static final EffectKey<Double> SCALE = new EffectKey<>("scale");
    public static final EffectKey<Double> HP_MULT = new EffectKey<>("hp_mult");
    public static final EffectKey<Integer> DELAY_TICKS = new EffectKey<>("delay_ticks");
    public static final EffectKey<Integer> INTERVAL_TICKS = new EffectKey<>("interval_ticks");
    public static final EffectKey<Integer> REPEATS = new EffectKey<>("repeats");
    public static final EffectKey<String> PRESET = new EffectKey<>("preset");
    public static final EffectKey<CounterSpec> COUNTER_SPEC = new EffectKey<>("counter_spec");
    /** 1.3 Spec1：范围效果圆心来源（event | hit_target | self/缺省）。 */
    public static final EffectKey<String> ORIGIN = new EffectKey<>("origin");
    /** 1.3 Spec1：BARRAGE 扇形微散角（度，可选）。 */
    public static final EffectKey<Double> SPREAD_ANGLE = new EffectKey<>("spread_angle");
    /** 1.3 Spec1：DAMAGE_BEAM / SALVO 瞄准档位（ray | per_player）。 */
    public static final EffectKey<String> AIM = new EffectKey<>("aim");

    // ---- 2026-10-26：Boss 读条大招 / 叠层 / 吞噬 / 场地传送 ----
    /** ADD_POTION：true = 每次施加时在已有等级上 +1（永久叠层 debuff/增益，如挖掘疲劳、力量）。 */
    public static final EffectKey<Boolean> STACK_AMP = new EffectKey<>("stack_amp");
    /** DELAYED_AREA：伤害按目标身上某药水的层数放大（每层 × per_level）。 */
    public static final EffectKey<String> SCALE_BY_POTION = new EffectKey<>("scale_by_potion");
    public static final EffectKey<Double> PER_LEVEL = new EffectKey<>("per_level");
    /** DELAYED_AREA：延迟期间环内玩家的移速削减（mul，0.5 = -50%）与持续 tick。 */
    public static final EffectKey<Double> SLOW_PERCENT = new EffectKey<>("slow_percent");
    public static final EffectKey<Integer> SLOW_TICKS = new EffectKey<>("slow_ticks");
    /** DEVOUR / CONSUME_STACKS：给自身叠加/消耗的药水名。 */
    public static final EffectKey<String> GAIN_POTION = new EffectKey<>("gain_potion");
    /** CONSUME_STACKS：每消耗一层，对玩家施加的晕眩 tick。 */
    public static final EffectKey<Integer> PER_STACK_TICKS = new EffectKey<>("per_stack_ticks");

    private EffectKeys() {}
}
