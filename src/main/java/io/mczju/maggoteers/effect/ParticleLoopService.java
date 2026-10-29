package io.mczju.maggoteers.effect;

import io.mczju.maggoteers.MaggoteersPlugin;
import org.bukkit.entity.LivingEntity;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 粒子周期任务表（Spec1 PARTICLE_RING / Spec3 fx 周期模式的载体）。
 *  独立于 MobEffectExecutor；取消时机 = detach / 死亡 / 清波 / 对局结束。
 *  ⚠️ 周期 = 效果内部固定缺省值（SHIELD_SHELL→3s），不读技能级 cooldown_sec。 */
public final class ParticleLoopService {
    /** 预设周期表（ticks）：SHIELD_SHELL → 3s；其他预设缺省 3s。调用方在 start 时显式传入 periodTicks。 */
    public static int periodTicksForPreset(String presetName) {
        return 3 * 20;
    }

    private record Task(LivingEntity entity, String key, BukkitRunnable runnable) {}

    private static final Map<String, Task> TASKS = new ConcurrentHashMap<>();

    private static String id(LivingEntity e, String key) {
        return e.getUniqueId() + ":" + key;
    }

    /** 幂等：同 (entity,key) 先 cancel 再注册。periodTicks 显式传入（来自 periodTicksForPreset）。
     *  durationTicks>0 播满自动取消；<=0 播到实体失效/被 cancel 为止。 */
    public static void start(LivingEntity entity, String key, int periodTicks, int durationTicks, Runnable action) {
        if (entity == null || action == null) return;
        cancel(entity, key);
        int period = Math.max(1, periodTicks);
        int max = durationTicks <= 0 ? Integer.MAX_VALUE : Math.max(period, durationTicks);
        BukkitRunnable r = new BukkitRunnable() {
            int tick = 0;
            @Override public void run() {
                if (!entity.isValid() || entity.isDead() || tick >= max) {
                    cancel();
                    TASKS.remove(id(entity, key), new Task(entity, key, this));
                    return;
                }
                if (tick % period == 0) {
                    try { action.run(); } catch (RuntimeException ex) {
                        MaggoteersPlugin.getInstance().getLogger().warning(
                                "ParticleLoopService tick: " + ex.getMessage());
                    }
                }
                tick++;
            }
        };
        TASKS.put(id(entity, key), new Task(entity, key, r));
        r.runTaskTimer(MaggoteersPlugin.getInstance(), 0L, 1L);
    }

    private static void cancel(LivingEntity entity, String key) {
        Task t = TASKS.remove(id(entity, key));
        if (t != null) t.runnable().cancel();
    }

    /** 取消某实体全部周期任务（detach / 死亡 / 清波）。 */
    public static void cancelAll(LivingEntity entity) {
        if (entity == null) return;
        String prefix = entity.getUniqueId() + ":";
        TASKS.entrySet().removeIf(en -> {
            if (!en.getKey().startsWith(prefix)) return false;
            en.getValue().runnable().cancel();
            return true;
        });
    }

    /** 取消全部周期任务（对局结束兜底；世界卸载前调）。 */
    public static void clearAll() {
        TASKS.values().forEach(t -> t.runnable().cancel());
        TASKS.clear();
    }
}
