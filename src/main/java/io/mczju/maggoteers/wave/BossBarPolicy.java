package io.mczju.maggoteers.wave;

/**
 * Boss 血条挂载判定（纯函数，便于单测）。
 *
 * <p>历史 Bug（2026-10 审计发现）：{@code WaveEngine.trackSpawned} 用
 * {@code step != null ? step.bossBar() : profile.bossBar()} 三目 —— 而乘客/亡语生成物
 * 走的是"父 step 的 lambda"，{@code step} 恒非 null，于是**乘客自己声明的 boss_bar 永远被丢弃**：
 * {@code b1_bandit_chief}（飞天大盗，骑在岩浆怪上）与 {@code b2_silent_guard}（猪灵将军，骑在
 * 疣猪兽上）虽然写了 {@code boss_bar: true}，实际完全没有血条。
 *
 * <p>语义：step 或实体自身任一声明"要血条"就挂。
 */
public final class BossBarPolicy {

    public static boolean wantBar(boolean stepBar, boolean profileBar) {
        return stepBar || profileBar;
    }

    private BossBarPolicy() {}
}
