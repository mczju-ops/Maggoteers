package io.mczju.maggoteers.ambience;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 氛围音效配置（config.yml {@code ambience:} 段）。四组：{@code enter_act / wave_start / boss_start / final_boss}。
 * <p>纯字符串解析（不触 {@code Sound} 类初始化，headless 可测）；Sound 名在运行时
 * {@link WaveAmbienceService} 经 {@code GameRegistries.sound} 解析，未知名则静默跳过该组音效。
 * 热重载时重新 load。
 */
public final class WaveAmbienceConfig {
    private static final java.util.List<String> PRESET_KEYS =
            java.util.List.of("enter_act", "wave_start", "boss_start", "final_boss", "victory");
    private static final Map<String, WaveAmbience> PRESETS = new ConcurrentHashMap<>();

    private WaveAmbienceConfig() {}

    public static void load(FileConfiguration cfg) {
        PRESETS.clear();
        ConfigurationSection root = cfg.getConfigurationSection("ambience");
        if (root == null) return;
        for (String key : PRESET_KEYS) {
            ConfigurationSection sec = root.getConfigurationSection(key);
            if (sec != null) {
                PRESETS.put(key, parse(key, sec));
            }
        }
    }

    public static WaveAmbience get(String key) {
        return PRESETS.get(key);
    }

    private static WaveAmbience parse(String key, ConfigurationSection sec) {
        String soundName = sec.getString("sound", "");
        float volume = (float) sec.getDouble("volume", 1.0);
        float pitch = (float) sec.getDouble("pitch", 1.0);
        int repeat = Math.max(1, sec.getInt("repeat", 1));
        int gap = Math.max(1, sec.getInt("repeat_gap_ticks", 12));
        String title = sec.getString("title");
        ConfigurationSection times = sec.getConfigurationSection("title_times");
        int fadeIn = times == null ? 5 : times.getInt("fade_in", 5);
        int stay = times == null ? 60 : times.getInt("stay", 60);
        int fadeOut = times == null ? 10 : times.getInt("fade_out", 10);
        return new WaveAmbience(soundName, volume, pitch, repeat, gap, title, fadeIn, stay, fadeOut);
    }
}
