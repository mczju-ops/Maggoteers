package io.mczju.maggoteers.effect;

import io.mczju.maggoteers.state.PlayerState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 计数器运行时（玩家侧）。纯逻辑部分（advance）与 Bukkit 解耦，便于单测。
 * <p>advance 语义：以 {@code t} 为输入信号，推进所有 countTrigger==t 且未被 sourceId 排除
 * 且通过 gate 的计数器；计满的 id 会作为 ON_COUNTER 信号继续驱动 countTrigger==ON_COUNTER
 * 的其它计数器（跳过自身防自环）。返回<b>全部</b>计满 id（含串联各级）。
 */
public final class CounterService {

    private CounterService() {}

    public static void register(PlayerState ps, CounterSpec spec) {
        if (ps == null || spec == null || spec.id().isBlank()) {
            return;
        }
        ps.counters().put(spec.id(), new CounterState(spec));
    }

    public static void unregister(PlayerState ps, String counterId) {
        if (ps == null || counterId == null) {
            return;
        }
        ps.counters().remove(counterId);
    }

    public static void clearAll(PlayerState ps) {
        if (ps != null) {
            ps.counters().clear();
        }
    }

    /** 对齐 ps.counters 与 effects 列表：注册列表中携带的计数器、注销不再携带的（held 挂载/卸载用）。 */
    public static void reconcile(PlayerState ps, java.util.List<PlayerEffect> effects) {
        if (ps == null) return;
        Map<String, CounterSpec> present = new java.util.LinkedHashMap<>();
        for (PlayerEffect e : effects) {
            if (e == null || e.params() == null) continue;
            CounterSpec spec = e.params().get(EffectKeys.COUNTER_SPEC);
            if (spec != null && !spec.id().isBlank()) present.put(spec.id(), spec);
        }
        for (String id : new HashSet<>(ps.counters().keySet())) {
            if (!present.containsKey(id)) ps.counters().remove(id);
        }
        for (Map.Entry<String, CounterSpec> en : present.entrySet()) {
            ps.counters().putIfAbsent(en.getKey(), new CounterState(en.getValue()));
        }
    }

    /** 清零修饰符：该 trigger 触发时，resetOn 匹配的计数器清零（本次不计数）。 */
    public static void resetMatching(PlayerState ps, Trigger firedTrigger) {
        if (ps == null || firedTrigger == null) {
            return;
        }
        for (CounterState cs : ps.counters().values()) {
            if (firedTrigger == cs.spec().resetOn()) {
                cs.reset();
            }
        }
    }

    /**
     * @param t        输入信号（外部 trigger 映射来的 CounterCountTrigger；串联在内部处理）
     * @param sourceId 初始来源 id（外部调用传 null；内联中继时传信号来源）
     * @param gate     条件门（玩家侧由 EffectService 构造 spec.condition().evaluate(game, player)）
     * @return 计满的计数器 id 列表（去重、含串联链）
     */
    public static List<String> advance(PlayerState ps, CounterCountTrigger t, String sourceId,
                                       Predicate<CounterSpec> gate) {
        if (ps == null || ps.counters().isEmpty()) {
            return List.of();
        }
        List<String> fired = new ArrayList<>();
        Set<String> processed = new HashSet<>();
        Deque<String> relayQueue = new ArrayDeque<>();
        collect(ps, t, sourceId, gate, fired, processed, relayQueue);
        while (!relayQueue.isEmpty()) {
            String sig = relayQueue.poll();
            collect(ps, CounterCountTrigger.ON_COUNTER, sig, gate, fired, processed, relayQueue);
        }
        return List.copyOf(fired);
    }

    private static void collect(PlayerState ps, CounterCountTrigger t, String excludeId,
                                Predicate<CounterSpec> gate, List<String> fired,
                                Set<String> processed, Deque<String> relayQueue) {
        for (CounterState cs : ps.counters().values()) {
            CounterSpec spec = cs.spec();
            if (!spec.triggeredBy(t)) {
                continue;
            }
            if (excludeId != null && excludeId.equals(cs.id())) {
                continue;                       // 防自环
            }
            if (processed.contains(cs.id())) {
                continue;
            }
            if (gate != null && !gate.test(spec)) {
                continue;
            }
            processed.add(cs.id());             // 同一次外部输入最多计一次（下游串联不重复计）
            if (cs.increment()) {
                fired.add(cs.id());
                relayQueue.add(cs.id());        // 作为 ON_COUNTER 信号继续中继
            }
        }
    }
}
