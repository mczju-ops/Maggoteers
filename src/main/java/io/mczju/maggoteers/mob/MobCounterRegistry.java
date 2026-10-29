package io.mczju.maggoteers.mob;

import com.github.mczjuops.mczjugamecore.game.AbstractGame;
import io.mczju.maggoteers.game.MaggoteersGame;
import io.mczju.maggoteers.wave.WaveEngine;
import org.bukkit.entity.LivingEntity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 怪物计数器生命周期：出生加载、死亡卸载；输入信号推进，计满触发 COUNTER 技能。
 * <p>与玩家 CounterService 对称但独立：count 用 {@link MobTrigger}，条件以怪物为中心。
 * <p>串联语义：本 tick 计满的 id 作为 {@code COUNTER} 信号继续驱动 count==COUNTER 的其它计数器，
 * 用队列迭代直到没有新计满（防自环：跳过自身 id；防 ping-pong：每条链内每个计数器最多触发一次）。
 */
public final class MobCounterRegistry {

    private static final Map<UUID, Map<String, MobCounterState>> BY_ENTITY = new ConcurrentHashMap<>();

    /** 计数器运行态。current 从 0 起，每次匹配输入 +1，计满清零循环；requireArm 计数器须 {@link #arm} 后才推进。 */
    public record MobCounterState(MobCounterSpec spec, int current, boolean armed) {
        public MobCounterState(MobCounterSpec spec, int current) {
            this(spec, current, false);
        }

        public MobCounterState {
            if (current < 0) current = 0;
        }

        public MobCounterState increment() {
            return new MobCounterState(spec, current + 1, armed);
        }

        public boolean full() {
            return current >= spec.amount();
        }
    }

    /** 纯逻辑：输入信号 input 时该计数器应得的下一计数；计满→0（触发）。非匹配输入→原值。 */
    static int advanceCount(MobCounterSpec spec, MobTrigger input, int current) {
        if (spec == null || !spec.triggeredBy(input)) return current;
        int next = current + 1;
        return next >= spec.amount() ? 0 : next;
    }

    /** 纯逻辑：输入信号 input 是否使该计数器计满（决定是否触发）。 */
    static boolean fullAt(MobCounterSpec spec, MobTrigger input, int current) {
        if (spec == null || !spec.triggeredBy(input)) return false;
        return current + 1 >= spec.amount();
    }

    public static void load(LivingEntity le, List<MobSkillSpec> specs) {
        if (le == null || specs == null || specs.isEmpty()) return;
        Map<String, MobCounterState> counters = new HashMap<>();
        for (MobSkillSpec s : specs) {
            if (s.counter() != null) {
                counters.put(s.counter().id(), new MobCounterState(s.counter(), 0));
            }
        }
        if (!counters.isEmpty()) {
            BY_ENTITY.put(le.getUniqueId(), counters);
        }
    }

    public static void unload(UUID uuid) {
        BY_ENTITY.remove(uuid);
    }

    /** 清零修饰：{@code resetOn == t} 的计数器进度归零并解除 armed（在对应信号推进计数之前调用）。 */
    public static void resetMatching(LivingEntity mob, MobTrigger t) {
        if (mob == null || t == null) return;
        Map<String, MobCounterState> counters = BY_ENTITY.get(mob.getUniqueId());
        if (counters == null || counters.isEmpty()) return;
        for (var e : counters.entrySet()) {
            if (e.getValue().spec().resetOn() == t) {
                e.setValue(new MobCounterState(e.getValue().spec(), 0, false));
            }
        }
    }

    /** 启动 requireArm 计数器（进度归零，armed=true）。 */
    public static void arm(LivingEntity mob, String counterId) {
        if (mob == null || counterId == null || counterId.isBlank()) return;
        Map<String, MobCounterState> counters = BY_ENTITY.get(mob.getUniqueId());
        if (counters == null) return;
        MobCounterState st = counters.get(counterId);
        if (st == null || !st.spec().requireArm()) return;
        counters.put(counterId, new MobCounterState(st.spec(), 0, true));
    }

    /** 计数器是否处于活动窗口（requireArm→armed；否则 {@code current > 0}）。 */
    public static boolean isRunning(LivingEntity mob, String counterId) {
        if (mob == null || counterId == null || counterId.isBlank()) return false;
        Map<String, MobCounterState> counters = BY_ENTITY.get(mob.getUniqueId());
        if (counters == null) return false;
        MobCounterState st = counters.get(counterId);
        if (st == null) return false;
        return st.spec().requireArm() ? st.armed() : st.current() > 0;
    }

    /**
     * 输入信号推进本怪计数器；返回本 tick 最终应触发的 counter id 列表（含串联驱动）。
     * 条件不通过、count!=input 的计数器不推进。
     */
    public static List<String> signal(LivingEntity mob, MobTrigger t,
                                      LivingEntity target, LivingEntity source) {
        if (mob == null) return List.of();
        Map<String, MobCounterState> counters = BY_ENTITY.get(mob.getUniqueId());
        if (counters == null || counters.isEmpty()) return List.of();

        Deque<String> initial = new ArrayDeque<>();
        // 初筛：count==t（非 COUNTER 信号，避免自环）且计满者入队
        for (var e : counters.entrySet()) {
            MobCounterState st = e.getValue();
            if (!st.spec().triggeredBy(t) || t == MobTrigger.COUNTER) continue;
            if (st.spec().requireArm() && !st.armed()) continue;
            if (!passesCondition(mob, st.spec(), target, source)) continue;
            e.setValue(new MobCounterState(st.spec(),
                    advanceCount(st.spec(), t, st.current()), st.armed()));
            if (fullAt(st.spec(), t, st.current())) {
                initial.add(st.spec().id());
            }
        }
        return resolveChain(counters, initial,
                (spec, st) -> passesCondition(mob, spec, target, source));
    }

    /**
     * 纯逻辑：驱动串联计数器链（count==COUNTER）。visited 集合防两个计数器互 ping-pong；
     * 每个计数器在一条信号链中最多触发一次。返回本链应触发的 counter id（含 initial）。
     */
    static List<String> resolveChain(Map<String, MobCounterState> counters,
                                     java.util.Deque<String> initial,
                                     java.util.function.BiPredicate<MobCounterSpec, MobCounterState> allowed) {
        List<String> fired = new ArrayList<>();
        java.util.Set<String> enqueued = new java.util.HashSet<>(initial);
        Deque<String> queue = new ArrayDeque<>(initial);
        while (!queue.isEmpty()) {
            String cid = queue.poll();
            fired.add(cid);
            for (var e : counters.entrySet()) {
                MobCounterState st = e.getValue();
                if (!st.spec().triggeredBy(MobTrigger.COUNTER) || cid.equals(st.spec().id())) continue;
                if (enqueued.contains(st.spec().id())) continue;      // 已入队/已处理 → 跳过（防死循环）
                if (allowed != null && !allowed.test(st.spec(), st)) continue;
                e.setValue(new MobCounterState(st.spec(),
                        advanceCount(st.spec(), MobTrigger.COUNTER, st.current()), st.armed()));
                if (fullAt(st.spec(), MobTrigger.COUNTER, st.current())) {
                    enqueued.add(st.spec().id());
                    queue.add(st.spec().id());
                }
            }
        }
        return fired;
    }

    private static boolean passesCondition(LivingEntity mob, MobCounterSpec spec,
                                           LivingEntity target, LivingEntity source) {
        if (spec.condition() == null) return true;
        AbstractGame game = WaveEngine.gameOfEntity(mob.getUniqueId());
        return game instanceof MaggoteersGame mg
                && spec.condition().evaluate(mg, mob, target, source);
    }

    private MobCounterRegistry() {}
}
