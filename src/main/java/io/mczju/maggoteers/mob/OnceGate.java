package io.mczju.maggoteers.mob;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 只触发一次的技能门（D1「血线阶段」的配套，2026-10-26）。
 *
 * <p>{@code mob_skills.yml} 里写 {@code once: true} 的技能（典型：{@code tick} + {@code condition: {hp_below: 0.5}}
 * 的"半血进入二阶段"）在**该实体一生**只执行一次；detach（死亡/清波）时随实体清理。
 *
 * <p>抽成独立类是为了能脱离 Bukkit 单测（纯内存 Set 语义）。
 */
public final class OnceGate {

    private static final Map<UUID, Set<String>> FIRED = new ConcurrentHashMap<>();

    private OnceGate() {
    }

    /**
     * 尝试占用一次触发机会。
     *
     * @return true = 第一次（应当执行）；false = 已经触发过
     */
    public static boolean tryConsume(UUID entity, String skillId) {
        if (entity == null || skillId == null) return false;
        Set<String> fired = FIRED.computeIfAbsent(entity, u -> ConcurrentHashMap.newKeySet());
        return fired.add(skillId);
    }

    /** 该实体是否已触发过某技能（只读查询，不占用）。 */
    public static boolean hasFired(UUID entity, String skillId) {
        Set<String> fired = entity == null ? null : FIRED.get(entity);
        return fired != null && fired.contains(skillId);
    }

    public static void forget(UUID entity) {
        if (entity != null) FIRED.remove(entity);
    }

    public static void clearAll() {
        FIRED.clear();
    }

    /** 测试用：当前登记实体数。 */
    static int size() {
        return FIRED.size();
    }
}
