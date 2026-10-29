package io.mczju.maggoteers.effect;

import io.mczju.maggoteers.MaggoteersPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 「寒冷」时长落地（2026-10-29 修）。
 *
 * <p>问题：`setFreezeTicks(n)` 只是**一次性赋值**，而 vanilla 在"不在细雪里"时每 tick
 * 把 freezeTicks 减 2 —— 于是 10 秒寒冷实际只看得见一两秒（玩家实测"寒霜之握只冷 1 秒"）。
 * 之前只有寒霜领域看着正常，因为它是 AURA，每秒刷新一次。
 *
 * <p>做法：把"寒冷 N tick"改成**持续续冻**——登记到本类，由一个全局每 2 tick 的任务
 * 把目标的 freezeTicks 顶到 {@code maxFreeze - 1}（139/140，满白霜但**不触发**冻结掉血），
 * 到期自动摘除。这样任意时长都稳定可见，也顺带满足冰镐"冻结暴击"的判定。
 */
public final class FreezeService {

    /** uuid → 剩余 tick（每 2 tick 扣 2）。 */
    private static final Map<UUID, Integer> REMAINING = new HashMap<>();
    private static BukkitTask TASK;

    private FreezeService() {}

    /** onEnable 调用一次（重复调用安全）。 */
    public static void start(MaggoteersPlugin plugin) {
        if (plugin == null || TASK != null) return;
        // 必须每 tick 刷：vanilla 在"不在细雪里"时每 tick 扣 2，隔 tick 刷会掉到满冻线以下，
        // 客户端就看不到白霜/发抖了（2026-10-29 实机复现）。
        TASK = Bukkit.getScheduler().runTaskTimer(plugin, FreezeService::tick, 1L, 1L);
    }

    /** 让目标保持"寒冷" {@code ticks} tick（<=0 忽略；同一目标叠加取更长剩余）。 */
    public static void apply(LivingEntity target, int ticks) {
        if (target == null || target.isDead() || ticks <= 0) return;
        REMAINING.merge(target.getUniqueId(), ticks, Math::max);
        refresh(target);
    }

    /**
     * 把目标顶到**满冻结**（{@code getMaxFreezeTicks()}，默认 140）。
     *
     * <p>⚠️ 必须顶满而不是 {@code max-1}：客户端的白霜/发抖（{@code isFullyFrozen}）只在
     * {@code freezeTicks >= maxFreezeTicks} 时出现，139 在实机里**完全看不出来**（2026-10-29 踩过）。
     * 代价是满冻结会吃到 vanilla 的冻结伤害（每 2 秒 1 点），这正是寒冷该有的代价。
     */
    public static void refresh(LivingEntity target) {
        if (target == null || target.isDead()) return;
        int cap = Math.max(140, target.getMaxFreezeTicks());
        if (target.getFreezeTicks() < cap) target.setFreezeTicks(cap);
    }

    /** 局末/清场：清空登记（不清实体自身的 freezeTicks）。 */
    public static void clear() {
        REMAINING.clear();
    }

    public static int trackedCount() {
        return REMAINING.size();
    }

    private static void tick() {
        if (REMAINING.isEmpty()) return;
        Iterator<Map.Entry<UUID, Integer>> it = REMAINING.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Integer> e = it.next();
            int left = e.getValue() - 1;
            Entity raw = Bukkit.getEntity(e.getKey());
            if (left <= 0 || !(raw instanceof LivingEntity le) || le.isDead() || !le.isValid()) {
                it.remove();
                continue;
            }
            e.setValue(left);
            refresh(le);
        }
    }
}
