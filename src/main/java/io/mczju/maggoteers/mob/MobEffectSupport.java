package io.mczju.maggoteers.mob;

import io.mczju.maggoteers.effect.Effect;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 怪物施法可执行性白名单：mob 侧统一走
 * {@code EffectService.executeEffectCore}，不在其 {@code switch} 里的 Effect 会被
 * {@code default -> {}} **静默丢弃**（配置写了但毫无效果、无日志）。
 *
 * <p>本类把这些"对怪物是空实现"的 Effect 集中声明出来，供 {@code MobSkillRegistry} 加载时告警。
 * 加新 Effect / 给怪物补新能力时，两处必须同步：
 * ① {@code EffectService.executeEffectCore} 的 switch；
 * ② 这里的 {@link #UNSUPPORTED_FOR_MOB}（删掉对应条目）。
 */
public final class MobEffectSupport {

    /** 怪物施法时为空实现（玩家专属或尚未实现）的 Effect。 */
    private static final Set<Effect> UNSUPPORTED_FOR_MOB = EnumSet.of(
            Effect.AURA,              // 玩家光环（AuraService 只服务 Player）
            Effect.BUFF_AREA,         // executeAreaCore 的 mob 分支为空（玩家侧走 executeBuffArea）
            Effect.GRANT_ITEM,        // 玩家背包
            Effect.GRANT_REVIVE,      // 玩家复活次数
            Effect.LOSE_COINS,        // 2026-10-28：玩家背包里的普通货币
            Effect.BOUND_EQUIP,       // 玩家绑定装备视图
            Effect.DYNAMIC_ATTRIBUTE, // 依赖 PlayerState（金币/复活次数）
            Effect.MAX_HEALTH_PER_REVIVE,
            Effect.SWAP_ATTACK_MAGIC); // 玩家物攻/魔攻互换

    /** targets 取值中怪物侧无法解析的（2026-10-26 起已支持 allies，仅保留未知写法兜底）。 */
    private static final Set<String> UNSUPPORTED_TARGETS = Set.of("ally", "team");

    public static boolean isSupported(Effect effect) {
        return effect != null && !UNSUPPORTED_FOR_MOB.contains(effect);
    }

    public static boolean isSupportedTargets(String targets) {
        if (targets == null) return true;
        return !UNSUPPORTED_TARGETS.contains(targets.trim().toLowerCase(java.util.Locale.ROOT));
    }

    /** 返回该技能里"对怪物无效"的 effect 名（用于一次性告警）。 */
    public static List<String> unsupportedIn(MobSkillSpec spec) {
        List<String> out = new ArrayList<>();
        if (spec == null) return out;
        for (MobEffectSpec e : spec.effects()) {
            if (!isSupported(e.effect())) {
                out.add(e.effect() + "(no-op)");
            }
        }
        return out;
    }
}
