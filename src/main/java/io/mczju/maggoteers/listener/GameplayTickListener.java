package io.mczju.maggoteers.listener;

import com.github.mczjuops.mczjugamecore.game.AbstractGame;
import io.mczju.maggoteers.MaggoteersPlugin;
import io.mczju.maggoteers.achievement.AchievementService;
import io.mczju.maggoteers.effect.AuraService;
import io.mczju.maggoteers.effect.EffectService;
import io.mczju.maggoteers.game.MaggoteersGame;
import io.mczju.maggoteers.item.ItemKind;
import io.mczju.maggoteers.item.ItemService;
import io.mczju.maggoteers.state.PlayerStateManager;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 全局游戏玩法维护心跳：每秒对所有活跃对局中存活的玩家执行：
 * <ul>
 *   <li>{@link io.mczju.maggoteers.effect.AuraService#refresh} — 团队/对敌光环派生与 carrier 粒子</li>
 *   <li>刷新常驻药水（EffectService.refreshPermanentPotions）—— 修复 QA #1 药水过期问题</li>
 *   <li>维持满腹—— 修复 QA #3 饥饿消耗问题（NATURAL_REGENERATION=false 阻止回血）</li>
 * </ul>
 * <p>全局单任务（非 per-game），在 onEnable 启动、onDisable 停止。
 */
public final class GameplayTickListener implements Listener {

    private static BukkitTask task;

    /**
     * DYNAMIC_ATTRIBUTE（真实属性）指纹：{金币数, 复活次数, 击杀, 受击, 死亡, 清波, 过层}。
     * 任一变化才 resyncDerived 重算动态值，避免每秒对全员 strip+reapply。
     * 虚拟属性（CDR/魔攻）由 PlayerCombatStats 使用时现算，不走这里。
     */
    private static final Map<UUID, long[]> DYNAMIC_FINGERPRINT = new HashMap<>();

    /** 指纹槽位：与 {@link #fingerprint(Player, io.mczju.maggoteers.state.PlayerState)} 一一对应。 */
    private static final int FP_SLOTS = 7;

    private static long[] fingerprint(Player p, io.mczju.maggoteers.state.PlayerState ps) {
        return new long[]{
                ItemService.countKind(p, ItemKind.CURRENCY_NORMAL),
                ps.getReviveCount(),
                ps.killCount(),
                ps.hitsTaken(),
                ps.deaths(),
                ps.wavesCleared(),
                ps.actsCleared()
        };
    }

    private static boolean sameFingerprint(long[] a, long[] b) {
        if (a == null || b == null || a.length != FP_SLOTS || b.length != FP_SLOTS) return false;
        for (int i = 0; i < FP_SLOTS; i++) {
            if (a[i] != b[i]) return false;
        }
        return true;
    }

    /**
     * 启动全局心跳（幂等）。
     * <p>本心跳只做光环/常驻药水/满腹维护，枚举源 {@code gamesWithState()}；
     * 与 {@link io.mczju.maggoteers.effect.EffectListener#startTick()}（tick 计数器 + recurring 心跳）
     * 职责不同，有意分开，勿合并——合并会改变两者各自的遍历与时机语义。
     */
    public static void start() {
        if (task != null) return;
        task = org.bukkit.Bukkit.getScheduler().runTaskTimer(MaggoteersPlugin.getInstance(), () -> {
            for (AbstractGame g : PlayerStateManager.gamesWithState()) {
                if (!(g instanceof MaggoteersGame mg)) continue;
                AuraService.refresh(mg);
                for (UUID uuid : PlayerStateManager.uuidsInGame(mg)) {
                    Player p = org.bukkit.Bukkit.getPlayer(uuid);
                    if (p == null) continue;
                    var ps = PlayerStateManager.get(mg, uuid);
                    if (ps == null || !ps.isAlive()) continue;
                    // QA #1: 刷新常驻药水（每秒重施加 30s，永不过期）
                    EffectService.refreshPermanentPotions(p);
                    // 成就：状态效果数量这类"持续态"条件每秒看一眼（"你的脑袋怎么尖尖的"）
                    AchievementService.onSecondTick(p);
                    // QA #3: 维持满腹（饥饿不消耗，NATURAL_REGENERATION=false 已阻止回血）
                    p.setFoodLevel(20);
                    p.setSaturation(5f);
                    p.setExhaustion(0f);
                    // 1.3.2：真实属性 DYNAMIC_ATTRIBUTE（如诸王冠冕 +150 攻/+1 触及）按本局计数刷新
                    if (EffectService.hasRealDynamicAttribute(ps.effects())) {
                        long[] next = fingerprint(p, ps);
                        long[] prev = DYNAMIC_FINGERPRINT.get(uuid);
                        if (!sameFingerprint(prev, next)) {
                            EffectService.resyncDerived(p, ps);
                            DYNAMIC_FINGERPRINT.put(uuid, next);
                        }
                    } else {
                        DYNAMIC_FINGERPRINT.remove(uuid);
                    }
                }
            }
        }, 20L, 20L);
    }

    /** 停止全局心跳。 */
    public static void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public GameplayTickListener() {}
}
