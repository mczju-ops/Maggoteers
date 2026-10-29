package io.mczju.maggoteers.effect;

import java.util.List;
import java.util.UUID;

/**
 * {@code DELAYED_AREA} 的圆心选择（2026-10-26 修正 + 用户新口径），纯逻辑、可单测。
 *
 * <h2>为什么要单独抽出来</h2>
 * 旧实现 {@code origin: other_boss} 取的是"**最近的另一个被追踪怪**"——而 Boss 波里同时有
 * 疣猪兽 / 凋灵骷髅 / 猪灵蛮兵等杂兵，于是环落在小怪身上（实测：迈入永恒的环出现在凋灵骷髅小怪脚下），
 * 玩家永远不在圈里 ⇒ 黑暗/减速/伤害全都没感觉。现在**只允许挑带 {@code boss_bar: true} 的怪**。
 *
 * <h2>用户新口径（2026-10-26）</h2>
 * 双王之一死后，剩下那个既没有"另一个 Boss"可联动 ⇒ 读条大招的落点改为**最近的玩家**
 * （"把读条大招的目标改为玩家"），而不是照旧糊在自己脚下。
 *
 * 优先级：其它 Boss（最近） → 玩家（最近） → 自己。
 */
public final class DelayedAreaOrigin {

    /** 候选实体：uuid + 与施法者的距离平方。 */
    public record Candidate(UUID uuid, double distSq) {
    }

    /** 落点归属。 */
    public enum Kind { OTHER_BOSS, PLAYER, SELF }

    public record Choice(Kind kind, UUID uuid) {
    }

    private DelayedAreaOrigin() {
    }

    /**
     * @param caster  施法者（自己不算"另一个 Boss"）
     * @param bosses  其它 Boss 候选（调用方已排除自己）
     * @param players 玩家候选
     */
    public static Choice choose(UUID caster, List<Candidate> bosses, List<Candidate> players) {
        Candidate boss = nearest(bosses);
        if (boss != null) return new Choice(Kind.OTHER_BOSS, boss.uuid());
        Candidate player = nearest(players);
        if (player != null) return new Choice(Kind.PLAYER, player.uuid());
        return new Choice(Kind.SELF, caster);
    }

    /** 最近的候选（跳过 null / 非有限距离）；无候选返回 null。 */
    static Candidate nearest(List<Candidate> list) {
        Candidate best = null;
        if (list == null) return null;
        for (Candidate c : list) {
            if (c == null || c.uuid() == null) continue;
            double d = c.distSq();
            if (Double.isNaN(d) || Double.isInfinite(d)) continue;
            if (best == null || d < best.distSq()) best = c;
        }
        return best;
    }
}
