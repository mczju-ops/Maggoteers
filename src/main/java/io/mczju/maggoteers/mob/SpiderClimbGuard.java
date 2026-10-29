package io.mczju.maggoteers.mob;

import io.mczju.maggoteers.MaggoteersPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Spider;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 禁止本局刷出的蜘蛛 / 洞穴蜘蛛爬墙（2026-10-27）。
 *
 * <p><b>为什么不能用 API 做</b>：{@code LivingEntity#isClimbing()} 是只读的，Bukkit/Paper 既没有
 * {@code setClimbing} 也没有 EntityToggleClimbEvent。爬墙是 {@code Spider} 自己的状态位：
 * {@code Spider#tick()} 末尾按 {@code horizontalCollision} 调 {@code setClimbing(boolean)}，
 * {@code onClimbable()}（= 玩家侧 {@code isClimbing()}）读它。故这里<strong>每 tick 把它清回 false</strong>。
 *
 * <p><b>为什么每 tick 清一次就够</b>：实体 tick 内的 {@code travel} 读的是<em>上一 tick 末尾</em>的 flag，
 * 而 flag 是<em>本 tick 末尾</em>才被写回 true ⇒ 无论本任务的 1-tick 心跳排在实体 tick 之前还是之后，
 * {@code travel} 读到的都是被清过的 false，蜘蛛不再上升（同 tick 内同步数据的最终值也是 false，
 * 客户端不会看到爬墙姿势，也不会因此多发元数据包）。
 *
 * <p><b>作用范围（scope proof）</b>：只处理 {@link #mark(LivingEntity)} 登记过的实体
 * （本局刷怪漏斗 {@link MobFactory} ＋ 召唤两条链路），<strong>从不按世界 / 类型扫描</strong>：
 * 世界里别的蜘蛛（大厅、别的插件、其它对局）永远不受影响，{@link #tick()} 只遍历本账本。
 * 实体死亡 / 被卸载 / 换世界后 `isValid()==false`，下一 tick 自动出账，账本不会无限增长。
 *
 * <p><b>降级</b>：NMS 方法名变更这类<em>整体性</em>失败 → 只告警一次，清空账本并停掉任务
 * （= 完全不拦截，蜘蛛照常爬墙），绝不影响开局；单个实体调用失败只跳过那一个。
 */
public final class SpiderClimbGuard {
    /** config 键：{@code true} = 拦截本局刷出的蜘蛛爬墙。缺键按 {@code true}（现场配置只增不改）。 */
    public static final String CONFIG_KEY = "mob_rules.block_spider_wall_climb";

    private static final Logger LOG = Logger.getLogger("Maggoteers");

    /** 已登记的蜘蛛 UUID（本局刷怪漏斗 + 召唤物）。 */
    private static final Set<UUID> MARKED = ConcurrentHashMap.newKeySet();

    /** 实体类 → 反射句柄（正常只有 CraftSpider / CraftCaveSpider 两条）。 */
    private static final Map<Class<?>, Invoker> INVOKERS = new ConcurrentHashMap<>();

    private static BukkitTask task;
    private static volatile boolean degraded;
    private static boolean resolvedOnce;

    private SpiderClimbGuard() {}

    /**
     * 生成后登记（幂等）：只有蜘蛛 / 洞穴蜘蛛会进账本；非蜘蛛、已失效、功能关闭、已降级都直接返回。
     *
     * <p>调用点只有三处，都是「本插件自己把怪刷出来」的那一刻：
     * {@link MobFactory#configureSpawnedLiving}（波次 / 坐骑 / 乘客 / 亡语）、
     * {@code SummonExecutor#spawn}（玩家侧 SUMMON）、
     * {@code EffectService#executeSummonCore}（技能/怪物侧召唤）。
     */
    public static void mark(LivingEntity le) {
        if (le == null || degraded) return;
        if (!(le instanceof Spider)) return;   // CaveSpider extends Spider，天然覆盖
        if (!le.isValid()) return;
        if (!enabled()) return;
        MARKED.add(le.getUniqueId());
    }

    /** 1-tick 心跳：只扫本账本，绝不遍历世界。 */
    public static void tick() {
        tickWith(Bukkit::getEntity, SpiderClimbGuard::clearClimbReflective);
    }

    /**
     * 账本推进（可注入，便于单测）：{@code lookup} 由 UUID 取实体，{@code clearer} 清一次爬墙标记。
     * {@code clearer} 返回 {@code false} 表示实现已不可用（它自己已完成降级）⇒ 本 tick 立即停手。
     */
    static void tickWith(Function<UUID, Entity> lookup, ClimbClearer clearer) {
        if (degraded || MARKED.isEmpty()) return;
        if (!enabled()) {           // 运行中把开关改成 false → 立刻放手（不残留账本）
            MARKED.clear();
            return;
        }
        for (Iterator<UUID> it = MARKED.iterator(); it.hasNext(); ) {
            UUID id = it.next();
            Entity raw;
            try {
                raw = lookup.apply(id);
            } catch (RuntimeException e) {
                raw = null;
            }
            if (!(raw instanceof LivingEntity le) || !le.isValid() || le.isDead()) {
                it.remove();
                continue;
            }
            if (!(le instanceof Spider)) {   // 归属校验：只有蜘蛛会爬墙
                it.remove();
                continue;
            }
            if (!le.isClimbing()) continue;  // 没贴墙就不碰 NMS（也避免无谓的同步数据写入）
            if (!clearer.clear(le)) return;  // 已降级（账本已由 degrade 清空）
        }
    }

    /** 清一次 {@code Spider#setClimbing(false)}；整体不可用 → 降级并返回 false。 */
    static boolean clearClimbReflective(LivingEntity spider) {
        return clearClimbOn(spider);
    }

    /**
     * 反射主体：对 {@code Object} 生效（只要求「有 {@code getHandle()}，句柄上有
     * {@code setClimbing(boolean)}」），便于单测用假 Craft 实体证明 invoke 语义本身正确。
     */
    static boolean clearClimbOn(Object entity) {
        if (entity == null) return true;
        Invoker inv;
        try {
            inv = invokerFor(entity.getClass());
        } catch (ReflectiveOperationException | LinkageError e) {
            degrade("无法解析 NMS Spider#setClimbing，蜘蛛爬墙拦截不可用：" + e, e);
            return false;
        }
        try {
            Object handle = inv.getHandle().invoke(entity);
            if (handle == null) {
                degrade("实体 getHandle() 返回 null，蜘蛛爬墙拦截不可用", null);
                return false;
            }
            inv.setClimbing().invoke(handle, false);
            return true;
        } catch (InvocationTargetException | IllegalAccessException | RuntimeException e) {
            // 单个实体失败（已被移除 / handle 被替换等）：只跳过这一个，不做整体降级
            LOG.log(Level.FINE, "清除爬墙状态失败（已跳过）：" + entity.getClass().getName(), e);
            return true;
        }
    }

    private static Invoker invokerFor(Class<?> entityClass) throws ReflectiveOperationException {
        Invoker cached = INVOKERS.get(entityClass);
        if (cached != null) return cached;
        Method getHandle = entityClass.getMethod("getHandle");
        Method setClimbing = getHandle.getReturnType().getMethod("setClimbing", boolean.class);
        Invoker inv = new Invoker(getHandle, setClimbing);
        INVOKERS.put(entityClass, inv);
        if (!resolvedOnce) {
            resolvedOnce = true;
            LOG.info("蜘蛛爬墙拦截已启用：解析到 "
                    + getHandle.getReturnType().getName() + "#setClimbing(boolean)");
        }
        return inv;
    }

    /** 反射句柄（正常只有两条：CraftSpider / CraftCaveSpider）。 */
    private record Invoker(Method getHandle, Method setClimbing) {}

    /** 清一次爬墙标记：{@code true} = 已处理（或个案已跳过）；{@code false} = 实现不可用 ⇒ 整体降级。 */
    @FunctionalInterface
    interface ClimbClearer {
        boolean clear(LivingEntity spider);
    }

    /** 配置开关（缺键 / 无插件上下文 → 视为开启）。 */
    static boolean enabled() {
        MaggoteersPlugin plugin = MaggoteersPlugin.getInstance();
        if (plugin == null) return true;
        try {
            return plugin.getConfig().getBoolean(CONFIG_KEY, true);
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** 整体降级：清空账本、停掉任务、只告警一次 —— 之后蜘蛛照常爬墙，但绝不报错刷屏。 */
    private static void degrade(String reason, Throwable cause) {
        if (degraded) return;
        degraded = true;
        MARKED.clear();
        cancelTask();
        if (cause == null) LOG.warning(reason);
        else LOG.log(Level.WARNING, reason, cause);
        LOG.warning("蜘蛛爬墙拦截已降级关闭（蜘蛛会照常爬墙）。升级服务端后请复查 "
                + "net.minecraft.world.entity.monster.spider.Spider#setClimbing(boolean)。");
    }

    public static void start(Plugin plugin) {
        stop();
        if (plugin == null) return;
        task = Bukkit.getScheduler().runTaskTimer(plugin, SpiderClimbGuard::tick, 1L, 1L);
    }

    public static void stop() {
        cancelTask();
        MARKED.clear();
    }

    private static void cancelTask() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    // ---- 单测接缝（不碰 Bukkit 实体） ----

    /** 单测：只登记 UUID，便于断言账本行为。 */
    static void noteMarkForTests(UUID id) {
        if (id != null) MARKED.add(id);
    }

    static int markedCount() {
        return MARKED.size();
    }

    static boolean isDegraded() {
        return degraded;
    }

    static void resetForTests() {
        cancelTask();
        MARKED.clear();
        INVOKERS.clear();
        degraded = false;
        resolvedOnce = false;
    }
}
