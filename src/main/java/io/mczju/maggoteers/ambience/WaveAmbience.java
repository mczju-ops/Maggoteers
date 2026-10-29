package io.mczju.maggoteers.ambience;

import java.time.Duration;

/**
 * 氛围音效 + 大字广播预设（config.yml {@code ambience:} 段解析产物）。
 * <p>存 {@code soundName} 字符串而非 {@code org.bukkit.Sound}——后者类初始化依赖 Paper Registry，
 * 配置解析保持纯字符串（headless 可测），运行时才在 {@link WaveAmbienceService} 解析成 Sound。
 * {@code title} 为 MiniMessage 模板（可含 {@code {name}} 占位 = 关卡名），null/blank 表示不广播标题。
 */
public record WaveAmbience(
        String soundName,
        float volume,
        float pitch,
        int repeat,
        int repeatGapTicks,
        String title,
        int fadeInTicks,
        int stayTicks,
        int fadeOutTicks
) {
    public boolean hasSound() { return soundName != null && !soundName.isBlank(); }
    public boolean hasTitle() { return title != null && !title.isBlank(); }

    public Duration fadeIn()  { return Duration.ofMillis(fadeInTicks * 50L); }
    public Duration stay()    { return Duration.ofMillis(stayTicks * 50L); }
    public Duration fadeOut() { return Duration.ofMillis(fadeOutTicks * 50L); }
}
