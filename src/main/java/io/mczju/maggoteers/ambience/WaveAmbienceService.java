package io.mczju.maggoteers.ambience;

import com.github.mczjuops.mczjugamecore.game.AbstractGame;
import com.github.mczjuops.mczjugamecore.player.PlayerExt;
import io.mczju.maggoteers.MaggoteersPlugin;
import io.mczju.maggoteers.util.GameRegistries;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * 氛围音效 + 大字广播播放（config.yml {@code ambience:} 段驱动，零 Java 可换音效/文案）。
 * <ul>
 *   <li>{@code enter_act}：进层音效</li>
 *   <li>{@code wave_start}：非 boss 波开始音效</li>
 *   <li>{@code boss_start}：boss 波开始音效 + 黑体大字关卡名</li>
 *   <li>{@code final_boss}：最终 boss 波音效（连播 repeat 次）+ 屏幕中央白色黑体大字关卡名</li>
 * </ul>
 */
public final class WaveAmbienceService {
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private WaveAmbienceService() {}

    /**
     * 进层氛围（音效 + 可选大字）。⚠️ 传 {@code ""} 而不是 {@code null}：{@link #play} 只在
     * {@code levelName != null} 时才播标题，传 null 会让 config 里配置的 {@code enter_act.title}
     * 静默失效（2026-10-29 给进层加了「区区恶敌？！」标题才发现这个坑）。进层没有关卡名可插值，
     * 故 title 里不要写 {name}。
     */
    public static void playEnterAct(AbstractGame game) { play(game, "enter_act", ""); }
    public static void playWaveStart(AbstractGame game) { play(game, "wave_start", null); }
    public static void playBoss(AbstractGame game, String levelName) { play(game, "boss_start", levelName); }
    public static void playFinalBoss(AbstractGame game, String levelName) { play(game, "final_boss", levelName); }

    /**
     * 通关（终局 Boss 清完）的胜利字幕：{@code ambience.victory}（title 不需要 {name}，
     * 传空串以满足"有 title 才播"的判定）。2026-10-28：终局 Boss 后不再直接结算，
     * 而是先播这个字幕 + 奖杯，再进入休整期。
     */
    public static void playVictory(AbstractGame game) { play(game, "victory", ""); }

    private static void play(AbstractGame game, String key, String levelName) {
        WaveAmbience a = WaveAmbienceConfig.get(key);
        if (a == null) return;
        if (a.hasTitle() && levelName != null) {
            Component title = MM.deserialize(a.title().replace("{name}", MM.escapeTags(levelName)));
            showTitle(game, title, a);
        }
        if (!a.hasSound()) return;
        // repeat 连播：每次间隔 repeat_gap_ticks（调度错开，避免同刻重叠听不出"几次"）
        for (int i = 0; i < a.repeat(); i++) {
            final int idx = i;
            new BukkitRunnable() {
                @Override public void run() { playSound(game, a); }
            }.runTaskLater(MaggoteersPlugin.getInstance(), (long) idx * a.repeatGapTicks());
        }
    }

    private static void playSound(AbstractGame game, WaveAmbience a) {
        String raw = a.soundName();
        if (raw == null || raw.isBlank()) return;
        org.bukkit.Sound sound = raw.contains(":") ? null : GameRegistries.sound(raw);
        for (PlayerExt pe : game.getPlayers()) {
            Player p = pe.player();
            if (p == null) continue;
            if (sound != null) {
                p.playSound(p.getLocation(), sound, SoundCategory.AMBIENT, a.volume(), a.pitch());
            } else if (raw.contains(":")) {
                p.playSound(p.getLocation(), raw, SoundCategory.AMBIENT, a.volume(), a.pitch());
            }
        }
    }

    private static void showTitle(AbstractGame game, Component title, WaveAmbience a) {
        Title t = Title.title(title, Component.empty(),
                Title.Times.times(a.fadeIn(), a.stay(), a.fadeOut()));
        for (PlayerExt pe : game.getPlayers()) {
            Player p = pe.player();
            if (p == null) continue;
            p.showTitle(t);
        }
    }
}
