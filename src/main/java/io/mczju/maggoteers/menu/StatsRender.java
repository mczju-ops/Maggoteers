package io.mczju.maggoteers.menu;

import io.mczju.maggoteers.config.MessageService;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 「查看玩家数值」的纯格式化输出（2026-10-27 改版）。
 *
 * <p>8 行：物攻倍率 / 魔攻倍率 / 冷却倍率 / 治疗强度 / 施法范围 / 速度倍率 / 生命上限 / 触及距离。
 * 文案模板来自 config.yml {@code messages.menu.stats.*}（{@code {value}} 占位符），
 * 数值由调用方采集后传入——本类不碰 Bukkit/Player，可 headless 单测。
 */
public final class StatsRender {

    /** 倍率保留两位小数；冷却显示为百分数（0.40 → "40%"）。 */
    private static final String MUL = "%.2f";

    private StatsRender() {}

    public static List<String> renderLines(double atkMul, double magicMul, double cdMul,
                                           double healMul, double rangeMul, double speedMul,
                                           double maxHp, double reach) {
        return List.of(
                renderLine("menu.stats.atk", String.format(Locale.ROOT, MUL, atkMul)),
                renderLine("menu.stats.magic", String.format(Locale.ROOT, MUL, magicMul)),
                renderLine("menu.stats.cd", String.format(Locale.ROOT, MUL, cdMul)),
                renderLine("menu.stats.heal", String.format(Locale.ROOT, MUL, healMul)),
                renderLine("menu.stats.range", String.format(Locale.ROOT, MUL, rangeMul)),
                renderLine("menu.stats.speed", String.format(Locale.ROOT, MUL, speedMul)),
                renderLine("menu.stats.maxhp", String.format(Locale.ROOT, "%.0f", maxHp)),
                renderLine("menu.stats.reach", String.format(Locale.ROOT, "%.1f", reach))
        );
    }

    private static String renderLine(String key, String value) {
        return MessageService.raw(key, Map.of("value", value));
    }
}
