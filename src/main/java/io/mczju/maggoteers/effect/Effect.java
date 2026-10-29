package io.mczju.maggoteers.effect;

/** Effect 目录（§10.2，23 种）。 */
public enum Effect {
    ADD_ATTRIBUTE,   // params: attr, op(PERCENT|FLAT|MULTIPLY), value
    ADD_POTION,      // params: potion, amp, duration_ticks(0=常驻)
    HEAL,            // params: amount
    DAMAGE_AREA,     // params: radius, damage, targets, enemy_scope
    DAMAGE_BEAM,     // params: damage, heal_amount, ray_length, beam_radius, targets, enemy_scope
    HEAL_AREA,       // params: amount, radius, targets, include_self
    GRANT_REVIVE,    // params: count
    BUFF_AREA,       // params: potions[], targets, radius, fx, mark_fx, mark_on
    /** params: duration_ticks, targets(enemies|hit_target|attacker), radius, enemy_scope, fx, mark_fx, mark_on */
    DISABLE_AI,
    /** 范围光环：params 见 {@link AuraParams}；grant 嵌套在 params 内。 */
    AURA,
    /** params: item (IC id), amount */
    GRANT_ITEM,
    /** params: entity, anchor, cleanup, attributes, tamed, projectile, friendly_fire */
    SUMMON,
    /** Bound equipment view; params: slot, items_by_level. Permanent only. */
    BOUND_EQUIP,

    // ---- 1.3.2：条件动态属性 + 复活转化（金币/复活次数）----
    /** 按玩家状态动态评估的属性加成：params: attr, op, source,
     *  source=coin_count|revive_zero 用 per_unit 或 value（1.3.2 原语义）；
     *  source=kills|hits_taken|deaths|waves_cleared|acts_cleared 走**阈值折叠**
     *  （每累计 threshold 次算一层，默认 10；值 = floor(count/threshold) × per_unit × level）。常驻。 */
    DYNAMIC_ATTRIBUTE,
    /** 获取时一次性转化：失去全部剩余复活次数，每失去一次获得 value 点生命上限（值存回 params.VALUE）。常驻。 */
    MAX_HEALTH_PER_REVIVE,
    /** 2026-10-27（喀戎之引）：不再获得任何普通货币（GRANT_ITEM / 掉落）。常驻、无 params。 */
    CURRENCY_LOCK,
    /**
     * 2026-10-28（诅咒·贫穷）：**夺走**背包里的普通货币（被夺走 ≠ 花掉，不发 ON_SPEND）。
     * params: {@code amount}（>0 定量；缺省/&lt;=0 = 全部）。
     * 无 trigger = 拾取即生效；ON_COUNTER 驱动 = 每计满一次扣一次。
     */
    LOSE_COINS,
    /** 认知失调（诅咒）：互换玩家的物攻加成与魔攻加成。常驻、无 params；获取时全量重派生既有效果，
     *  此后新增的物攻/魔攻加成同样互换（PlayerCombatStats.effectiveAttrName 读时互换，不改效果 params）。 */
    SWAP_ATTACK_MAGIC,

    // ---- 1.3 Spec1：mob 侧并入 + 新需求 ----
    DAMAGE,              // 单体独立伤害（MagicDamage.applyIndependent）
    SALVO,               // 光束连射（aim: ray | per_player）
    BARRAGE,             // 弹体连发（三连发射）
    TELEPORT,            // 安全传送（anchor: random | player）
    DISPLACE,            // 位移（拽玩家）
    PERMANENT_ATTRIBUTE, // 永久叠加属性
    COUNTER_ARM,         // 计数器武装
    SOUND,               // 播放音效
    PARTICLE_RING,       // 粒子环/预设
    CLEAR_POTIONS,       // 清除药水

    // ---- 2026-10-26：Boss 读条大招 / 叠层 / 吞噬 / 场地传送 ----
    DELAYED_AREA,        // 延迟范围伤害：origin(self|other_boss) 处画环 + 环内减速，delay_ticks 后对环内玩家造成
                         // damage 或 per_level×(目标药水层数)；params: radius/damage/delay_ticks/particle/
                         // slow_percent/slow_ticks/scale_by_potion/per_level/origin
    STUN,                // 定身：targets(players|area+radius) 获得 duration_ticks 的移动/跳跃归零
    DEVOUR,              // 吞噬：吃掉半径内最近的同类型被追踪怪（不回掉落），回 amount 血、获得 1 层 gain_potion
    CONSUME_STACKS,      // 消耗自身 gain_potion 全部层数：每层对全场玩家施加 per_stack_ticks 晕眩
    FANG_PATTERN,
    STEAL_ITEM,          // 2026-10-26：thief —— 夺走目标主手武器，掉在施法者脚下
    CURSE_PLAYERS,       // 2026-10-26：给全场玩家挂常驻负面（走 PlayerState，如 -50% 上限 / +50 冷却系数）        // 2026-10-26：唤魔尖牙法阵（3 预设 + 逐格预警 + 逐格命中 + 魔法伤害）
    FREEZE,              // 冻结：目标进入 powder-snow 冻结（setFreezeTicks），参数 duration_ticks + targets
    /**
     * 2026-10-29：**扇形打击**——以施法者为圆心、沿准星方向的锥形范围。
     * params: {@code angle}（度数，**不吃 RANGE**）、{@code radius}（**吃 RANGE**）、
     * 可选 {@code damage} / {@code potions[]} / {@code freeze_ticks} / {@code fx}；
     * 目标筛选走 {@code targets: enemies} + {@code enemy_scope}（默认 tracked）。
     * 几何唯一求值处：{@link ConeMath#inCone}。
     */
    CONE,
    /**
     * 2026-10-29：**点燃**——setFireTicks(ignite_ticks)。
     * targets 语义同 FREEZE（默认 hit_target；enemies + radius 范围点燃）。
     * 首个用户：活化火石光环（AURA grant）。
     */
    IGNITE
}
