package io.mczju.maggoteers.effect;

import io.mczju.maggoteers.game.MaggoteersGame;
import io.mczju.maggoteers.state.PlayerState;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 周期性效果（{@link PlayerEffect#recurringIntervalSec()}）的**内部计时器**。
 * <p>2026-10-26 起不再借道 {@code Trigger.ON_TICK_1S}（已删除）：玩家心跳（
 * {@code EffectService.tickGame}，每秒）调 {@link #pulse}，本类自己按"下一次到期 tick"
 * 推进——不再依赖 `当前秒 % interval` 这种墙钟取模，因此
 * <ul>
 *   <li>间隔严格（首次在 {@code interval} 秒后，此后每 {@code interval} 秒一次）；</li>
 *   <li>心跳抖动/卡顿后不会漏发或重发（积压最多补 {@value #MAX_CATCHUP} 次，然后重新对齐）。</li>
 * </ul>
 * 纯逻辑部分 {@link #pulsesDue(long, long, long)} 与 Bukkit 解耦，便于单测。
 */
public final class RecurringClock {

    /** 单次 pulse 最多补发的次数（服务器长时间卡顿后的追赶上限，防止一次性刷爆）。 */
    static final int MAX_CATCHUP = 4;

    private static final long TICKS_PER_SEC = 20L;

    /** game → (player uuid → (effect id → 下一次到期 tick))。 */
    private static final Map<MaggoteersGame, Map<UUID, Map<String, Long>>> NEXT_DUE =
            new IdentityHashMap<>();

    private RecurringClock() {}

    /**
     * 纯函数：给定当前 tick、下一次到期 tick 与间隔 tick，返回本 tick 应补发的次数。
     * 调用方按返回值 apply，再用 {@link #advanceDue} 更新到期 tick。
     */
    public static int pulsesDue(long nowTick, long nextDueTick, long intervalTicks) {
        if (intervalTicks <= 0 || nextDueTick > nowTick) return 0;
        long elapsed = nowTick - nextDueTick;
        long due = elapsed / intervalTicks + 1;
        return (int) Math.min(due, MAX_CATCHUP);
    }

    /** 纯函数：按已补发次数推进到期 tick；追赶被封顶时重新对齐到"下一次". */
    static long advanceDue(long nowTick, long nextDueTick, long intervalTicks, int pulses) {
        if (intervalTicks <= 0) return nowTick + TICKS_PER_SEC;
        long next = nextDueTick + (long) pulses * intervalTicks;
        if (next <= nowTick) {
            // 追赶封顶：仍有积压 → 直接对齐到下一次，不再补发历史脉冲
            next = nowTick + intervalTicks;
        }
        return next;
    }

    /**
     * 每秒心跳：为本局该玩家的每条 recurring 效果按内部计时生成子效果。
     * 同时清理已不在 effects 列表里的计时条目（换武器/到期/清理后不会残留）。
     */
    public static void pulse(MaggoteersGame game, PlayerState ps, Player p, long nowTick) {
        if (game == null || ps == null || p == null) return;
        Map<String, Long> due = NEXT_DUE
                .computeIfAbsent(game, g -> new HashMap<>())
                .computeIfAbsent(p.getUniqueId(), u -> new HashMap<>());
        Set<String> present = new HashSet<>();
        for (PlayerEffect e : new ArrayList<>(ps.effects())) {
            int intervalSec = e.recurringIntervalSec();
            PlayerEffect spawn = e.recurringSpawn();
            if (intervalSec <= 0 || spawn == null) continue;
            present.add(e.id());
            long intervalTicks = intervalSec * TICKS_PER_SEC;
            long nextDue = due.getOrDefault(e.id(), nowTick + intervalTicks);
            int pulses = pulsesDue(nowTick, nextDue, intervalTicks);
            for (int i = 0; i < pulses; i++) {
                EffectService.apply(p, spawn);
            }
            due.put(e.id(), advanceDue(nowTick, nextDue, intervalTicks, pulses));
        }
        due.keySet().retainAll(present);
        if (due.isEmpty()) {
            Map<UUID, Map<String, Long>> perPlayer = NEXT_DUE.get(game);
            if (perPlayer != null) {
                perPlayer.remove(p.getUniqueId());
                if (perPlayer.isEmpty()) NEXT_DUE.remove(game);
            }
        }
    }

    /** 玩家离开/对局结束时清掉其计时条目。 */
    public static void forget(MaggoteersGame game, UUID uuid) {
        if (game == null) return;
        Map<UUID, Map<String, Long>> perPlayer = NEXT_DUE.get(game);
        if (perPlayer == null) return;
        perPlayer.remove(uuid);
        if (perPlayer.isEmpty()) NEXT_DUE.remove(game);
    }

    /** 对局结束：清掉本局全部计时条目。 */
    public static void forgetGame(MaggoteersGame game) {
        if (game != null) NEXT_DUE.remove(game);
    }

    /** 测试用：清空所有计时状态。 */
    static void clearAll() {
        NEXT_DUE.clear();
    }
}
