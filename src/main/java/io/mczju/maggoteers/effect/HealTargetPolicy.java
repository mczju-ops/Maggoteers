package io.mczju.maggoteers.effect;

/**
 * 范围治疗/范围增益的"同侧"判定（纯函数）。
 *
 * <p>历史 Bug（2026-10 审计发现）：{@code Effect.HEAL} 走 {@code targets: area} 时，
 * 实现是「半径内所有冒险玩家 + 半径内所有被追踪怪」一并回血 —— 即
 * <b>怪物施法会把玩家也奶了</b>（{@code s3_silent_hills} 的 {@code healer} 技能就是这个形状；
 * 该波次当时未入池，所以线上没暴露）。反过来玩家武器用 {@code targets: area} 也会治疗怪物。
 *
 * <p>语义：治疗/增益只在"同侧"生效 —— 玩家 ↔ 玩家，怪物 ↔ 被追踪怪。
 * 独立的 {@link Effect#HEAL_AREA} 对怪物曾直接 return（空实现），现与 HEAL 共用本判定。
 */
public final class HealTargetPolicy {

    public static boolean sameSide(boolean casterIsPlayer, boolean targetIsPlayer) {
        return casterIsPlayer == targetIsPlayer;
    }

    private HealTargetPolicy() {}
}
