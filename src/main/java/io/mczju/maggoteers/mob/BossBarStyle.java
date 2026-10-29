package io.mczju.maggoteers.mob;

import net.kyori.adventure.bossbar.BossBar;

import java.util.Locale;

/**
 * Boss 血条外观（D11 / 2026-10-26）：原来 {@code attachBossBar} 把颜色/样式写死成
 * {@code RED / PROGRESS}，内容侧无法区分"普通 Boss / 狂暴阶段 / 隐藏 Boss"。
 *
 * <p>YAML 侧（写在 step 或乘客节点上，与 {@code boss_bar: true} 并列）：
 * <pre>
 * boss_bar: true
 * bar_color: PURPLE      # PINK/BLUE/RED/GREEN/YELLOW/PURPLE/WHITE
 * bar_overlay: NOTCHED_10 # PROGRESS/NOTCHED_6/NOTCHED_10/NOTCHED_12/NOTCHED_20
 * </pre>
 *
 * <p>解析容错：写错的名字**降级**为默认（RED / PROGRESS）而不是崩启动——内容错字由
 * {@code MobSkillParamKeySchemaTest}/schema 测试兜底，运行时不该因为一个颜色名让整局开不起来。
 */
public record BossBarStyle(BossBar.Color color, BossBar.Overlay overlay) {

    public static final BossBarStyle DEFAULT = new BossBarStyle(BossBar.Color.RED, BossBar.Overlay.PROGRESS);

    public static BossBarStyle parse(String color, String overlay) {
        return new BossBarStyle(parseColor(color), parseOverlay(overlay));
    }

    public static BossBar.Color parseColor(String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT.color();
        try {
            return BossBar.Color.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return DEFAULT.color();
        }
    }

    public static BossBar.Overlay parseOverlay(String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT.overlay();
        try {
            return BossBar.Overlay.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return DEFAULT.overlay();
        }
    }

    public boolean isDefault() {
        return color == DEFAULT.color() && overlay == DEFAULT.overlay();
    }
}
