package io.mczju.maggoteers.plan;

import io.mczju.maggoteers.wave.Vec3;
import io.mczju.maggoteers.wave.WaveSpec;
import java.util.List;

/**
 * 一层的已解析剧本：层 id + 地图 id + 玩家出生点（绝对）+ 有序波次（weak×M、strong×N、boss×1）。
 *
 * <p>{@code actId} 自 2026-10-26 起随剧本携带：历史上由 {@code "act" + (actIndex + 1)} 现推，
 * 导致层数/层号一旦变化（如第四层）就会静默错层（原点、诅咒池、奖励池、地图全按错误 id 取）。
 */
public record ActPlan(String actId, String mapId, Vec3 playerSpawn, List<WaveSpec> waves) {
    public ActPlan {
        waves = List.copyOf(waves);
    }
}
