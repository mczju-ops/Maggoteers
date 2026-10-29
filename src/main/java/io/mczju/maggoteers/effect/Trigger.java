package io.mczju.maggoteers.effect;

/**
 * 触发目录（§10.1）。监听器在各 fire-point 调 {@link EffectService#fireTrigger}。
 * <p><b>2026-10-26 起只剩"内容可用"的 7 个（2026-10-27 新增 {@code ON_BOSS_KILL} = 8 个；
 * 2026-10-28 新增 {@code ON_SPEND} = 9 个）</b>：原 {@code ON_TICK_1S} / {@code ON_REVIVE} 已删除——
 * <ul>
 *   <li>计时需求 → 玩家侧计数器 {@code counter{count: tick}}（{@link CounterCountTrigger#TICK}，
 *       由 {@code EffectService.tickGame} 每秒推进），计满走 {@code ON_COUNTER}。</li>
 *   <li>复活需求 → 复活流程本身（{@code EffectService.resync} + {@code restoreFullHealth} +
 *       {@code applyReviveBuffs}），不再有"复活触发被动"这条内容通道。</li>
 * </ul>
 * 故本枚举每一项都是奖励白名单里允许写的 trigger（见 {@code RewardLoadValidator.ALLOWED_FIRE}）。
 */
public enum Trigger {
    ON_WAVE_CLEAR, ON_ACT_ENTER,                 // 生命周期
    ON_KILL, ON_DAMAGE_DEALT, ON_DAMAGE_TAKEN,     // 战斗
    /** 2026-10-27：击杀**带血条的 Boss**（冰霜树类藏品）。普通击杀仍走 {@link #ON_KILL}。 */
    ON_BOSS_KILL,
    ON_DEATH,                                      // 玩家状态
    /**
     * 2026-10-28：玩家**花掉一枚普通货币**（卫戍币）时触发——`ItemService.spendOneKind` 成功后发出。
     * 用途：猪灵存钱罐「第一次花掉金币时失效」（{@code expiry: {trigger: ON_SPEND, charges: 1}}）。
     * 只认普通货币；星石/职业券不算"金币"。
     */
    ON_SPEND,
    ON_COUNTER                                     // 计数器计满（唯一计时/中继出口）
}
