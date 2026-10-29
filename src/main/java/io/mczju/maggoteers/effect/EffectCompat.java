package io.mczju.maggoteers.effect;

import java.util.EnumSet;
import java.util.Set;

/**
 * Effect × 触发路径的兼容矩阵（纯声明，加载期校验用）。
 *
 * <p>背景（2026-10 审计）：{@code Effect} 有 26 个值，但<b>三条执行通道并不互通</b>，
 * 而当时的校验只看 trigger 白名单、不看 effect 是否属于该通道 —— 配置写了却静默无效果：
 *
 * <ul>
 *   <li><b>A 玩家被动/触发</b>（reward STAT / held / 计数器）：只走
 *       {@code EffectService.executeEffect} 的 switch —— 就是
 *       {@link #PLAYER_TRIGGERED} 这 11 个。</li>
 *   <li><b>B 只能常驻</b>（获取时由 {@code applyDerived} 应用，禁 trigger/expiry）：
 *       {@link #PERMANENT_ONLY}。</li>
 *   <li><b>C 仅怪物施法 / 武器 use_ability</b>（走 {@code executeEffectCore} / {@code executeMagicEffect}）：
 *       {@link #MOB_OR_WEAPON_ONLY}。玩家被动里写它们 = 什么都不发生。</li>
 * </ul>
 *
 * <p>守护：{@code EffectCompatTest} 断言三者恰好覆盖 {@code Effect.values()} ——
 * 以后新增 Effect 若忘了归类，测试会失败，而不是让配置继续静默失效。
 */
public final class EffectCompat {

    /** A：玩家被动/触发通道可执行（{@code EffectService.executeEffect} 的 switch）。 */
    public static final Set<Effect> PLAYER_TRIGGERED = EnumSet.of(
            Effect.HEAL,
            Effect.ADD_POTION,
            Effect.DAMAGE_AREA,
            Effect.DAMAGE_BEAM,
            Effect.HEAL_AREA,
            Effect.BUFF_AREA,
            Effect.DISABLE_AI,
            Effect.GRANT_REVIVE,
            Effect.GRANT_ITEM,
            Effect.SUMMON,
            // 2026-10-28：诅咒·贫穷——夺走玩家背包里的普通货币
            Effect.LOSE_COINS,
            // 2026-10-28：寒冷（powder-snow 冻结）也能由玩家侧施加——
            // 武器 held_effects「攻击附带寒冷」/ 奖励藏品走 executeEffect → executeFreezeCore。
            Effect.FREEZE,
            // 2026-10-29：扇形打击（CONE）——武器 use_ability、held 触发、怪物施法都走 executeConeCore
            Effect.CONE,
            // 2026-10-29：点燃（IGNITE）——武器 / held / AURA grant（活化火石）
            Effect.IGNITE,
            Effect.ADD_ATTRIBUTE);

    /** B：只能常驻（无 fireTrigger / 无 expiry），获取时由 applyDerived 生效。 */
    public static final Set<Effect> PERMANENT_ONLY = EnumSet.of(
            Effect.AURA,
            Effect.BOUND_EQUIP,
            Effect.DYNAMIC_ATTRIBUTE,
            Effect.MAX_HEALTH_PER_REVIVE,
            Effect.SWAP_ATTACK_MAGIC,
            // 2026-10-27：喀戎之引——不再获得普通货币（常驻、无 params）
            Effect.CURRENCY_LOCK);

    /** C：只有怪物施法或武器 use_ability/held_effects 能执行；玩家被动里是静默无效果。 */
    public static final Set<Effect> MOB_OR_WEAPON_ONLY = EnumSet.of(
            Effect.DAMAGE,
            Effect.SALVO,
            Effect.BARRAGE,
            Effect.TELEPORT,
            Effect.DISPLACE,
            Effect.PERMANENT_ATTRIBUTE,
            Effect.COUNTER_ARM,
            Effect.SOUND,
            Effect.PARTICLE_RING,
            Effect.CLEAR_POTIONS,
            // 2026-10-26：Boss 读条大招 / 叠层 / 吞噬 / 场地传送（怪物侧或武器侧）
            Effect.DELAYED_AREA,
            Effect.STUN,
            Effect.DEVOUR,
            Effect.CONSUME_STACKS,
            Effect.FANG_PATTERN,
            Effect.STEAL_ITEM,
            // 2026-10-28：FREEZE 已移入 A（玩家触发通道）——武器「攻击附带寒冷」走 held_effects。
            Effect.CURSE_PLAYERS);

    public static boolean playerPassiveSupported(Effect e) {
        return e != null && PLAYER_TRIGGERED.contains(e);
    }

    public static boolean permanentOnly(Effect e) {
        return e != null && PERMANENT_ONLY.contains(e);
    }

    public static boolean mobOrWeaponOnly(Effect e) {
        return e != null && MOB_OR_WEAPON_ONLY.contains(e);
    }

    /** 玩家被动/触发路径是否可执行（含"只能常驻"那类，由调用方按 trigger 判定）。 */
    public static boolean expressibleForPlayerPassive(Effect e) {
        return playerPassiveSupported(e) || permanentOnly(e);
    }

    private EffectCompat() {}
}
