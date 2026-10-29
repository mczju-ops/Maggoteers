package io.mczju.maggoteers.reward;

import io.mczju.maggoteers.effect.Effect;
import io.mczju.maggoteers.effect.EffectKeys;
import io.mczju.maggoteers.effect.PlayerEffect;
import io.mczju.maggoteers.effect.Stack;
import io.mczju.maggoteers.state.PlayerState;

import java.util.Set;

/** Pure draw visibility for reward pools (STAT/WEAPON once, SUPPLY repeatable, UPGRADE until max). */
public final class RewardDrawVisibility {

    private RewardDrawVisibility() {}

    public static boolean isVisible(RewardOption opt, PlayerState ps, Set<String> acquiredIds) {
        return isVisible(opt, ps, acquiredIds, null);
    }

    public static boolean isVisible(RewardOption opt, PlayerState ps, Set<String> acquiredIds, RewardPool pool) {
        return switch (opt.category()) {
            case SUPPLY -> true;
            case WEAPON -> acquiredIds == null || !acquiredIds.contains(opt.id());
            case STAT, BUNDLE -> isVisibleStat(opt, ps, acquiredIds, pool);
        };
    }

    private static boolean isVisibleStat(RewardOption opt, PlayerState ps, Set<String> acquiredIds,
                                         RewardPool pool) {
        if (opt.isBundle()) {
            // bundle 以自身 id 的 acquiredUnique 判定一次性（R11）：含 WEAPON/SUPPLY grant 的
            // bundle 不会因 grant 不进 acquiredUnique 而恒可见、无限重复发放。
            return acquiredIds == null || !acquiredIds.contains(opt.id());
        }
        if (ps == null) return true;
        // 2026-10-28（魔王的旗帜）：即时 GRANT_REVIVE 带 min_revives 门槛时，门槛不满足就不入池——
        // 否则玩家会抽到一个"当前拿不到任何东西"的藏品（RewardService 里同样有兜底门槛）。
        if (opt.effect() == Effect.GRANT_REVIVE && opt.trigger() == null) {
            int min = opt.params() == null ? 0
                    : opt.params().getOrDefault(EffectKeys.MIN_REVIVES, 0);
            if (min > 0 && ps.getReviveCount() < min) {
                return false;
            }
        }
        PlayerEffect pe = ps.effects().stream().filter(e -> e.id().equals(opt.id())).findFirst().orElse(null);
        if (pe == null) {
            // One-shot STATs that never leave an effect (legacy instant GRANT_REVIVE) still
            // mark acquiredUnique; UPGRADE_LEVEL first acquire has empty acquired → visible.
            if (opt.stack() != Stack.UPGRADE_LEVEL
                    && acquiredIds != null
                    && acquiredIds.contains(opt.id())) {
                return false;
            }
            return true;
        }
        if (opt.stack() == Stack.UPGRADE_LEVEL) {
            int cap = UpgradeLevelCaps.resolve(pool, opt);
            return pe.level() < cap;
        }
        return false;
    }
}
