package io.mczju.maggoteers.mob;

import io.mczju.maggoteers.MaggoteersPlugin;
import io.mczju.maggoteers.config.BossPhaseCfg;
import io.mczju.maggoteers.effect.EffectService;
import io.mczju.maggoteers.effect.Trigger;
import io.mczju.maggoteers.effect.TriggerContext;
import io.mczju.maggoteers.game.MaggoteersGame;
import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Boss 阶段跟踪（2026-10-26，门扉缔造者；N5「阶段原语」）。
 *
 * <p>模型：{@code waves.yml} 的 strategy 上写 {@code phases:}，每个阶段声明门槛
 * （{@code after_sec} 出生后 N 秒 / {@code hp_below} 血量比例）与进入时执行的效果；
 * 技能上写 {@code phases: [2,3,4]} 表示"只在这些阶段可用"。
 *
 * <p>为什么这样做而不是往引擎里塞状态机：阶段是**内容**概念，引擎只需
 * ① 每秒评估门槛 ② 记住当前阶段号 ③ 让技能/伤害门按阶段过滤。
 * 这样就够表达"阶段1无敌、阶段2只吃贴身伤害、阶段3限伤、阶段4给全场负面"，
 * 且不用给每个 Boss 改 Java。
 *
 * <p>{@link #nextPhase(int, List, double, double)} 是纯函数，单测覆盖"按时间""按血线""不重复触发"。
 */
public final class BossPhaseTracker {

    private record State(List<BossPhaseCfg> phases, long spawnTick, int currentPhase) {
    }

    private static final Map<UUID, State> STATE = new ConcurrentHashMap<>();

    private BossPhaseTracker() {
    }

    /** 登记一只 Boss 的阶段表（由 WaveEngine 在带 boss_bar 的怪生成时调用）。 */
    public static void register(UUID uuid, List<BossPhaseCfg> phases, long spawnTick) {
        if (uuid == null || phases == null || phases.isEmpty()) return;
        List<BossPhaseCfg> sorted = new ArrayList<>(phases);
        sorted.sort(Comparator.comparingInt(BossPhaseCfg::id));
        STATE.put(uuid, new State(List.copyOf(sorted), spawnTick, 1));
    }

    /** 当前阶段（1 = 初始；未登记也返回 1）。 */
    public static int phaseOf(UUID uuid) {
        State st = uuid == null ? null : STATE.get(uuid);
        return st == null ? 1 : st.currentPhase();
    }

    public static boolean tracked(UUID uuid) {
        return uuid != null && STATE.containsKey(uuid);
    }

    public static void forget(UUID uuid) {
        if (uuid != null) STATE.remove(uuid);
    }

    public static void clearAll() {
        STATE.clear();
    }

    /**
     * 纯函数：算出"应当进入的新阶段号"（不跨级、只前进一格，避免血量一次掉太多跳过阶段效果）。
     *
     * @param current   当前阶段
     * @param phases    阶段表（按 id 升序）
     * @param hpRatio   当前血量比例（0~1）
     * @param elapsedSec 出生至今秒数
     * @return 新阶段号；无变化返回 {@code current}
     */
    public static int nextPhase(int current, List<BossPhaseCfg> phases, double hpRatio, double elapsedSec) {
        if (phases == null || phases.isEmpty()) return current;
        int next = current;
        for (BossPhaseCfg p : phases) {
            if (p.id() <= current) continue;
            boolean hit = p.byTime() ? elapsedSec >= p.afterSec() : hpRatio <= p.hpBelow();
            if (hit) {
                next = p.id();
                break;                     // 每秒只推进一格：保证每个阶段的效果都会被执行到
            }
        }
        return next;
    }

    /** 每秒评估（由 {@link MobSkillService#tickAll()} 调用）。切换阶段时立刻执行该阶段 effects。 */
    public static void tick(UUID uuid, LivingEntity le, MaggoteersGame game) {
        State st = STATE.get(uuid);
        if (st == null || le == null || le.isDead() || game == null) return;
        double max = le.getMaxHealth();
        double ratio = max <= 0 ? 1.0 : le.getHealth() / max;
        double elapsed = (Bukkit.getCurrentTick() - st.spawnTick()) / 20.0;
        int next = nextPhase(st.currentPhase(), st.phases(), ratio, elapsed);
        if (next == st.currentPhase()) return;
        STATE.put(uuid, new State(st.phases(), st.spawnTick(), next));
        if (next == 2) startBoundaryRing(le);
        BossPhaseCfg cfg = find(st.phases(), next);
        if (cfg == null) return;
        TriggerContext ctx = new TriggerContext(Trigger.ON_COUNTER, null, null);
        for (MobEffectSpec es : cfg.effects()) {
            try {
                EffectService.executeEffectCore(le, game, es.effect(), es.params(), ctx);
            } catch (RuntimeException ex) {
                Bukkit.getLogger().warning("[BossPhase] 阶段 " + next + " 效果执行失败："
                        + es.effect() + " / " + ex.getMessage());
            }
        }
        // 2026-10-26：阶段提示文案走 config.yml messages.wave.phase_switch（{phase} 占位）
        var msg = io.mczju.maggoteers.config.MessageService.component("wave.phase_switch",
                Map.of("phase", String.valueOf(next)));
        game.getPlayers().forEach(pe -> pe.player().sendActionBar(msg));
    }


    /**
     * 阶段2「勿听」的 6 格边界环（2026-10-26）。
     *
     * <p>之前挂在"每秒 tick + tick % 40 == 0"上，实测没画出来（怀疑与 tick 相位/客户端粒子距离有关），
     * 改成**进入阶段2 时启动独立循环任务**：每 10 tick 画一次，Boss 失效/死亡或离开阶段2 自动停。
     */
    private static void startBoundaryRing(LivingEntity le) {
        UUID id = le.getUniqueId();
        MaggoteersPlugin plugin = MaggoteersPlugin.getInstance();
        if (plugin == null) return;
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (le.isDead() || !le.isValid() || phaseOf(id) != 2) return;
            var w = le.getWorld();
            if (w == null) return;
            var base = le.getLocation();
            for (int a = 0; a < 72; a++) {
                double ang = Math.PI * 2 * a / 72.0;
                w.spawnParticle(org.bukkit.Particle.END_ROD,
                        base.clone().add(Math.cos(ang) * 6.0, 0.4, Math.sin(ang) * 6.0), 1, 0, 0, 0, 0);
            }
        }, 0L, 10L);
    }

    private static BossPhaseCfg find(List<BossPhaseCfg> phases, int id) {
        for (BossPhaseCfg p : phases) if (p.id() == id) return p;
        return null;
    }

    /** 测试用：已登记实体数。 */
    static int size() {
        return STATE.size();
    }

    /** 测试用：直接读纯函数结果。 */
    static OptionalInt peekPhase(UUID uuid) {
        State st = STATE.get(uuid);
        return st == null ? OptionalInt.empty() : OptionalInt.of(st.currentPhase());
    }

    }
