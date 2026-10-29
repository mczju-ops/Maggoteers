package io.mczju.maggoteers.mob;

import com.github.mczjuops.mczjugamecore.game.AbstractGame;
import io.mczju.maggoteers.MaggoteersPlugin;
import io.mczju.maggoteers.effect.Effect;
import io.mczju.maggoteers.effect.EffectKeys;
import io.mczju.maggoteers.effect.ParticleLoopService;
import io.mczju.maggoteers.game.MaggoteersGame;
import io.mczju.maggoteers.util.GameDifficulty;
import io.mczju.maggoteers.wave.WaveEngine;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 怪物技能运行时：attach（生成）→ 事件分发（攻击/受击/被击杀/tick/计数器）→ detach（死亡/清波）。
 * <p>技能 id 解析失败（mob_skills.yml 缺失）时静默跳过；SPAWN 技能在 attach 立即执行。
 */
public final class MobSkillService {

    private static final Map<UUID, List<MobSkillSpec>> BY_ENTITY = new ConcurrentHashMap<>();
    private static final Map<UUID, Map<String, Long>> LAST_FIRE = new ConcurrentHashMap<>();
    /** 技能分发重入守卫：正在为某实体执行技能的实体集。反伤类技能（vengeance 等 damage_taken→DAMAGE）
     *  自身产出的伤害经 applyIndependent 同步再抛 EntityDamageByEntityEvent，会以同一实体为 victim 回到
     *  fire()——若无守卫则与玩家反伤互相反弹无限递归（StackOverflow）。参照 MagicDamageContext 同 tick 标记范式。 */
    private static final java.util.Set<UUID> IN_FIRE = ConcurrentHashMap.newKeySet();

    private MobSkillService() {}

    public static void start(MaggoteersPlugin plugin) {
        Bukkit.getScheduler().runTaskTimer(plugin, MobSkillService::tickAll, 20L, 20L);
    }

    /**
     * 生成时挂载：技能表 + 计数器 + 隐身 + SPAWN 技能。幂等（重复 attach 覆盖）。
     * <p>1.3.3 难度过滤：带 {@code difficulty} 标记的技能仅在该难度及以上剧本挂载，逐条独立过滤。
     */
    public static void attach(LivingEntity le, List<String> skillIds) {
        if (le == null || skillIds == null || skillIds.isEmpty()) return;
        MaggoteersGame game = gameOf(le);
        GameDifficulty gameDiff = game == null ? null : game.getDifficulty();
        List<MobSkillSpec> specs = new ArrayList<>();
        for (String id : skillIds) {
            MobSkillRegistry.get(id).ifPresent(spec -> {
                if (isAllowed(gameDiff, spec.difficulty())) specs.add(spec);
            });
        }
        if (specs.isEmpty()) return;
        UUID uuid = le.getUniqueId();
        BY_ENTITY.put(uuid, List.copyOf(specs));
        MobCounterRegistry.load(le, specs);
        for (MobSkillSpec spec : specs) {
            if (spec.noAi()) le.setAI(false);            // 2026-10-26：永久关 AI（Boss 站桩）
            if (spec.invisible()) applyInvisible(le);
            if (spec.trigger() == MobTrigger.SPAWN) {
                execute(game, le, spec, null, null);
            }
        }
    }

    /**
     * 纯函数：技能难度过滤（1.3.3）。{@code specDiff==null}（不限难度）→ 恒挂；
     * {@code gameDiff==null}（无法确定当局难度，如 debug spawnmob 宽容路径）→ 宽容挂载；
     * 否则要求 当局难度 ≥ 技能难度（HARD 技能在 HARD/EXPERT 挂，EXPERT 技能仅 EXPERT 挂）。
     */
    static boolean isAllowed(GameDifficulty gameDiff, GameDifficulty specDiff) {
        if (specDiff == null) return true;
        if (gameDiff == null) return true;
        return gameDiff.atLeast(specDiff);
    }

    public static void detach(UUID uuid) {
        Entity raw = Bukkit.getEntity(uuid);
        if (raw instanceof LivingEntity le) {
            ParticleLoopService.cancelAll(le);
        }
        BY_ENTITY.remove(uuid);
        LAST_FIRE.remove(uuid);
        OnceGate.forget(uuid);   // D1：阶段切换记录随实体清理
        MobCounterRegistry.unload(uuid);
        MobTempAttributeService.clearAll(uuid);
        MobPermanentAttributeService.clearAll(uuid);
    }

    public static List<MobSkillSpec> skillsOf(UUID uuid) {
        List<MobSkillSpec> s = BY_ENTITY.get(uuid);
        return s == null ? List.of() : s;
    }

    /**
     * 该实体是否有 KILLED 自我复活技能（trigger==KILLED + HEAL 自疗 amount>0）。
     * 调用方（MobSkillListener.onKilled）据此 cancel 死亡事件，随后 fire 执行 setHealth 复活。
     */
    public static boolean wantsRevive(LivingEntity le) {
        if (le == null) return false;
        List<MobSkillSpec> specs = BY_ENTITY.get(le.getUniqueId());
        if (specs == null || specs.isEmpty()) return false;
        for (MobSkillSpec spec : specs) {
            if (spec.trigger() != MobTrigger.KILLED) continue;
            for (MobEffectSpec es : spec.effects()) {
                if (es.effect() != Effect.HEAL) continue;
                String t = es.params().get(EffectKeys.TARGETS);
                if (t != null && !"self".equalsIgnoreCase(t.trim())) continue;   // 只认 self/null
                Double amount = es.params().get(EffectKeys.AMOUNT);
                if (amount != null && amount > 0) return true;
            }
        }
        return false;
    }

    /** 事件分发：先推进计数器（计满 id 触发对应 COUNTER 技能），再匹配普通 trigger。
     *  <p>⚠️ 重入守卫：本实体技能分发已在栈上时（反伤链：vengeance 造成伤害 → 该伤害又回到本实体 → 再次分发）
     *  直接返回——否则与玩家反伤互相反弹会无限递归。只拦同实体<b>嵌套</b>分发；顶层顺序分发不受影响。 */
    public static void fire(MaggoteersGame game, LivingEntity mob, MobTrigger t,
                            LivingEntity target, LivingEntity source) {
        if (mob == null) return;
        UUID uuid = mob.getUniqueId();
        if (!IN_FIRE.add(uuid)) return;   // 反伤/技能自产伤害同步重入 → 不再次分发
        try {
            fireDispatch(game, mob, uuid, t, target, source);
        } finally {
            IN_FIRE.remove(uuid);
        }
    }

    /** fire() 守卫体内的实际分发（重入守卫保证同一实体不会同时执行两份技能）。 */
    private static void fireDispatch(MaggoteersGame game, LivingEntity mob, UUID uuid, MobTrigger t,
                                     LivingEntity target, LivingEntity source) {
        List<MobSkillSpec> specs = BY_ENTITY.get(uuid);
        if (specs == null || specs.isEmpty()) return;
        long now = System.currentTimeMillis();

        MobCounterRegistry.resetMatching(mob, t);
        for (String cid : MobCounterRegistry.signal(mob, t, target, source)) {
            MobCounterRegistry.resetMatching(mob, MobTrigger.COUNTER);
            fireCounterSkills(specs, game, mob, cid, target, source);
        }
        if (t == MobTrigger.COUNTER) return;

        int phase = BossPhaseTracker.phaseOf(uuid);
        for (MobSkillSpec spec : specs) {
            if (spec.trigger() != t || spec.trigger() == MobTrigger.SPAWN) continue;
            if (!spec.phaseAllowed(phase)) continue;
            if (spec.condition() != null && !spec.condition().evaluate(game, mob, target, source)) continue;
            // cooldown_sec 对 attack/damage_taken/killed 同样生效（原实现只对 tick 生效，冷却形同虚设）
            if (!cooldownElapsed(uuid, spec.id(), now, spec.cooldownSec())) continue;
            // D1：once 技能（血线阶段）占用一次机会；占用失败直接跳过
            if (spec.once() && !OnceGate.tryConsume(uuid, spec.id())) continue;
            // ⚠️ 冷却先于 execute 标记：技能自产伤害（反伤/爆炸）若在 execute 内同步重入或紧接重打本实体，
            // 重入帧必须能立即看到已消耗的冷却；写在 execute 之后 = 递归/连环时冷却恒为 0。
            if (spec.cooldownSec() > 0) markFired(uuid, spec.id(), now);
            // chance：冷却之后掷（对齐 InfernalMobs——掷空也吃冷却），默认 1.0 恒中
            if (!rollChance(spec.chance())) continue;
            execute(game, mob, spec, target, source);
        }
    }

    private static void fireCounterSkills(List<MobSkillSpec> specs, MaggoteersGame game, LivingEntity mob,
                                          String counterId, LivingEntity target, LivingEntity source) {
        for (MobSkillSpec spec : specs) {
            if (spec.trigger() == MobTrigger.COUNTER && spec.counter() != null
                    && spec.counter().id().equals(counterId)) {
                execute(game, mob, spec, target, source);
            }
        }
    }

    private static void execute(MaggoteersGame game, LivingEntity mob, MobSkillSpec spec,
                                LivingEntity target, LivingEntity source) {
        // 2026-10-26：给技能一个"施法动作"（手部摆动）+ 原地粒子，避免"没动作、没预警"
        if (!spec.effects().isEmpty() && spec.trigger() != MobTrigger.SPAWN) {
            try {
                mob.swingMainHand();
                var loc = mob.getLocation();
                var w = mob.getWorld();
                // 环状法术粒子（半径 1.6，眼高附近）+ 头顶闪光：明确"正在施法"的视觉提示
                for (int a = 0; a < 16; a++) {
                    double ang = Math.PI * 2 * a / 16.0;
                    w.spawnParticle(org.bukkit.Particle.WITCH,
                            loc.clone().add(Math.cos(ang) * 1.6, 1.4, Math.sin(ang) * 1.6), 1, 0, 0, 0, 0);
                }
                w.spawnParticle(org.bukkit.Particle.END_ROD, loc.clone().add(0, 2.6, 0), 6, 0.15, 0.15, 0.15, 0.02);
            } catch (RuntimeException ignored) {
                // 实体已失效等，忽略
            }
        }
        // 2026-10-26：MobEffectExecutor 已删除（其源码在清理时丢失、只剩 target/ 里的旧 class，
        // 导致"增量构建能过、clean 构建炸"）。统一走 EffectService.executeEffectCore，
        // 与 {EffectService} 的空实现白名单（MobEffectSupport）同源。
        io.mczju.maggoteers.effect.TriggerContext ctx =
                new io.mczju.maggoteers.effect.TriggerContext(
                        triggerOf(spec.trigger()), target, source);
        for (MobEffectSpec es : spec.effects()) {
            io.mczju.maggoteers.effect.EffectService.executeEffectCore(mob, game, es.effect(), es.params(), ctx);
        }
    }

    /** MobTrigger → 玩家侧 Trigger（仅用于给效果传上下文；mob 侧语义由 MobTrigger 决定）。 */
    private static io.mczju.maggoteers.effect.Trigger triggerOf(MobTrigger t) {
        if (t == null) return null;
        return switch (t) {
            case ATTACK -> io.mczju.maggoteers.effect.Trigger.ON_DAMAGE_DEALT;
            case DAMAGE_TAKEN -> io.mczju.maggoteers.effect.Trigger.ON_DAMAGE_TAKEN;
            case KILLED -> io.mczju.maggoteers.effect.Trigger.ON_KILL;
            case SPAWN -> io.mczju.maggoteers.effect.Trigger.ON_ACT_ENTER;
            case TICK, COUNTER -> io.mczju.maggoteers.effect.Trigger.ON_COUNTER;
        };
    }

    /** 每秒心跳：TICK 光环技能按 cooldown_sec 触发；失效实体 detach。 */
    private static void tickAll() {
        for (var entry : BY_ENTITY.entrySet()) {
            UUID uuid = entry.getKey();
            Entity raw = Bukkit.getEntity(uuid);
            if (!(raw instanceof LivingEntity le) || le.isDead() || !le.isValid()) {
                detach(uuid);
                continue;
            }
            MaggoteersGame game = gameOf(le);
            if (game == null) continue;
            BossPhaseTracker.tick(uuid, le, game);       // 2026-10-26：阶段门槛评估（每秒）
            long now = System.currentTimeMillis();
            List<MobSkillSpec> specs = entry.getValue();
            MobCounterRegistry.resetMatching(le, MobTrigger.TICK);
            for (String cid : MobCounterRegistry.signal(le, MobTrigger.TICK, null, null)) {
                MobCounterRegistry.resetMatching(le, MobTrigger.COUNTER);
                fireCounterSkills(specs, game, le, cid, null, null);
            }
            int phase = BossPhaseTracker.phaseOf(uuid);
            for (MobSkillSpec spec : specs) {
                if (spec.trigger() != MobTrigger.TICK) continue;
                if (!spec.phaseAllowed(phase)) continue;
                if (!cooldownElapsed(uuid, spec.id(), now, spec.cooldownSec())) continue;
                if (spec.condition() != null && !spec.condition().evaluate(game, le, null, null)) continue;
                // D1：once（血线阶段）——占用成功才继续；失败当作已切过阶段
                if (spec.once() && !OnceGate.tryConsume(uuid, spec.id())) continue;
                // chance 与 ATTACK/受击同口径：先吃冷却，再掷概率
                markFired(uuid, spec.id(), now);
                if (!rollChance(spec.chance())) continue;
                execute(game, le, spec, null, null);
            }
        }
    }

    private static boolean cooldownElapsed(UUID uuid, String skillId, long now, int cooldownSec) {
        if (cooldownSec <= 0) return true;
        Map<String, Long> bySkill = LAST_FIRE.get(uuid);
        Long last = bySkill == null ? null : bySkill.get(skillId);
        return last == null || now - last >= cooldownSec * 1000L;
    }

    /** 技能触发概率（{@code chance:} 0~1；默认 1.0 恒中）。纯函数，便于单测。 */
    static boolean rollChance(double chance) {
        return chance >= 1.0 || Math.random() < chance;
    }

    private static void markFired(UUID uuid, String skillId, long now) {
        LAST_FIRE.computeIfAbsent(uuid, u -> new HashMap<>()).put(skillId, now);
    }

    private static void applyInvisible(LivingEntity le) {
        le.setInvisible(true);
        EntityEquipment eq = le.getEquipment();
        if (eq == null) return;
        ItemStack bottle = new ItemStack(Material.GLASS_BOTTLE);
        eq.setHelmet(bottle);
        eq.setHelmetDropChance(0f);
    }

    private static MaggoteersGame gameOf(LivingEntity le) {
        AbstractGame g = WaveEngine.gameOfEntity(le.getUniqueId());
        return g instanceof MaggoteersGame mg ? mg : null;
    }
}
