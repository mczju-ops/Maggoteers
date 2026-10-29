package io.mczju.maggoteers.world;

import io.mczju.maggoteers.world.MapRepository.MapEntry;
import java.util.List;
import java.util.Map;

/** 地图库纯数据视图（act → 该层所有地图条目），供 RunPlanner 纯函数消费。 */
public record MapLibrary(Map<String, List<MapEntry>> byAct) {
    public List<MapEntry> maps(String act) { return byAct.getOrDefault(act, List.of()); }
}
