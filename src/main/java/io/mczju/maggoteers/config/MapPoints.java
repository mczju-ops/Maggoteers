package io.mczju.maggoteers.config;

import io.mczju.maggoteers.wave.Vec3;
import java.util.Map;

/** 单张图的 points.yml：玩家出生点（相对）+ 编号刷怪点（相对）。绝对坐标由 Coords 叠层原点。 */
public record MapPoints(Vec3 playerSpawn, Map<String, Vec3> spawnPoints) {
    public Vec3 point(String id) { return spawnPoints.get(id); }

    /** 非 boss 刷怪点数量（不含 {@code boss} key）。 */
    public int nonBossSpawnCount() {
        int n = 0;
        for (String id : spawnPoints.keySet()) {
            if (!"boss".equals(id)) n++;
        }
        return n;
    }

    /**
     * 解析刷怪点：命中则返回；未配置且非 boss 点数少于 9 且存在 boss 时回退到 boss；
     * 否则返回 null（由调用方 fail-fast）。
     */
    public Vec3 resolvePointOrBossFallback(String id) {
        Vec3 rel = point(id);
        if (rel != null) return rel;
        if ("boss".equals(id)) return null;
        if (nonBossSpawnCount() >= 9) return null;
        return point("boss");
    }
}
