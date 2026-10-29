package io.mczju.maggoteers.util;

import io.mczju.maggoteers.wave.Vec3;
import java.util.List;

/** 从 config 的整数列表解析层原点（纯函数，便于单测）。 */
public final class Origins {
    private Origins() {}

    public static Vec3 parse(List<Integer> list, int defaultY) {
        int x = (list == null || list.isEmpty()) ? 0 : list.get(0);
        int y = (list == null || list.size() < 2) ? defaultY : list.get(1);
        int z = (list == null || list.size() < 3) ? 0 : list.get(2);
        return new Vec3(x, y, z);
    }
}
