package io.mczju.maggoteers.mob;

/** 怪物技能触发类型（与玩家被动对称；计数器为独立 trigger）。 */
public enum MobTrigger {
    ATTACK,          // 攻击时（MobSkillService 注入 target）
    DAMAGE_TAKEN,    // 受击时（注入 source）
    KILLED,          // 被击杀时（注入 killer）
    SPAWN,           // 生成时（出生施加）
    TICK,            // 光环类：每 cooldown_sec 触发一次（tick 心跳）
    COUNTER          // 计数器计满（MobCounterRegistry 推进）
}
