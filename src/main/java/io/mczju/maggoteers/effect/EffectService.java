package io.mczju.maggoteers.effect;

import io.mczju.maggoteers.MaggoteersPlugin;
import io.mczju.maggoteers.game.AllyTargeting;
import io.mczju.maggoteers.game.MaggoteersGame;
import io.mczju.maggoteers.item.fx.MagicFxBuilder;
import io.mczju.maggoteers.item.fx.MagicFxConfig;
import io.mczju.maggoteers.item.fx.MagicFxPreset;
import io.mczju.maggoteers.item.fx.MagicFxPresets;
import io.mczju.maggoteers.item.ItemService;
import io.mczju.maggoteers.item.fx.MagicFxService;
import io.mczju.maggoteers.item.fx.MagicUseFx;
import io.mczju.maggoteers.mob.MobCounterRegistry;
import io.mczju.maggoteers.mob.MobPermanentAttributeService;
import io.mczju.maggoteers.mob.MobProjectileService;
import io.mczju.maggoteers.mob.MobTempAttributeService;
import io.mczju.maggoteers.mob.TeleportSafety;
import io.mczju.maggoteers.plan.ActPlan;
import io.mczju.maggoteers.state.PlayerState;
import io.mczju.maggoteers.state.PlayerStateManager;
import io.mczju.maggoteers.util.GameRegistries;
import io.mczju.maggoteers.util.ParticleEffects;
import io.mczju.maggoteers.wave.Vec3;
import io.mczju.maggoteers.wave.WaveEngine;
import io.mczju.maggoteers.wave.WaveScheduler;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import org.bukkit.Bukkit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.logging.Logger;

/**
 * 效果引擎服务（§10）。PlayerState 为真相源，Bukkit 属性/药水为派生视图。
 * <p>D5：常驻 ADD_ATTRIBUTE 每层 AttributeModifier 用唯一 NamespacedKey（id + level + 序号），
 * 1.21 起同 key 重复添加会抛异常。
 */
public final class EffectService {
    private static final Logger LOG = Logger.getLogger("Maggoteers");
    private static final AtomicLong KEY_SEQ = new AtomicLong();   // 保证 key 唯一
    /** 玩家常驻 ADD_POTION 派生药水时长（ticks）。20*99999 ≈ 27.7h：够整局 + 败局观战；
     *  由 refreshPermanentPotions 每秒重刷，客户端稳定显示 27:44:39（非 ∞）。
     *  ⚠️ 覆盖 Spec1 迁移注① 玩家分支（原 -1 无限）。配置侧 duration_ticks<=0=常驻 语义不变。 */
    /**
     * 2026-10-28（安全）：原 20*99999（≈27.8h）会跟着 playerdata 下盘，`kill -9`
     * 后玩家上线仍带着速度/抗性/夜视。局内每秒都会
     * {@code refreshPermanentPotions} 续时，故取 20 分钟足够，意外断开后也不会永久残留。
     */
    public static final int PERMANENT_POTION_TICKS = 20 * 60 * 20;

    /** 怪物常驻 ADD_POTION：原版无限（NMS isInfiniteDuration）。独立常量，不随玩家分支变化。 */
    public static final int MOB_PERMANENT_POTION_TICKS = -1;

    /** 旁观者夜视：原版无限。旁观者收不到 resync 重施加（MaggoteersDeathStrategy:134 注释），必须 -1。 */
    public static final int SPECTATOR_NIGHT_VISION_TICKS = -1;

    /** 应用一条效果：merge 进 PlayerState；常驻型立即施加派生视图。 */
    public static void apply(Player p, PlayerEffect incoming) {
        apply(p, incoming, currentGame(p));
    }

    public static void apply(Player p, PlayerEffect incoming, MaggoteersGame game) {
        if (game == null) {
            LOG.warning("EffectService.apply: game null " + p.getName());
            return;
        }
        PlayerState st = PlayerStateManager.get(game, p.getUniqueId());
        if (st == null) {
            LOG.warning("EffectService.apply: 无 PlayerState " + p.getName());
            return;
        }
        var merged = EffectStacker.merge(st.effects(), incoming);
        st.effects().clear();
        st.effects().addAll(merged);
        PlayerEffect mergedPe = st.effects().stream()
                .filter(e -> e.id().equals(incoming.id())).findFirst().orElse(null);
        if (mergedPe != null && mergedPe.effect() == Effect.BOUND_EQUIP) {
            BoundEquipService.sync(p, mergedPe);
        } else if (incoming.effect() == Effect.AURA && incoming.fireTrigger() == null) {
            AuraService.refresh(game);
        } else if (incoming.isPermanent() && incoming.effect() == Effect.SWAP_ATTACK_MAGIC) {
            // 认知失调：全量重算——既有物攻/魔攻效果在互换规则下重新派生（物攻→魔攻停写 Bukkit，魔攻→物攻开始写）
            resyncDerived(p, st);
        } else if (incoming.isPermanent() && incoming.effect() == Effect.ADD_ATTRIBUTE) {
            // Full resync avoids REPLACE/REFRESH leaving orphan AttributeModifiers (unique KEY_SEQ).
            resyncDerived(p, st);
        } else if (incoming.isPermanent() && incoming.effect() != Effect.AURA) {
            applyDerived(p, st.effects(), incoming);
        }
        if (mergedPe != null) {
            CounterSpec spec = mergedPe.params().get(EffectKeys.COUNTER_SPEC);
            if (spec != null) CounterService.register(st, spec);
        }
    }

    /** 复活/开局后重施加（幂等）：held 武器被动 + 派生视图。不补发背包护符/绑定装备。 */
    public static void resync(Player p) {
        MaggoteersGame game = currentGame(p);
        PlayerState st = PlayerStateManager.get(game, p.getUniqueId());
        if (st == null) return;
        if (game != null) {
            // sync may early-return when main-hand unchanged; still must strip/reapply derived views
            WeaponHeldService.sync(p, game);
        }
        resyncDerived(p, st);
    }

    private static final ThreadLocal<Boolean> BULK_RESYNC = ThreadLocal.withInitial(() -> false);

    /** Strip + reapply permanent attribute/potion derived views (no collectible sync)。血量基线在 strip 前采集（D11）。 */
    public static void resyncDerived(Player p, PlayerState st) {
        if (p == null || st == null) return;
        var maxInst = p.getAttribute(Attribute.MAX_HEALTH);
        resyncDerived(p, st, p.getHealth(), maxInst != null ? maxInst.getValue() : 20.0);
    }

    /** Strip + reapply，血量基线由调用方传入（WeaponHeldService.sync 需在首次 strip 前采集，D11）。 */
    static void resyncDerived(Player p, PlayerState st, double healthBefore, double maxBefore) {
        if (p == null || st == null) return;
        var maxInst = p.getAttribute(Attribute.MAX_HEALTH);   // 保留：finalize 时测 maxAfter

        BULK_RESYNC.set(true);
        try {
            stripDerived(p, st.effects());
            for (PlayerEffect e : new ArrayList<>(st.effects())) {
                if (e.isPermanent() && e.effect() != Effect.AURA) {
                    try {
                        applyDerived(p, st.effects(), e);
                    } catch (RuntimeException ex) {
                        LOG.warning("resyncDerived apply " + e.id() + ": " + ex.getMessage());
                    }
                }
            }
        } finally {
            BULK_RESYNC.set(false);
            double maxAfter = maxInst != null ? maxInst.getValue() : 20.0;
            try {
                p.setHealth(finalizeHealthAfterResync(healthBefore, maxBefore, maxAfter));
            } catch (IllegalArgumentException ex) {
                LOG.warning("resyncDerived setHealth: " + ex.getMessage());
            }
        }
        // stripDerived 会按常驻 ADD_POTION 类型清药水，须立刻重刷 AURA 派生，否则光环药水会空窗甚至被低 amp 常驻盖住
        MaggoteersGame game = currentGame(p);
        if (game != null) {
            AuraService.refresh(game);
        }
    }

    /**
     * strip/reapply 后确定当前血量：净增上限 → 等量加当前血；否则保留比例（切换武器/重算属性）。
     */
    static double finalizeHealthAfterResync(double healthBefore, double maxBefore, double maxAfter) {
        double maxDelta = maxAfter - maxBefore;
        if (maxDelta > 0) {
            return Math.min(maxAfter, healthBefore + maxDelta);
        }
        return preserveHealthAcrossResync(healthBefore, maxBefore, maxAfter);
    }

    /** MAX_HEALTH 实际升高时，给玩家补上同等当前血量（上限与血量同步增长）。 */
    static void bumpHealthForMaxGain(Player p, double maxBefore, double maxAfter) {
        if (p == null || maxAfter <= maxBefore) return;
        p.setHealth(Math.min(maxAfter, p.getHealth() + (maxAfter - maxBefore)));
    }

    /**
     * strip/reapply 属性时保留血量比例；避免 MC 在移除 MAX_HEALTH 修饰符时把当前血 clamp 到 20，
     * 导致刚用补给/heal 恢复的血量在切换手持物后被“打回”基础上限。
     */
    static double preserveHealthAcrossResync(double healthBefore, double maxBefore, double maxAfter) {
        if (maxAfter <= 0) return Math.max(0, healthBefore);
        if (maxBefore <= 0) return Math.min(maxAfter, healthBefore);
        return Math.min(maxAfter, healthBefore * (maxAfter / maxBefore));
    }

    /** 对局结束：剥除派生视图 + 清 PlayerState 效果 + 剥离绑定装备/护符视图。 */
    public static void removeAll(Player p) {
        removeAllFor(p, currentGame(p));
    }

    /**
     * 按显式对局清理单个玩家的派生效果。中途退出玩家已不在 MGC game map（{@code currentGame} 查不到），
     * 必须把对局对象显式传进来才能拿到 PlayerState 并剥除 MAX_HEALTH 等属性加成/常驻药水。
     * <p>离开对局时额外清掉全部活跃药水（含光环/临时技能残留）；局内复活勿调本方法。
     */
    public static void removeAllFor(Player p, MaggoteersGame game) {
        PlayerState st = PlayerStateManager.get(game, p.getUniqueId());
        if (st != null) {
            stripDerived(p, st.effects());
            st.clearEffects();
            CounterService.clearAll(st);
        }
        if (game != null) RecurringClock.forget(game, p.getUniqueId());
        // 光环药水、临时 use_ability 药水、夜视等均不保证在 effects 列表里可逐条剥除
        clearAllActivePotions(p);
        if (game != null) {
            AuraService.stripAllFromReceiver(game, p.getUniqueId());
            AuraService.stripAllFromSource(game, p.getUniqueId());
            io.mczju.maggoteers.reward.CollectibleService.resync(p, game);
            BoundEquipService.resync(p, game);
        }
    }

    /** 清除玩家身上全部药水效果（回大厅 / leave）。 */
    public static void clearAllActivePotions(Player p) {
        if (p == null) return;
        for (PotionEffect pe : new ArrayList<>(p.getActivePotionEffects())) {
            p.removePotionEffect(pe.getType());
        }
    }

    /** 纯逻辑（可测）：收集 effects 中应剥除的常驻 ADD_POTION 药水类型。 */
    static java.util.List<org.bukkit.potion.PotionEffectType> permanentPotionTypesToStrip(
            java.util.List<PlayerEffect> effects) {
        java.util.List<org.bukkit.potion.PotionEffectType> out = new ArrayList<>();
        for (PlayerEffect e : effects) {
            if (e.isPermanent() && e.effect() == Effect.ADD_POTION) {
                org.bukkit.potion.PotionEffectType type = e.params().get(EffectKeys.POTION);
                if (type != null) out.add(type);
            }
        }
        return out;
    }

    /**
     * 剥除本插件施加的派生视图：插件命名空间下的 AttributeModifier + 常驻 ADD_POTION 药水。
     * ⚠️ 契约：若调用方接下来会从 effects 列表删除任何常驻 ADD_POTION 条目（如换武器 removeHeld / 清 ability），
     *    必须【先】调用本方法（基于变更前完整列表清药水），【再】删条目；否则被删条目对应药水无人清理而残留。
     *    resyncDerived 内部会在 strip 后 reapply 仍在列表的条目，天然自愈；无需重复调用。
     */
    static void stripDerived(Player p, java.util.List<PlayerEffect> effects) {
        DerivedViewAttributes.stripPluginModifiers(
                p, MaggoteersPlugin.getInstance().getName().toLowerCase(), null);
        for (org.bukkit.potion.PotionEffectType type : permanentPotionTypesToStrip(effects)) {
            p.removePotionEffect(type);
        }
    }

    private static final Set<String> FIRING = new HashSet<>();

    /**
     * 对局内某 trigger 触发：对每个冒险模式玩家先分发 fireTrigger==fired，再扫到期。
     * <p>不含周期性效果计时——那由每秒心跳 {@link #tickGame}（→ {@link RecurringClock}）负责。
     * @param game  当前对局
     * @param fired 触发的 trigger
     */
    public static void fireTrigger(MaggoteersGame game, Trigger fired) {
        if (game == null) return;
        String key = System.identityHashCode(game) + ":" + fired.name();
        if (!FIRING.add(key)) return;   // 同 (game,trigger) 正在分发 → 防递归
        try {
            for (var pe : game.getPlayers()) {
                Player p = pe.player();
                try {
                    var ps = io.mczju.maggoteers.state.PlayerStateManager.get(game, p.getUniqueId());
                    if (ps == null || !ps.isAlive()) continue;
                    CounterCountTrigger ct = counterInput(fired);
                    if (ct != null) {
                        fireCounter(game, ps, p, ct, null, TriggerContext.empty());
                    }
                    processTriggerEffects(ps, p, game, fired, TriggerContext.empty());
                } catch (RuntimeException ex) {
                    LOG.warning("fireTrigger " + fired + " player=" + p.getName() + ": " + ex.getMessage());
                }
            }
        } finally {
            FIRING.remove(key);
        }
    }

    /**
     * 玩家每秒心跳（取代已删除的 {@code Trigger.ON_TICK_1S}）：
     * <ol>
     *   <li>推进玩家侧 {@code counter{count: tick}} 计数器（{@link CounterCountTrigger#TICK}），
     *       计满走 {@code ON_COUNTER}——这是唯一的"计时"内容通道；</li>
     *   <li>推进 {@code recurring} 周期性效果（内部计时，见 {@link RecurringClock}）。</li>
     * </ol>
     * 由 {@code EffectListener.startTick} 每秒调用（多对局共享一个心跳任务）。
     */
    public static void tickGame(MaggoteersGame game) {
        if (game == null) return;
        long nowTick = Bukkit.getCurrentTick();
        String key = System.identityHashCode(game) + ":TICK";
        if (!FIRING.add(key)) return;   // 防递归（计数器效果里若再触发 tick 不会重入）
        try {
            for (var pe : game.getPlayers()) {
                Player p = pe.player();
                try {
                    var ps = io.mczju.maggoteers.state.PlayerStateManager.get(game, p.getUniqueId());
                    if (ps == null || !ps.isAlive()) continue;
                    fireCounter(game, ps, p, CounterCountTrigger.TICK, null, TriggerContext.empty());
                    RecurringClock.pulse(game, ps, p, nowTick);
                } catch (RuntimeException ex) {
                    LOG.warning("tickGame player=" + p.getName() + ": " + ex.getMessage());
                }
            }
        } finally {
            FIRING.remove(key);
        }
    }

    /** 单玩家触发（战斗/交互：只触发该玩家；不做 recurring 计时）。 */
    public static void fireTriggerPlayer(MaggoteersGame game, Player p, Trigger fired) {
        fireTriggerPlayer(game, p, fired, TriggerContext.empty());
    }

    public static void fireTriggerPlayer(MaggoteersGame game, Player p, Trigger fired, TriggerContext ctx) {
        if (game == null || p == null) return;
        try {
            var ps = io.mczju.maggoteers.state.PlayerStateManager.get(game, p.getUniqueId());
            if (ps == null) return;
            TriggerContext raw = ctx != null ? ctx : TriggerContext.empty();
            TriggerContext useCtx = raw.fired() != null ? raw : raw.withFired(fired);
            if (fired != Trigger.ON_DEATH && !ps.isAlive()) {
                return;
            }
            CounterCountTrigger ct = counterInput(fired);
            if (ct != null) {
                fireCounter(game, ps, p, ct, null, useCtx);
            }
            processTriggerEffects(ps, p, game, fired, useCtx);
        } catch (RuntimeException ex) {
            LOG.warning("fireTriggerPlayer " + fired + " player=" + p.getName() + ": " + ex.getMessage());
        }
    }

    /** 外部 trigger → 计数器输入信号（无对应输入返回 null）。tick 输入由 {@link #tickGame} 直接推进。 */
    private static CounterCountTrigger counterInput(Trigger t) {
        return switch (t) {
            case ON_WAVE_CLEAR -> CounterCountTrigger.WAVE_CLEAR;
            case ON_KILL -> CounterCountTrigger.KILL;
            case ON_DAMAGE_DEALT -> CounterCountTrigger.ATTACK;
            case ON_DAMAGE_TAKEN -> CounterCountTrigger.DAMAGE_TAKEN;
            default -> null;
        };
    }

    private static Trigger toTrigger(CounterCountTrigger t) {
        return switch (t) {
            case WAVE_CLEAR -> Trigger.ON_WAVE_CLEAR;
            case KILL -> Trigger.ON_KILL;
            case ATTACK -> Trigger.ON_DAMAGE_DEALT;
            case DAMAGE_TAKEN -> Trigger.ON_DAMAGE_TAKEN;
            case TICK, ON_COUNTER -> null;   // resetOn 无法引用 tick/中继（不存在的 Trigger 值）
        };
    }

    /**
     * 推进计数器（含 resetOn 清零 + condition 门 + ON_COUNTER 串联），
     * 对每个计满 id dispatch fireTrigger==ON_COUNTER && params.counter.id==id 的被动。
     */
    public static void fireCounter(MaggoteersGame game, PlayerState ps, Player p,
                                   CounterCountTrigger t, String sourceId, TriggerContext ctx) {
        if (ps == null) {
            return;
        }
        // 蓄力那一刀（票券被消耗的近战）不计入下一轮蓄力进度
        if (t == CounterCountTrigger.ATTACK && ps.consumeSkipAttackCounterOnce()) {
            return;
        }
        CounterService.resetMatching(ps, toTrigger(t));
        List<String> firedIds = CounterService.advance(ps, t, sourceId,
                spec -> spec.condition() == null || spec.condition().evaluate(game, p));
        for (String id : firedIds) {
            TriggerContext c = (ctx != null ? ctx : TriggerContext.empty())
                    .withFired(Trigger.ON_COUNTER).withCounter(id);
            dispatchCounterEffects(ps, p, game, id, c);
        }
    }

    private static void dispatchCounterEffects(PlayerState ps, Player p, MaggoteersGame game,
                                               String counterId, TriggerContext ctx) {
        for (PlayerEffect e : new ArrayList<>(ps.effects())) {
            if (e.fireTrigger() != Trigger.ON_COUNTER) {
                continue;
            }
            if (!counterId.equals(e.params().get(EffectKeys.COUNTER_ID))) {
                continue;
            }
            executeEffect(p, e, game, ctx);
        }
    }

    /**
     * Spec 4.0 + 4.4: dispatch fireTrigger==T, then sweepExpiry; empower FX once per pack itemId on victim.
     */
    /** 2026-10-28：派发前的「蓄力 grant」快照（only_with_grant 用）。 */
    private static final ThreadLocal<Set<String>> GRANT_SNAPSHOT = ThreadLocal.withInitial(java.util.HashSet::new);

    /** 本次伤害事件开头是否已有蓄力 grant（用于 only_with_grant）。 */
    static boolean hasGrantSnapshot() {
        return !GRANT_SNAPSHOT.get().isEmpty();
    }

    static void processTriggerEffects(PlayerState ps, Player p, MaggoteersGame game,
                                      Trigger fired, TriggerContext useCtx) {
        Set<String> touched = new HashSet<>();
        // 现在的 grant 不会在本次派发中被创建后又被同一伤害看到
        java.util.Set<String> snap = GRANT_SNAPSHOT.get();
        snap.clear();
        for (PlayerEffect e2 : ps.effects()) {
            if (e2.id() != null && e2.id().endsWith(TriggeredGrantAttribute.GRANT_SUFFIX)) {
                snap.add(e2.id());
            }
        }
        Predicate<PlayerEffect> sweepFilter = weaponSourcedSweepFilter(p, fired);
        List<String> removed = TriggerFireOrder.run(
                () -> touched.addAll(dispatchTriggeredEffectsCollecting(p, ps, game, fired, useCtx)),
                () -> {
                    List<String> r = EffectStacker.sweepExpiry(ps.effects(), fired, sweepFilter);
                    for (String id : r) {
                        if (id != null && id.startsWith(WeaponAbilityIds.PREFIX)) {
                            touched.add(id);
                        }
                    }
                    return r;
                });
        if (!removed.isEmpty()) {
            resyncDerived(p, ps);
            // 惰性收集：仅当有效果被移除时才建 counter 反查表（计数器仅在移除时需 unregister，避免热路径空建）
            Map<String, CounterSpec> counterByEffectId = new HashMap<>();
            for (PlayerEffect e : ps.effects()) {
                CounterSpec cs = e.params().get(EffectKeys.COUNTER_SPEC);
                if (cs != null) {
                    counterByEffectId.put(e.id(), cs);
                }
            }
            for (String removedId : removed) {
                io.mczju.maggoteers.reward.CollectibleService.remove(p, removedId);
                BoundEquipService.remove(p, removedId);
                CounterSpec cs = counterByEffectId.get(removedId);
                if (cs != null) {
                    CounterService.unregister(ps, cs.id());
                }
            }
        }
        playEmpowerFxIfNeeded(useCtx, touched);
    }

    /**
     * ON_DAMAGE_DEALT 到期过滤：武器"下次攻击"强化（{@code ability:*} 与切肉刀类
     * {@code held:*:grant}）只有当前主手武器 == buff 来源武器才消耗——用别的武器攻击
     * 不会白嫖/误消耗另一把武器的强化（2026-08-23 修复）。非 ON_DAMAGE_DEALT 或非
     * 武器 buff 返回 null（不过滤，正常消耗）。
     */
    private static Predicate<PlayerEffect> weaponSourcedSweepFilter(Player p, Trigger fired) {
        if (fired != Trigger.ON_DAMAGE_DEALT) {
            return null;
        }
        String heldItemId = ItemService.itemIdOf(p.getInventory().getItemInMainHand());
        return e -> {
            String id = e.id();
            // 2026-10-28：下次攻击票券由 {@code EffectListener.onNextHitTicket} 消耗，
            // 不能在创建它的同一次伤害里被 sweep 扣掉。
            if (isNextHitTicket(e)) {
                return false;
            }
            if (id == null) {
                return true;
            }
            Optional<String> sourceItem;
            if (id.startsWith(WeaponAbilityIds.PREFIX)) {
                sourceItem = WeaponAbilityIds.itemIdOf(id);
            } else if (id.startsWith(WeaponHeldService.HELD_PREFIX)
                    && id.endsWith(TriggeredGrantAttribute.GRANT_SUFFIX)) {
                sourceItem = Optional.ofNullable(WeaponHeldService.parseHeldItemId(id));
            } else {
                return true;   // 非武器"下次攻击"buff：正常消耗
            }
            return sourceItem.map(source -> source.equals(heldItemId)).orElse(true);
        };
    }

    private static void playEmpowerFxIfNeeded(TriggerContext useCtx, Set<String> touched) {
        LivingEntity victim = useCtx != null ? useCtx.hitTarget() : null;
        if (victim == null || touched == null || touched.isEmpty()) {
            return;
        }
        for (String itemId : EmpowerFxCoalesce.itemIdsTouched(touched)) {
            ItemAbility ab = ItemAbilityRegistry.get(itemId).orElse(null);
            if (ab == null) {
                continue;
            }
            MagicUseFx empower = MagicFxConfig.resolveEmpowerFx(ab);
            if (empower == null) {
                continue;
            }
            MagicFxService.playOnEntity(victim, empower);
        }
    }

    /** Executes triggered effects on {@code fired}; returns executed {@code ability:*} effect ids. */
    private static Set<String> dispatchTriggeredEffectsCollecting(Player p, PlayerState ps, MaggoteersGame game,
                                                                  Trigger fired, TriggerContext ctx) {
        TriggerContext useCtx = ctx != null ? ctx : TriggerContext.empty();
        Set<String> executed = new HashSet<>();
        var snapshot = new ArrayList<>(ps.effects());
        for (PlayerEffect e : snapshot) {
            if (e.fireTrigger() != fired) continue;
            if (e.effect() == Effect.ADD_ATTRIBUTE && TriggeredGrantAttribute.isRevoke(e.params())) {
                executeTriggeredAddAttribute(p, e, game, useCtx);
                noteAbilityTouched(executed, e.id());
            }
        }
        for (PlayerEffect e : snapshot) {
            if (e.fireTrigger() != fired) continue;
            if (e.effect() == Effect.ADD_ATTRIBUTE && TriggeredGrantAttribute.isRevoke(e.params())) continue;
            executeEffect(p, e, game, useCtx);
            noteAbilityTouched(executed, e.id());
        }
        return executed;
    }

    private static void noteAbilityTouched(Set<String> out, String effectId) {
        if (out != null && effectId != null && effectId.startsWith(WeaponAbilityIds.PREFIX)) {
            out.add(effectId);
        }
    }

    private static void executeTriggeredAddAttribute(Player p, PlayerEffect e, MaggoteersGame game,
                                                     TriggerContext ctx) {
        PlayerState st = PlayerStateManager.get(game, p.getUniqueId());
        if (st == null) return;
        if (TriggeredGrantAttribute.isRevoke(e.params())) {
            String sourceId = e.params().get(EffectKeys.SOURCE_ID);
            if (sourceId == null || sourceId.isBlank()) return;
            if (TriggeredGrantAttribute.revokeGrants(st, sourceId)) {
                resyncDerived(p, st);
            }
            return;
        }
        if (e.fireTrigger() != null) {
            if (isAttackCounterGrant(e)) {
                // 切肉刀类：累计命中 N 次 → 下次攻击一次性强化。grant 创建于攻击回调内、
                // 当次 sweep 即扣 1 charge，故 charges=2：下次攻击享受 1 次后移除（2026-08-23）。
                // 2026-10-28：票券模型下由 EffectListener 在下一刀消耗，charges=1 即可
                TriggeredGrantAttribute.applyGrant(p, game, e, Trigger.ON_DAMAGE_DEALT, 1);
            } else {
                TriggeredGrantAttribute.applyGrant(p, game, e);
            }
            return;
        }
        // 认知失调互换：按有效属性名施加（物攻加成在诅咒下成为魔攻，不再写 Bukkit）
        applyAttribute(p, st.effects(), e);
    }

    /** 由 ATTACK 计数器驱动的一次性强化（切肉刀类）：count 满后 grant 应为"下次攻击"一次性。 */
    private static boolean isAttackCounterGrant(PlayerEffect e) {
        CounterSpec cs = e.params().get(EffectKeys.COUNTER_SPEC);
        return cs != null && cs.countTrigger() == CounterCountTrigger.ATTACK;
    }

    /** Weapon entry: FX already played by {@link io.mczju.maggoteers.item.interact.ConfigMagicHandler}. */
    public static boolean executeAbility(Player p, MaggoteersGame game, ItemAbility ability) {
        if (p == null || game == null || ability == null) return false;
        boolean didSide = false;
        List<org.bukkit.potion.PotionEffectType> selfClear = ability.selfClearPotions();
        if (selfClear != null && !selfClear.isEmpty()) {
            PotionClear.apply(p, selfClear);
            didSide = true;
        }
        List<BuffPotionSpec> selfPots = ability.selfPotions();
        if (selfPots != null && !selfPots.isEmpty()) {
            applySelfPotions(p, selfPots);
            didSide = true;
        }

        TriggerContext ctx = TriggerContext.empty();
        AtomicReference<LivingEntity> beamHit = new AtomicReference<>();
        boolean anyStep = false;
        List<AbilityStep> steps = ability.steps();
        for (int i = 0; i < steps.size(); i++) {
            AbilityStep step = steps.get(i);
            try {
                if (step.isDeferred()) {
                    if (writeDeferredStep(p, game, ability, step, i)) {
                        anyStep = true;
                    }
                    continue;
                }
                boolean ok;
                if (step.effect() == Effect.ADD_ATTRIBUTE) {
                    LOG.warning("use_ability " + ability.itemId() + " immediate ADD_ATTRIBUTE unsupported");
                    ok = false;
                } else if (step.effect() == Effect.ADD_POTION) {
                    int dur = step.params().getOrDefault(EffectKeys.DURATION_TICKS, 0);
                    ok = dur > 0 && applyInstantPotion(p, step.params());
                } else if (step.effect() == Effect.HEAL) {
                    ok = applyHealOrSelfDamage(p, step.params().getOrDefault(EffectKeys.AMOUNT, 0.0));
                } else {
                    ok = executeMagicEffect(p, game, step.effect(), step.params(),
                            ability.fx(), ctx, true, beamHit, null);
                    if (beamHit.get() != null) {
                        ctx = ctx.withHitTarget(beamHit.get());
                        beamHit.set(null);
                    }
                }
                if (ok) {
                    anyStep = true;
                }
            } catch (RuntimeException ex) {
                // 单步异常不拖垮整条技能链（如 DRAGON_BREATH 粒子 data 缺失曾中断全部 step，2026-08-22）
                LOG.warning("use_ability " + ability.itemId() + " step " + i + " (" + step.effect() + "): "
                        + ex.getClass().getSimpleName() + " " + ex.getMessage());
            }
        }
        boolean sideConfigured = (selfClear != null && !selfClear.isEmpty())
                || (selfPots != null && !selfPots.isEmpty());
        return anyStep || (sideConfigured && didSide);
    }

    /** Deferred step → PlayerEffect (ADD_* fireTrigger=null; magic fireTrigger=expiryTrigger). */
    private static boolean writeDeferredStep(Player p, MaggoteersGame game, ItemAbility ability,
                                             AbilityStep step, int stepIndex) {
        Optional<String> err = WeaponTempEffect.validate(
                step.effect(), step.params(), step.expiryTrigger(), step.expiryCharges());
        if (err.isPresent()) {
            LOG.warning("use_ability deferred " + ability.itemId() + " step " + stepIndex + ": " + err.get());
            return false;
        }
        Stack stack = step.stack() != null ? step.stack() : Stack.REPLACE;
        boolean addFamily = step.effect() == Effect.ADD_ATTRIBUTE || step.effect() == Effect.ADD_POTION;
        boolean legacySingleAdd = addFamily && ability.steps().size() == 1;
        String id = legacySingleAdd
                ? WeaponAbilityIds.effectId(ability.itemId())
                : WeaponAbilityIds.effectId(ability.itemId(), stepIndex);
        // 2026-10-27：永久代价（无 expiry 的 ADD_ATTRIBUTE）用 curse: 前缀——切武器不被 ability:* 清掉
        if (step.effect() == Effect.ADD_ATTRIBUTE && step.expiryTrigger() == null) {
            id = "curse:" + ability.itemId() + ":" + stepIndex;
        }
        Trigger fire = addFamily ? null : step.expiryTrigger();
        EffectContext params = step.params() != null ? step.params().copy() : new EffectContext();
        PlayerEffect pe = new PlayerEffect(
                id, step.effect(), params, fire,
                step.expiryTrigger(), step.expiryCharges(),
                0, null, stack, 0, 0);
        apply(p, pe, game);
        // 2026-10-28（仪式匕首）：限时加成——ADD_ATTRIBUTE 无 expiry 但写了 duration_ticks 时，
        // 到点主动摘掉并按需重派生（否则就是"常驻到底"的假 buff）。
        if (step.effect() == Effect.ADD_ATTRIBUTE && step.expiryTrigger() == null) {
            int durTicks = params.getOrDefault(EffectKeys.DURATION_TICKS, 0);
            if (durTicks > 0) {
                scheduleTimedEffectRemoval(p, id, durTicks);
            }
        }
        return true;
    }

    /** 限时效果（use_ability 的 {@code ADD_ATTRIBUTE + duration_ticks}）的到期摘除任务。 */
    private static final Map<String, org.bukkit.scheduler.BukkitTask> TIMED_EFFECT_REMOVALS = new HashMap<>();

    private static void scheduleTimedEffectRemoval(Player p, String effectId, int ticks) {
        if (p == null || effectId == null || ticks <= 0) return;
        String key = p.getUniqueId() + "|" + effectId;
        org.bukkit.scheduler.BukkitTask prev = TIMED_EFFECT_REMOVALS.remove(key);
        if (prev != null) prev.cancel();
        org.bukkit.scheduler.BukkitTask task = Bukkit.getScheduler().runTaskLater(
                MaggoteersPlugin.getInstance(), () -> {
                    TIMED_EFFECT_REMOVALS.remove(key);
                    Player online = Bukkit.getPlayer(p.getUniqueId());
                    if (online == null) return;
                    MaggoteersGame game = currentGame(online);
                    if (game == null) return;
                    var st = PlayerStateManager.get(game, online.getUniqueId());
                    if (st == null) return;
                    if (st.effects().removeIf(e -> effectId.equals(e.id()))) {
                        resyncDerived(online, st);
                    }
                }, ticks);
        TIMED_EFFECT_REMOVALS.put(key, task);
    }

    private static void applySelfPotions(Player p, java.util.List<BuffPotionSpec> potions) {
        if (p == null || potions == null || potions.isEmpty()) return;
        for (BuffPotionSpec spec : potions) {
            PotionMerge.applyIfNeeded(p, spec.type(), spec.amp(), spec.durationTicks());
        }
    }

    private static boolean applyInstantPotion(Player p, EffectContext params) {
        if (p == null || params == null) return false;
        PotionEffectType type = params.get(EffectKeys.POTION);
        if (type == null) return false;
        if (PotionImmunity.isImmune(p, type)) return false;
        int amp = params.getOrDefault(EffectKeys.AMP, 0);
        int dur = params.getOrDefault(EffectKeys.DURATION_TICKS, 0);
        if (dur <= 0) return false;
        p.addPotionEffect(new PotionEffect(type, dur, amp, false, true));
        return true;
    }

    /**
     * 治疗：上限按 MAX_HEALTH（缺省 20.0），返回实际增加的血量。
     * 集中散落各处的 {min(max, health+amount)}（applyHealOrSelfDamage 正分支 / HEAL_AREA / AuraService / SUPPLY_HEALING）。
     */
    public static double heal(Player p, double amount) {
        if (p == null || amount <= 0) return 0.0;
        var hp = p.getAttribute(Attribute.MAX_HEALTH);
        double max = hp != null ? hp.getValue() : 20.0;
        double before = p.getHealth();
        double after = Math.min(max, before + amount);
        p.setHealth(after);
        return after - before;
    }

    // ──────── 2026-10-27：虚拟属性 HEAL / RANGE 的生效点（治疗量 / 作用半径） ────────

    /** 施法者的治疗强度系数（虚拟属性 HEAL，基础 100）；怪物/无状态 → 1.0。 */
    public static double healMultiplierOf(org.bukkit.entity.LivingEntity caster) {
        if (caster instanceof Player p) {
            var st = PlayerStateManager.getByUuid(p.getUniqueId());
            if (st == null) return 1.0;
            return PlayerCombatStats.healMultiplierFromEffects(st.effects(), p);
        }
        return 1.0;
    }

    /** 施法者的技能范围系数（虚拟属性 RANGE，基础 100）；怪物/无状态 → 1.0。 */
    public static double rangeMultiplierOf(org.bukkit.entity.LivingEntity caster) {
        if (caster instanceof Player p) {
            var st = PlayerStateManager.getByUuid(p.getUniqueId());
            if (st == null) return 1.0;
            return PlayerCombatStats.rangeMultiplierFromEffects(st.effects(), p);
        }
        return 1.0;
    }

    /** 正向治疗量按施法者治疗强度缩放（负值 = 扣血代价，不缩放）。 */
    static double scaledHealAmount(org.bukkit.entity.LivingEntity caster, double amount) {
        if (amount <= 0) return amount;
        return amount * healMultiplierOf(caster);
    }

    /** 半径按施法者技能范围缩放（怪物不变）。 */
    static double scaledRadius(org.bukkit.entity.LivingEntity caster, double radius) {
        if (radius <= 0) return radius;
        return radius * rangeMultiplierOf(caster);
    }


    /** 光束射程按施法者技能范围缩放。 */
    static double scaledRayLength(org.bukkit.entity.LivingEntity caster, double length) {
        if (length <= 0) return length;
        return length * rangeMultiplierOf(caster);
    }

    /** 复制 params 并把 radius 按施法者技能范围放大（BuffAreaTargets / DisableAiTargets 内部只读 radius）。 */
    static EffectContext withScaledRadius(org.bukkit.entity.LivingEntity caster, EffectContext params) {
        Double raw = params == null ? null : params.get(EffectKeys.RADIUS);
        if (params == null || raw == null || raw <= 0) return params;
        double scaled = scaledRadius(caster, raw);
        if (Math.abs(scaled - raw) < 1e-9) return params;
        EffectContext copy = params.copy();
        copy.put(EffectKeys.RADIUS, scaled);
        return copy;
    }

    /**
     * Positive amount heals; negative amount is an exact HP cost via {@link Player#setHealth}
     * (not {@link Player#damage}) so armor / resistance / invuln frames cannot cancel or dilute it.
     */
    static boolean applyHealOrSelfDamage(Player p, double amount) {
        if (p == null || amount == 0) return false;
        if (amount < 0) {
            // Exact cost: no Attribute lookup (keeps unit tests free of RegistryAccess).
            p.setHealth(Math.max(0.0, p.getHealth() + amount));
            return true;
        }
        heal(p, amount);
        return true;
    }

    /**
     * 执行一条效果（触发型在 fireTrigger 时调；常驻型在 apply 时已施加）。
     * <p>PlayerEffect 路径：params 在解析期已带默认值，这里<b>不</b>套 withDefaults。
     * 因此 GRANT_ITEM/SUMMON/GRANT_REVIVE 与 {@link #executeMagicEffect} 里的双份分支<b>有意保留</b>——
     * 那边先套 {@code MagicEffectParams.withDefaults}（武器 use_ability 路径），合并会把默认值引入本路径，改变行为。
     */
    private static void executeEffect(Player p, PlayerEffect e, MaggoteersGame game, TriggerContext ctx) {
        switch (e.effect()) {
            case HEAL -> {
                // 正向治疗吃施法者的治疗强度；负值是扣血代价，不缩放
                double amount = e.params().getOrDefault(EffectKeys.AMOUNT, 0.0);
                applyHealOrSelfDamage(p, scaledHealAmount(p, amount));
            }
            case ADD_POTION -> {
                PotionEffectType type = e.params().get(EffectKeys.POTION);
                if (type == null) return;
                if (Boolean.TRUE.equals(e.params().get(EffectKeys.IMMUNITY))) {
                    return;
                }
                if (PotionImmunity.isImmune(p, type)) return;
                int amp = e.params().getOrDefault(EffectKeys.AMP, 0);
                int dur = e.params().getOrDefault(EffectKeys.DURATION_TICKS, 0);
                if (dur > 0) p.addPotionEffect(new PotionEffect(type, dur, amp, false, true));
            }
            // 2026-10-28：FREEZE 也进玩家触发通道（武器 held_effects "攻击附带寒冷"、奖励 cold 藏品）。
            // 实际执行仍在 executeFreezeCore（与 use_ability / 怪物共用一份实现）。
            case DAMAGE_AREA, DAMAGE_BEAM, HEAL_AREA, BUFF_AREA, DISABLE_AI, FREEZE -> {
                boolean weaponPack = e.id() != null && e.id().startsWith(WeaponAbilityIds.PREFIX);
                MagicUseFx weaponFx = null;
                if (weaponPack) {
                    String itemId = WeaponAbilityIds.itemIdOf(e.id()).orElse(null);
                    ItemAbility ab = itemId == null ? null : ItemAbilityRegistry.get(itemId).orElse(null);
                    weaponFx = ab != null ? ab.fx() : null;
                }
                executeMagicEffect(p, game, e.effect(), e.params(), weaponFx, ctx, weaponPack, e.id());
            }
            case GRANT_REVIVE -> {
                if (!reviveGatePasses(p, e.params())) return;
                int count = e.params().getOrDefault(EffectKeys.COUNT, 1);
                PlayerStateManager.addReviveCount(game, p.getUniqueId(), count);
            }
            case GRANT_ITEM -> executeGrantItem(p, e.params());
            case LOSE_COINS -> executeLoseCoins(p, e.params());
            case SUMMON -> executeSummon(p, game, e.params(), ctx, false);
            case ADD_ATTRIBUTE -> executeTriggeredAddAttribute(p, e, game, ctx);
        }
    }

    /**
     * 2026-10-28（魔王的旗帜）：GRANT_REVIVE 的 {@code min_revives} 门槛——
     * 剩余复活次数 ≥ min 才发放（缺省 0 = 无门槛）。
     */
    private static boolean reviveGatePasses(Player p, EffectContext params) {
        if (p == null || params == null) return true;
        int min = params.getOrDefault(EffectKeys.MIN_REVIVES, 0);
        if (min <= 0) return true;
        var st = PlayerStateManager.getByUuid(p.getUniqueId());
        return st != null && st.getReviveCount() >= min;
    }

    /** 2026-10-28（诅咒·贫穷）：夺走普通货币；{@code amount <= 0/缺省} = 全部。 */
    private static void executeLoseCoins(Player p, EffectContext params) {
        int amount = params == null ? 0 : params.getOrDefault(EffectKeys.AMOUNT, 0.0).intValue();
        io.mczju.maggoteers.item.ItemService.loseNormalCurrency(p, amount);
    }

    private static boolean executeMagicEffect(Player p, MaggoteersGame game, Effect effect,
                                           EffectContext rawParams, MagicUseFx weaponFx,
                                           TriggerContext ctx) {
        return executeMagicEffect(p, game, effect, rawParams, weaponFx, ctx, weaponFx != null, null, null);
    }

    private static boolean executeMagicEffect(Player p, MaggoteersGame game, Effect effect,
                                           EffectContext rawParams, MagicUseFx weaponFx,
                                           TriggerContext ctx, boolean weaponPath, String effectId) {
        return executeMagicEffect(p, game, effect, rawParams, weaponFx, ctx, weaponPath, null, effectId);
    }

    /**
     * 武器 use_ability/held 路径：先 {@code MagicEffectParams.withDefaults} 套默认值再分发。
     * <p>GRANT_ITEM/SUMMON/GRANT_REVIVE 与 {@link #executeEffect} 里的双份分支<b>有意保留</b>——
     * 那边（PlayerEffect 路径）不套默认值，合并会把本路径默认值引入普通效果执行，改变行为。
     */
    private static boolean executeMagicEffect(Player p, MaggoteersGame game, Effect effect,
                                           EffectContext rawParams, MagicUseFx weaponFx,
                                           TriggerContext ctx, boolean weaponPath,
                                           AtomicReference<LivingEntity> hitTargetOut, String effectId) {
        EffectContext params = MagicEffectParams.withDefaults(effect, rawParams);
        TriggerContext useCtx = ctx != null ? ctx : TriggerContext.empty();
        switch (effect) {
            case GRANT_ITEM -> {
                return executeGrantItem(p, params);
            }
            case SUMMON -> {
                return executeSummon(p, game, params, useCtx, weaponPath);
            }
            case DAMAGE_AREA -> {
                // 法伤倍率是玩家-only（mob 无此 stat）：玩家包装层折算后喂入核心，核心保持施法者无关
                double baseDmg = params.getOrDefault(EffectKeys.DAMAGE, 0.0);
                double dmg = baseDmg * PlayerCombatStats.magicDamageMultiplier(game, p.getUniqueId());
                params.put(EffectKeys.DAMAGE, dmg);
                Runnable action = () -> executeEffectCore(p, game, effect, params, useCtx);
                // Spec 4.3.1: wrap when shouldWrapMagicDamage — not when weaponFx != null alone
                if (shouldWrapMagicDamage(useCtx)) {
                    MagicDamageContext.run(game, p.getUniqueId(), action);
                } else {
                    action.run();
                }
                return true;
            }
            case DAMAGE_BEAM -> {
                LivingEntity beamHit = executeDamageBeam(p, game, params, weaponFx);
                if (beamHit != null && hitTargetOut != null) {
                    hitTargetOut.set(beamHit);
                }
                return true;
            }
            case CONE -> {
                // 2026-10-29 扇形打击：法伤倍率只折算 enemies 那一半（治疗走 scaledHealAmount）
                double coneBaseDmg = params.getOrDefault(EffectKeys.DAMAGE, 0.0);
                if (coneBaseDmg > 0) {
                    params.put(EffectKeys.DAMAGE,
                            coneBaseDmg * PlayerCombatStats.magicDamageMultiplier(game, p.getUniqueId()));
                }
                Runnable coneAction = () -> executeConeCore(p, game, params, useCtx, weaponFx);
                if (shouldWrapMagicDamage(useCtx)) {
                    MagicDamageContext.run(game, p.getUniqueId(), coneAction);
                } else {
                    coneAction.run();
                }
                return true;
            }
            case HEAL_AREA -> {
                executeEffectCore(p, game, effect, params, useCtx);
                return true;
            }
            case BUFF_AREA -> {
                executeBuffArea(p, game, params, weaponFx, useCtx, weaponPath, effectId);
                return true;
            }
            case DISABLE_AI -> {
                executeDisableAi(p, game, params, weaponFx, useCtx, weaponPath, effectId);
                return true;
            }
            case GRANT_REVIVE -> {
                if (!reviveGatePasses(p, params)) return true;
                int count = params.getOrDefault(EffectKeys.COUNT, 1);
                PlayerStateManager.addReviveCount(game, p.getUniqueId(), count);
                return true;
            }
            case LOSE_COINS -> {
                executeLoseCoins(p, params);
                return true;
            }
            case DAMAGE, SALVO, BARRAGE -> {
                // 法伤倍率是玩家-only：折算后喂入核心；SALVO 的 fireBeam 读 params.DAMAGE 生效，
                // BARRAGE 玩家路径自行构造 SummonParams（damage 0.0）不消费该键，fold 无副作用。
                double baseDmg = params.getOrDefault(EffectKeys.DAMAGE, 0.0);
                double dmg = baseDmg * PlayerCombatStats.magicDamageMultiplier(game, p.getUniqueId());
                params.put(EffectKeys.DAMAGE, dmg);
                Runnable action = () -> executeEffectCore(p, game, effect, params, useCtx);
                if (shouldWrapMagicDamage(useCtx)) {
                    MagicDamageContext.run(game, p.getUniqueId(), action);
                } else {
                    action.run();
                }
                return true;
            }
            // 2026-10-29：FREEZE 之前漏在这个 switch 外面——held_effects / use_ability 的寒冷全部
            // 落到 default 返回 false（静默无效），只有 CONE / AURA 两条路径能冻。
            case TELEPORT, DISPLACE, PERMANENT_ATTRIBUTE, COUNTER_ARM, SOUND, PARTICLE_RING,
                 CLEAR_POTIONS, IGNITE, FREEZE -> {
                executeEffectCore(p, game, effect, params, useCtx);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /** 范围效果圆心来源键（纯逻辑可测）：explicit > eventLocation > hitTarget > caster。 */
    static String resolveOriginTag(EffectContext params, TriggerContext ctx) {
        String o = params == null ? null : params.get(EffectKeys.ORIGIN);
        if (o != null && !o.isBlank()) return o.trim().toLowerCase(java.util.Locale.ROOT);
        if (ctx != null && ctx.eventLocation() != null) return "event";
        if (ctx != null && ctx.hitTarget() != null) return "hit_target";
        return "self";
    }

    /** 解析范围效果圆心（LivingEntity 级，无 PlayerState 依赖）。 */
    static Location resolveOrigin(LivingEntity caster, EffectContext params, TriggerContext ctx) {
        String tag = resolveOriginTag(params, ctx);
        return switch (tag) {
            case "event" -> ctx != null && ctx.eventLocation() != null
                    ? ctx.eventLocation().clone() : caster.getLocation().clone();
            // 2026-10-28：目标已死/无效时用**命中时记下的事件坐标**，
            // 不能回退到施法者脚下（否则切肉刀的范围强力击会在玩家身边空放）。
            case "hit_target" -> ctx != null && ctx.hitTarget() != null
                    ? ctx.hitTarget().getLocation().clone()
                    : (ctx != null && ctx.eventLocation() != null
                        ? ctx.eventLocation().clone() : caster.getLocation().clone());
            default -> caster.getLocation().clone();
        };
    }

    /**
     * 纯函数（可测）：按瞄准档位选择光束方向。
     * <p>{@code aim: per_player} → 从施法者眼睛指向目标中心（caster→player）；
     * 其余（含 {@code aim: ray} / 缺省）→ 施法者准星方向。
     */
    static Vector resolveDirection(Vector eye, Vector look, String aim, Vector targetCenter) {
        String a = aim == null ? "" : aim.trim().toLowerCase(java.util.Locale.ROOT);
        if ("per_player".equals(a) && targetCenter != null) {
            Vector e = eye != null ? eye : new Vector(0, 0, 0);
            return targetCenter.clone().subtract(e);
        }
        return look != null ? look.clone() : new Vector(0, 0, 1);
    }

    /** 纯效果执行核：施法者抽象为 LivingEntity，无 PlayerState 依赖（Spec1 §4.1）。
     *  玩家侧经 executeMagicEffect 转发、mob 侧经 MobEffectExecutor.execute 转发到此。 */
    public static void executeEffectCore(LivingEntity caster, MaggoteersGame game,
                                         Effect effect, EffectContext params, TriggerContext ctx) {
        if (caster == null || effect == null || params == null) return;
        try {
            switch (effect) {
                case DAMAGE_AREA, HEAL_AREA, BUFF_AREA -> executeAreaCore(caster, game, effect, params, ctx);
                case DAMAGE_BEAM, SALVO -> executeBeamCore(caster, game, effect, params, ctx);
                case BARRAGE -> executeBarrageCore(caster, game, params, ctx);
                case TELEPORT -> executeTeleportCore(caster, game, params, ctx);
                case DISPLACE -> executeDisplaceCore(caster, game, params, ctx);
                case DISABLE_AI -> executeDisableAiCore(caster, game, params);
                case HEAL -> executeHealCore(caster, game, params, ctx);
                case DAMAGE -> executeDamageCore(caster, game, params, ctx);
                case ADD_POTION, CLEAR_POTIONS -> executePotionCore(caster, params, ctx);
                case ADD_ATTRIBUTE, PERMANENT_ATTRIBUTE -> executeAttributeCore(caster, effect, params, ctx);
                case SUMMON -> executeSummonCore(caster, game, params, ctx);
                case PARTICLE_RING -> executeParticleRingCore(caster, params, ctx);
                case SOUND -> executeSoundCore(caster, game, params);
                case COUNTER_ARM -> executeCounterArmCore(caster, params);
                // 2026-10-26：Boss 读条大招 / 叠层 / 吞噬 / 场地传送
                case DELAYED_AREA -> executeDelayedAreaCore(caster, game, params);
                case STUN -> executeStunCore(caster, game, params);
                case DEVOUR -> executeDevourCore(caster, game, params);
                case CONSUME_STACKS -> executeConsumeStacksCore(caster, game, params);
                case FREEZE -> executeFreezeCore(caster, game, params, ctx);
                case CONE -> executeConeCore(caster, game, MagicEffectParams.withDefaults(Effect.CONE, params), ctx);
                case IGNITE -> executeIgniteCore(caster, game, params, ctx);
                case FANG_PATTERN -> executeFangPatternCore(caster, game, params);
                case STEAL_ITEM -> executeStealItemCore(caster, params, ctx);
                case CURSE_PLAYERS -> executeCursePlayersCore(caster, game, params);
                default -> { /* GRANT_* / AURA / BOUND_EQUIP 等玩家-only 不走核 */ }
            }
        } catch (RuntimeException ex) {
            LOG.warning("executeEffectCore " + effect + " caster=" + caster.getType() + ": " + ex.getMessage());
        }
    }

    private static void executeAreaCore(LivingEntity caster, MaggoteersGame game,
                                        Effect effect, EffectContext params, TriggerContext ctx) {
        EffectContext p = MagicEffectParams.withDefaults(effect, params);
        switch (effect) {
            case DAMAGE_AREA -> executeDamageArea(caster, game, p, ctx);
            case HEAL_AREA -> executeHealArea(caster, game, p, ctx);
            case BUFF_AREA -> { /* 玩家侧仍在 executeBuffArea（weaponPath/weaponFx/fx 语义）；mob 侧 Task 7 */ }
            default -> { }
        }
    }

    private static void executeDamageArea(LivingEntity caster, MaggoteersGame game,
                                          EffectContext params, TriggerContext ctx) {
        // 2026-10-28：只在"本次命中带蓄力" 时才生效（切肉刀类范围强力击）
        if (Boolean.TRUE.equals(params.get(EffectKeys.ONLY_WITH_GRANT)) && !hasGrantSnapshot()) return;
        double radius = scaledRadius(caster, params.getOrDefault(EffectKeys.RADIUS, 3.0));
        double dmg = params.getOrDefault(EffectKeys.DAMAGE, 0.0);   // 玩家路径已折算魔伤倍率
        Location origin = resolveOrigin(caster, params, ctx);
        if (origin == null || origin.getWorld() == null) return;
        io.mczju.maggoteers.util.ParticleEffects.playAreaRing(origin.getWorld(), origin, radius);
        // Spec §5：怪物施法 DAMAGE_AREA targets:enemies 只命中冒险玩家——TargetResolver 的 enemies
        // 分支返回的是其他被追踪怪，会误伤施法者队友（wardenwrath/zizai_boom/final_gift）；
        // 玩家武器路径保持不变（enemies = 敌对怪）。
        List<LivingEntity> targets;
        if (!(caster instanceof Player) && AuraParams.targetsEnemies(params)) {
            targets = new ArrayList<>(adventurePlayersInRadius(game, origin, radius));
        } else {
            targets = TargetResolver.collect(game, caster, origin, radius, Effect.DAMAGE_AREA, params);
        }
        List<BuffPotionSpec> potions = params.get(EffectKeys.POTIONS);
        List<PotionEffectType> clears = params.get(EffectKeys.CLEAR_POTIONS);
        EffectContext markFxCtx = params.get(EffectKeys.MARK_FX);
        MagicUseFx markFx = markFxCtx == null ? null
                : MagicFxBuilder.fromContext(markFxCtx, MagicUseFx.defaults());
        Runnable applyAll = () -> {
            for (LivingEntity le : targets) {
                MagicDamage.applyIndependent(le, dmg, caster);
                if (!le.isValid() || le.isDead()) {
                    continue;
                }
                PotionClear.apply(le, clears);
                if (potions != null && !potions.isEmpty()) {
                    for (BuffPotionSpec spec : potions) {
                        PotionMerge.applyIfNeeded(le, spec.type(), spec.amp(), spec.durationTicks());
                    }
                }
                if (markFx != null) {
                    playMarkFx(le, markFx);
                }
            }
        };
        // 2026-10-28：由"计数器/事件"触发出来的伤害，不能再反向喂给计数器（否则群怪时一次强力击会连鎖刷层）。
        if (ctx != null && ctx.fired() != null && caster instanceof Player pl) {
            MagicDamageContext.run(game, pl.getUniqueId(), applyAll);
        } else {
            applyAll.run();
        }
    }

    private static void executeHealArea(LivingEntity caster, MaggoteersGame game,
                                        EffectContext params, TriggerContext ctx) {
        // 2026-10 修复：历史上非 Player 施法者直接 return（HEAL_AREA 对怪物是空实现）。
        // 现在与 HEAL targets: area 共用同侧治疗内核：怪物治疗半径内的被追踪怪（含自己）。
        double radius = scaledRadius(caster, params.getOrDefault(EffectKeys.RADIUS, 5.0));
        Double amount = params.get(EffectKeys.AMOUNT);
        boolean includeSelf = AuraParams.includeSelf(params);
        Location origin = resolveOrigin(caster, params, ctx);
        if (origin == null || origin.getWorld() == null) origin = caster.getLocation();
        // 2026-10-29：HEAL_AREA 之前完全不画粒子（典范砍人只有回血、没有圈）。
        // 有 params.fx 就以 origin 为圆心播一次（半径用本次治疗半径），没配就保持安静。
        EffectContext fxCtx = params.get(EffectKeys.FX);
        if (fxCtx != null && origin.getWorld() != null) {
            MagicUseFx raw = MagicFxBuilder.fromContext(fxCtx, MagicUseFx.defaults());
            MagicFxPresets.play(origin, new MagicUseFx(raw.sound(), raw.soundVolume(), raw.soundPitch(),
                    raw.preset(), raw.particle(), radius, raw.rayLength(), raw.density(), raw.rippleRings(),
                    raw.expandSteps(), raw.spiralTicks(), raw.durationTicks()));
        }
        healAlliesInRadius(caster, game, origin, radius, includeSelf, amount, null);
    }

    /** DAMAGE_BEAM / SALVO：共用 traceBeam；aim 两档（ray=准星 / per_player=逐玩家）。 */
    private static void executeBeamCore(LivingEntity caster, MaggoteersGame game,
                                        Effect effect, EffectContext params, TriggerContext ctx) {
        if (caster == null || caster.getWorld() == null) return;
        if (effect == Effect.SALVO) {
            executeSalvoVolley(caster, game, params);
        } else {
            fireBeam(caster, game, params);
        }
    }

    /** SALVO：纯连射（delay_ticks 首射 + interval_ticks 间隔 × repeats），不含锁 AI / 音效。 */
    private static void executeSalvoVolley(LivingEntity caster, MaggoteersGame game, EffectContext params) {
        if (game == null || caster.isDead()) return;
        int startDelay = params.getOrDefault(EffectKeys.DELAY_TICKS, 60);
        int interval = Math.max(1, params.getOrDefault(EffectKeys.INTERVAL_TICKS, 20));
        int shots = Math.max(1, params.getOrDefault(EffectKeys.REPEATS, 5));
        UUID mobId = caster.getUniqueId();
        for (int i = 0; i < shots; i++) {
            long delay = (long) startDelay + (long) i * interval;
            new BukkitRunnable() {
                @Override public void run() {
                    Entity raw = Bukkit.getEntity(mobId);
                    if (!(raw instanceof LivingEntity le) || le.isDead()) return;
                    fireBeam(le, game, params);
                }
            }.runTaskLater(MaggoteersPlugin.getInstance(), delay);
        }
    }

    /** 单次光束：按 aim 分派（per_player 对每个冒险玩家各一束；ray 沿施法者准星）。 */
    private static void fireBeam(LivingEntity caster, MaggoteersGame game, EffectContext params) {
        if (caster == null || caster.getWorld() == null) return;
        double damage = params.getOrDefault(EffectKeys.DAMAGE, 0.0);
        double healAmount = params.getOrDefault(EffectKeys.HEAL_AMOUNT, 0.0);
        if (damage <= 0 && healAmount <= 0) return;
        String aim = resolveAim(caster, params);
        double maxRange = scaledRayLength(caster, params.getOrDefault(EffectKeys.RAY_LENGTH, 32.0));
        double beamRadius = params.getOrDefault(EffectKeys.BEAM_RADIUS, 1.25);
        Location eye = caster.getEyeLocation();
        Vector look = eye.getDirection();

        if ("per_player".equals(aim)) {
            if (game == null) return;
            for (Player pl : adventurePlayers(game)) {
                if (pl.getWorld() == null || !pl.getWorld().equals(caster.getWorld())) continue;
                Location center = pl.getLocation().add(0, pl.getHeight() * 0.5, 0);
                Vector dir = resolveDirection(eye.toVector(), look, "per_player", center.toVector());
                double len = dir.length();
                if (len < 0.05) continue;
                traceBeam(caster, game, eye, dir, len, beamRadius, params, pl);
            }
            return;
        }
        // ray：沿施法者准星，采样结算（玩家默认）
        Vector dir = resolveDirection(eye.toVector(), look, "ray", null);
        traceBeam(caster, game, eye, dir, maxRange, beamRadius, params, null);
    }

    /** 瞄准档位：显式 aim 优先；缺省按施法者（玩家=ray，怪=per_player）。 */
    private static String resolveAim(LivingEntity caster, EffectContext params) {
        String aim = params.get(EffectKeys.AIM);
        if (aim != null && !aim.isBlank()) return aim.trim().toLowerCase(Locale.ROOT);
        return caster instanceof Player ? "ray" : "per_player";
    }

    /**
     * 光束结算：沿 direction 画 beam + 命中结算。
     * <p>{@code directTarget != null}（per_player）：直接命中固定目标（mob 语义 {@code damage(damage, caster)}）；
     * {@code directTarget == null}（ray）：采样结算 + 友军治疗（玩家语义）。
     */
    private static void traceBeam(LivingEntity caster, MaggoteersGame game, Location from, Vector direction,
                                  double length, double radius, EffectContext params, LivingEntity directTarget) {
        if (from == null || from.getWorld() == null || direction == null) return;
        double damage = params.getOrDefault(EffectKeys.DAMAGE, 0.0);
        double healAmount = params.getOrDefault(EffectKeys.HEAL_AMOUNT, 0.0);
        Particle particle = resolveParticle(params.get(EffectKeys.PARTICLE_NAME));
        Vector dir = direction.clone().normalize();
        // 2026-10-29：撞墙即停（怪物光束同样不穿墙）
        length = clampBeamByWalls(from, dir, length);
        MagicUseFx fx = new MagicUseFx(null, 0f, 0f, MagicFxPreset.AIM_RAY, particle, 0, length,
                Math.max(48, (int) length), 0, 0, 0, 0);
        MagicFxPresets.playBeamAlong(from, dir, length, fx);

        if (directTarget != null) {
            if (damage > 0 && directTarget.isValid() && !directTarget.isDead()) {
                directTarget.damage(damage, caster);
            }
            return;
        }
        // ray 采样结算（2026-10-27：**怪物侧默认仍贯穿**，跟武器侧的默认单体相反；治疗始终贯穿）
        boolean pierce = !Boolean.FALSE.equals(params.get(EffectKeys.PIERCE));
        Set<UUID> hit = new HashSet<>();
        int steps = Math.max(16, (int) (length * 2));
        List<LivingEntity> beamTargets = new ArrayList<>();
        for (int i = 0; i <= steps; i++) {
            Location pt = from.clone().add(dir.clone().multiply(length * i / (double) steps));
            for (Entity en : from.getWorld().getNearbyEntities(pt, radius, radius, radius)) {
                if (!(en instanceof LivingEntity le)) continue;
                if (TargetResolver.isTarget(game, caster, le, params)) {
                    if (!hit.add(le.getUniqueId())) continue;
                    beamTargets.add(le);
                    if (!pierce) break;
                }
            }
            if (!pierce && !beamTargets.isEmpty()) break;
        }
        for (LivingEntity le : beamTargets) {
            MagicDamage.applyIndependent(le, damage, caster);
        }
        if (healAmount > 0 && caster instanceof Player player) {
            boolean includeSelf = AuraParams.includeSelf(params);
            double healAmt = scaledHealAmount(player, healAmount);
            Set<UUID> healed = new HashSet<>();
            if (includeSelf) {
                healed.add(player.getUniqueId());
                heal(player, healAmt);
            }
            for (Player ally : AllyTargeting.alliesOnBeam(game, from, dir, length, radius, player, includeSelf)) {
                if (!healed.add(ally.getUniqueId())) continue;
                heal(ally, healAmt);
            }
        }
    }

    /** BARRAGE：弹体连发（mob 走 MobProjectileService.shoot；玩家走 SummonExecutor 弹道）。 */
    private static void executeBarrageCore(LivingEntity caster, MaggoteersGame game,
                                           EffectContext params, TriggerContext ctx) {
        if (caster.getWorld() == null) return;
        String name = params.get(EffectKeys.ENTITY);
        if (name == null || name.isBlank()) return;
        EntityType type = GameRegistries.entityType(name);
        if (type == null) return;
        int count = Math.max(1, params.getOrDefault(EffectKeys.COUNT, 3));
        double speed = params.getOrDefault(EffectKeys.PROJECTILE_SPEED, 1.0);
        String homing = params.get(EffectKeys.HOMING_TARGET);
        int startDelay = params.getOrDefault(EffectKeys.DELAY_TICKS, 0);
        int interval = Math.max(1, params.getOrDefault(EffectKeys.INTERVAL_TICKS, 10));

        if (caster instanceof Player player) {
            UUID id = player.getUniqueId();
            for (int i = 0; i < count; i++) {
                long delay = (long) startDelay + (long) i * interval;
                new BukkitRunnable() {
                    @Override public void run() {
                        Player pl = Bukkit.getPlayer(id);
                        if (pl == null || !pl.isOnline()) return;
                        SummonParams sp = new SummonParams(type, "self", 1, 0.0, null, 0,
                                Map.of(), false, false,
                                new SummonParams.ProjectileParams(speed), homing);
                        SummonExecutor.spawn(pl, game, sp, TriggerContext.empty());
                    }
                }.runTaskLater(MaggoteersPlugin.getInstance(), delay);
            }
            return;
        }
        // mob：复用 MobProjectileService.shoot
        if (game == null) return;
        LivingEntity target = ctx != null ? ctx.hitTarget() : null;
        LivingEntity source = ctx != null ? ctx.attacker() : null;
        String h = homing == null ? "" : homing.trim().toLowerCase(Locale.ROOT);
        final LivingEntity init;
        if ("attack_target".equals(h)) init = target;
        else if ("damage_source".equals(h) || "killer".equals(h)) init = source;
        else init = null;
        UUID mobId = caster.getUniqueId();
        for (int i = 0; i < count; i++) {
            long delay = (long) startDelay + (long) i * interval;
            new BukkitRunnable() {
                @Override public void run() {
                    Entity raw = Bukkit.getEntity(mobId);
                    if (!(raw instanceof LivingEntity le) || le.isDead()) return;
                    MobProjectileService.shoot(le, game, type,
                            new SummonParams.ProjectileParams(speed), homing, init);
                }
            }.runTaskLater(MaggoteersPlugin.getInstance(), delay);
        }
    }

    /** TELEPORT：anchor=random → 附近随机安全位置；否则 targets=target/hit_target → 目标上方安全点，缺省自身。 */
    private static void executeTeleportCore(LivingEntity caster, MaggoteersGame game,
                                            EffectContext params, TriggerContext ctx) {
        double[] origin = layerOriginXZ(game);
        String anchor = params.get(EffectKeys.ANCHOR);
        if (anchor != null && "arena_point".equalsIgnoreCase(anchor.trim())) {
            // 2026-10-26：随机传送到本层 points.yml 的一个刷怪点（自身当前位置所在点除外）
            Location dest = io.mczju.maggoteers.wave.ArenaPoints.randomOther(game, caster.getLocation());
            if (dest != null) teleportAndSettle(caster, dest);
            return;
        }
        if (anchor != null && "random".equalsIgnoreCase(anchor.trim())) {
            double radius = params.getOrDefault(EffectKeys.RADIUS, 8.0);
            Location dest = TeleportSafety.randomSafeTarget(caster, radius, origin[0], origin[1]);
            if (dest != null) teleportAndSettle(caster, dest);
            return;
        }
        String t = params.get(EffectKeys.TARGETS);
        boolean toTarget = t != null
                && ("target".equalsIgnoreCase(t.trim()) || "hit_target".equalsIgnoreCase(t.trim()));
        LivingEntity anchorEnt = toTarget && ctx != null && ctx.hitTarget() != null ? ctx.hitTarget() : caster;
        double offsetY = params.getOrDefault(EffectKeys.OFFSET_Y, 2.0);
        Location dest = TeleportSafety.safeTarget(anchorEnt, offsetY, origin[0], origin[1]);
        if (dest != null) teleportAndSettle(caster, dest);
    }

    /**
     * 传送并"落地即定住"（2026-10-26 实测修正）。
     *
     * <p>为什么必须清速度：{@code teleport()} 之后实体**保留传送前的速度向量**，落地后还会按惯性
     * 滑出去 / 被残余速度带着"蹦跶"，看起来像"传送了但没站住"（实测：克雷松瞬移后会飘离落点）。
     * 这里额外清零速度与摔落距离——对位移类大招（Boss 瞬移、把玩家拽过来）都适用，且不改变位置语义。
     */
    private static void teleportAndSettle(LivingEntity caster, Location dest) {
        caster.teleport(dest);
        caster.setVelocity(new Vector(0, 0, 0));
        caster.setFallDistance(0f);
    }

    /** DISPLACE：把目标玩家水平拉向施法者（蹲下/创造/旁观豁免）。 */
    private static void executeDisplaceCore(LivingEntity caster, MaggoteersGame game,
                                            EffectContext params, TriggerContext ctx) {
        String t = params.get(EffectKeys.TARGETS);
        LivingEntity target = ctx != null ? ctx.hitTarget() : null;
        boolean toHitTarget = t != null
                && ("target".equalsIgnoreCase(t.trim()) || "hit_target".equalsIgnoreCase(t.trim()));
        Player victim = null;
        if (toHitTarget && target instanceof Player tp && !tp.isDead()) {
            victim = tp;
        } else {
            double radius = params.getOrDefault(EffectKeys.RADIUS, 8.0);
            victim = nearestPlayer(caster, radius);
        }
        if (victim == null || !victim.isValid() || victim.isDead()) return;
        if (victim.isSneaking()) return;
        GameMode gm = victim.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) return;
        Vector toMob = caster.getLocation().toVector().subtract(victim.getLocation().toVector()).setY(0);
        if (toMob.lengthSquared() < 0.01) return;
        toMob.normalize();
        double force = params.getOrDefault(EffectKeys.FORCE, 1.2);
        double up = params.getOrDefault(EffectKeys.UPWARD, 0.2);
        victim.setVelocity(toMob.multiply(force).setY(up));
        try {
            victim.getWorld().playSound(victim.getLocation(), Sound.ENTITY_BREEZE_JUMP, 1f, 0.8f);
        } catch (IllegalArgumentException ignored) { }
    }

    /** DISABLE_AI（mob 侧）：锁自身 AI。玩家侧仍走 executeDisableAi。 */
    private static void executeDisableAiCore(LivingEntity caster, MaggoteersGame game, EffectContext params) {
        int dur = params.getOrDefault(EffectKeys.DURATION_TICKS, 0);
        if (dur <= 0) return;
        MobAiLockRegistry.lock(caster, dur, game);
    }

    /** HEAL：players=全体冒险玩家 / area=半径内玩家+被追踪怪 / 单目标=self/target/hit_target/attacker。 */
    private static void executeHealCore(LivingEntity caster, MaggoteersGame game,
                                        EffectContext params, TriggerContext ctx) {
        Double amount = params.get(EffectKeys.AMOUNT);
        Double damage = params.get(EffectKeys.DAMAGE);
        Double radius = params.get(EffectKeys.RADIUS);
        if (isPlayers(params)) {
            for (Player pl : adventurePlayers(game)) {
                applyHealSingle(pl, amount, damage, caster);
            }
            return;
        }
        if (isArea(params) && radius != null && radius > 0) {
            // 同侧治疗（HealTargetPolicy）：玩家只奶玩家、怪物只奶被追踪怪。
            // 历史实现是「半径内所有冒险玩家 + 半径内所有被追踪怪」一并回血 —— 怪物施法会把玩家也奶了。
            healAlliesInRadius(caster, game, caster.getLocation(), radius,
                    AuraParams.includeSelf(params), amount, damage);
            return;
        }
        if (isAllies(params)) {
            // targets: allies —— 只治被追踪友军（含自己，include_self 默认 true）
            for (LivingEntity ally : mobAlliesInRadius(caster, params)) {
                applyHealSingle(ally, amount, damage, caster);
            }
            return;
        }
        applyHealSingle(resolveMobTarget(caster, ctx, params), amount, damage, caster);
    }

    /**
     * 范围治疗（HEAL 的 {@code targets: area} 与独立的 {@code HEAL_AREA} 共用）：
     * 只治疗"同侧"目标（玩家→玩家、怪物→被追踪怪），{@code includeSelf} 决定是否含施法者自身。
     */
    private static void healAlliesInRadius(LivingEntity caster, MaggoteersGame game, Location center,
                                           double radius, boolean includeSelf, Double amount, Double damage) {
        if (center == null || center.getWorld() == null) return;
        if ((amount == null || amount == 0) && (damage == null || damage <= 0)) return;
        boolean casterIsPlayer = caster instanceof Player;
        boolean selfHealed = false;
        for (Player pl : adventurePlayersInRadius(game, center, radius)) {
            if (!HealTargetPolicy.sameSide(casterIsPlayer, true)) continue;
            if (!includeSelf && pl.equals(caster)) continue;
            applyHealSingle(pl, amount, damage, caster);
            selfHealed |= pl.equals(caster);
        }
        for (LivingEntity le : trackedMobsInRadius(center, radius, caster)) {
            if (!HealTargetPolicy.sameSide(casterIsPlayer, false)) continue;
            applyHealSingle(le, amount, damage, caster);
        }
        if (includeSelf && !selfHealed) {
            applyHealSingle(caster, amount, damage, caster);
        }
    }

    /** DAMAGE：players=每人一次独立伤害 / 单目标=self/target/hit_target/attacker。 */
    private static void executeDamageCore(LivingEntity caster, MaggoteersGame game,
                                          EffectContext params, TriggerContext ctx) {
        double damage = params.getOrDefault(EffectKeys.DAMAGE, 0.0);
        if (damage <= 0) return;
        Runnable applyAll = () -> {
            if (isPlayers(params)) {
                for (Player pl : adventurePlayers(game)) {
                    MagicDamage.applyIndependent(pl, damage, caster);
                }
                return;
            }
            LivingEntity victim = resolveMobTarget(caster, ctx, params);
            if (victim == null || victim.isDead()) return;
            MagicDamage.applyIndependent(victim, damage, caster);
        };
        // 2026-10-28：触发式伤害不反向喂计数器
        if (ctx != null && ctx.fired() != null && caster instanceof Player pl) {
            MagicDamageContext.run(game, pl.getUniqueId(), applyAll);
        } else {
            applyAll.run();
        }
    }

    /** ADD_POTION / CLEAR_POTIONS（单目标：self/target/hit_target/attacker；{@code targets: players} = 全场玩家）。 */

    /**
     * 本局内"活着且非旁观"的玩家（2026-10-26）：Boss 的 {@code targets: players} 效果用它。
     * <p>不能用 {@code adventurePlayers}——它只认冒险模式，调试时（创造/生存）会被整体过滤，
     * 表现为"全场黑暗/全场药水完全不生效"。
     */
    private static List<Player> playersInGame(MaggoteersGame game) {
        if (game == null) return List.of();
        List<Player> out = new ArrayList<>();
        for (var pe : game.getPlayers()) {
            Player p = pe.player();
            if (p == null || p.isDead() || p.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue;
            out.add(p);
        }
        return out;
    }

    private static void executePotionCore(LivingEntity caster, EffectContext params, TriggerContext ctx) {
        List<PotionEffectType> clears = params.get(EffectKeys.CLEAR_POTIONS);
        if (isPlayersTargets(params)) {
            // 2026-10-26：怪物对"全场玩家"上药（哨兵大招的全场黑暗）
            PotionEffectType type = params.get(EffectKeys.POTION);
            if (type == null) {
                String name = params.get(EffectKeys.POTION_NAME);
                if (name != null) type = GameRegistries.potionEffect(name);
            }
            // 2026-10-26：允许"只清不挂"（type==null 但要 clear_potions）——阶段4 清掉阶段3 的黑暗靠它。
            int amp = params.getOrDefault(EffectKeys.AMP, 0);
            // 2026-10-26 修：duration_ticks: 0 在内容里表示"常驻"，这里原先 Math.max(1, 0) = 1 tick
            // ⇒ 阶段3 的全场黑暗/常驻药水一瞬就消失（实测"没有给玩家上黑暗"）。0 映射为无限。
            int rawDur = params.getOrDefault(EffectKeys.DURATION_TICKS, 60);
            int dur = rawDur <= 0 ? MOB_PERMANENT_POTION_TICKS : rawDur;
            var game = WaveEngine.gameOfEntity(caster.getUniqueId());
            for (Player pl : playersInGame(game instanceof MaggoteersGame mg ? mg : null)) {
                if (clears != null) clears.forEach(pl::removePotionEffect);
                if (type != null) pl.addPotionEffect(new PotionEffect(type, dur, amp, false, true));
            }
            return;
        }
        if (isAllies(params)) {
            // 2026-10-26：怪物给友军上药（bard 的攻速/力量、后续"给手下 buff"的词条）
            PotionEffectType type = params.get(EffectKeys.POTION);
            if (type == null) {
                String name = params.get(EffectKeys.POTION_NAME);
                if (name != null) type = GameRegistries.potionEffect(name);
            }
            if (type == null) return;
            int amp = params.getOrDefault(EffectKeys.AMP, 0);
            int dur = params.getOrDefault(EffectKeys.DURATION_TICKS, 0);
            if (dur <= 0) dur = MOB_PERMANENT_POTION_TICKS;
            for (LivingEntity ally : mobAlliesInRadius(caster, params)) {
                if (ally == null || ally.isDead()) continue;
                if (clears != null) clears.forEach(ally::removePotionEffect);
                ally.addPotionEffect(new PotionEffect(type, dur, amp, false, true));
            }
            return;
        }
        if (clears != null && !clears.isEmpty()) {
            LivingEntity victim = resolveMobTarget(caster, ctx, params);
            if (victim == null || victim.isDead()) return;
            for (PotionEffectType type : clears) {
                if (type != null) victim.removePotionEffect(type);
            }
            return;
        }
        LivingEntity victim = resolveMobTarget(caster, ctx, params);
        if (victim == null || victim.isDead()) return;
        PotionEffectType type = params.get(EffectKeys.POTION);
        if (type == null) {
            String name = params.get(EffectKeys.POTION_NAME);
            if (name != null) type = GameRegistries.potionEffect(name);
        }
        if (type == null) return;
        int amp = params.getOrDefault(EffectKeys.AMP, 0);
        int dur = params.getOrDefault(EffectKeys.DURATION_TICKS, 0);
        // stack_amp（2026-10-26）：每次施加在已有等级上 +1（永久叠层 debuff/增益：挖掘疲劳、力量…）
        if (Boolean.TRUE.equals(params.get(EffectKeys.STACK_AMP))) {
            PotionEffect existing = victim.getPotionEffect(type);
            if (existing != null) {
                amp = Math.min(255, Math.max(existing.getAmplifier() + 1, amp));
            }
        }
        if (dur <= 0) {
            // <=0 = 常驻：怪物用原版无限（NMS isInfiniteDuration），配合死亡/清波 detach 清理
            dur = MOB_PERMANENT_POTION_TICKS;
        }
        // ⚠️ 必须显式 ambient=false（Paper 26.2 起 3 参构造器默认 ambient=true，粒子会变成信标淡色且辅助 mod 不可见）
        victim.addPotionEffect(new PotionEffect(type, dur, amp, false, true));
    }

    /** ADD_ATTRIBUTE / PERMANENT_ATTRIBUTE：duration_ticks>0 = 临时 modifier；PERMANENT_ATTRIBUTE = 永久叠加。 */
    private static void executeAttributeCore(LivingEntity caster, Effect effect,
                                             EffectContext params, TriggerContext ctx) {
        if (isAllies(params)) {
            // 2026-10-26：怪物给友军加属性（bard）
            int dur = params.getOrDefault(EffectKeys.DURATION_TICKS, 0);
            for (LivingEntity ally : mobAlliesInRadius(caster, params)) {
                if (ally == null || ally.isDead()) continue;
                if (effect == Effect.PERMANENT_ATTRIBUTE) {
                    MobPermanentAttributeService.apply(ally, params);
                } else if (dur > 0) {
                    MobTempAttributeService.apply(ally, params, dur);
                }
            }
            return;
        }
        LivingEntity victim = resolveMobTarget(caster, ctx, params);
        if (victim == null || victim.isDead()) return;
        if (effect == Effect.PERMANENT_ATTRIBUTE) {
            MobPermanentAttributeService.apply(victim, params);
            return;
        }
        int dur = params.getOrDefault(EffectKeys.DURATION_TICKS, 0);
        if (dur <= 0) return;   // 常驻属性不在此效果（mob 由 coeff 承担；玩家走 PlayerState 派生）
        MobTempAttributeService.apply(victim, params, dur);
    }

    /** SUMMON（mob 侧）：玩家侧仍走 executeSummon。含 projectile 形态 + lightning 落雷分支。 */
    private static void executeSummonCore(LivingEntity caster, MaggoteersGame game,
                                          EffectContext params, TriggerContext ctx) {
        if (game == null) return;
        String name = params.get(EffectKeys.ENTITY);
        if (name == null || name.isBlank()) return;
        EntityType type = GameRegistries.entityType(name);
        if (type == null) return;
        int count = Math.max(1, params.getOrDefault(EffectKeys.COUNT, 1));
        double offsetY = params.getOrDefault(EffectKeys.OFFSET_Y, 0.0);
        Player atPlayer = null;
        String ts = params.get(EffectKeys.TARGETS);
        if (ts != null && isNearestPlayer(ts)) {
            double pr = params.getOrDefault(EffectKeys.RADIUS, 8.0);
            atPlayer = nearestPlayer(caster, pr);
            if (atPlayer == null || atPlayer.isDead() || !atPlayer.isValid()) return;
        }
        LivingEntity target = ctx != null ? ctx.hitTarget() : null;
        LivingEntity source = ctx != null ? ctx.attacker() : null;
        if (params.has(EffectKeys.PROJECTILE_SPEED) || params.get(EffectKeys.PROJECTILE) != null) {
            double speed = params.getOrDefault(EffectKeys.PROJECTILE_SPEED, 1.0);
            String homing = params.get(EffectKeys.HOMING_TARGET);
            String h = homing == null ? "" : homing.trim().toLowerCase(Locale.ROOT);
            LivingEntity init = null;
            if ("attack_target".equals(h)) init = target;
            else if ("damage_source".equals(h) || "killer".equals(h)) init = source;
            for (int i = 0; i < count; i++) {
                MobProjectileService.shoot(caster, game, type,
                        new SummonParams.ProjectileParams(speed), homing, init);
            }
            return;
        }
        // 2026-10-26（门扉缔造者）：召唤物支持 技能/体型/血量倍率 与逐个间隔生成
        int summonStart = Math.max(0, params.getOrDefault(EffectKeys.DELAY_TICKS, 0));
        int summonInterval = Math.max(0, params.getOrDefault(EffectKeys.INTERVAL_TICKS, 0));
        java.util.List<String> summonSkills = params.get(EffectKeys.SUMMON_SKILLS);
        double summonScale = params.getOrDefault(EffectKeys.SCALE, 1.0);
        double summonHp = params.getOrDefault(EffectKeys.HP_MULT, 1.0);
        for (int i = 0; i < count; i++) {
            final int idx = i;
            final Location at = atPlayer != null
                    ? atPlayer.getLocation().clone()
                    : caster.getLocation().clone().add(0, offsetY + 0.8 * i, 0);
            final EntityType fType = type;
            final LivingEntity fTarget = target;
            long delayTicks = (long) summonStart + (long) idx * summonInterval;
            Runnable spawnOne = () -> {
                if (at.getWorld() == null) return;
                if (fType == EntityType.LIGHTNING_BOLT) {
                    at.getWorld().strikeLightning(at);
                    return;
                }
                if (!(at.getWorld().spawnEntity(at, fType) instanceof LivingEntity spawned)) return;
                io.mczju.maggoteers.mob.SpiderClimbGuard.mark(spawned);   // 召唤的蜘蛛同样禁止爬墙
                if (summonScale != 1.0) {
                    var sc = spawned.getAttribute(org.bukkit.attribute.Attribute.SCALE);
                    if (sc != null) sc.setBaseValue(sc.getBaseValue() * summonScale);
                }
                if (summonHp != 1.0) {
                    var hp = spawned.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
                    if (hp != null) {
                        hp.setBaseValue(hp.getBaseValue() * summonHp);
                        spawned.setHealth(hp.getValue());
                    }
                }
                if (spawned instanceof org.bukkit.entity.Mob mobSpawned) {
                    mobSpawned.setTarget(fTarget instanceof org.bukkit.entity.Mob m ? m : null);
                }
                if (summonSkills != null && !summonSkills.isEmpty()) {
                    io.mczju.maggoteers.mob.MobSkillService.attach(spawned, summonSkills);
                }
            // D4（2026-10-26）：技能召唤的怪也登记进本波 livingMobs —— 否则它们不计入波次，
            // 波末 clearWorld 会直接清掉（"召唤了一堆小怪但什么都没发生"），且 Boss 召唤物存活时
            // 波次也可能提前判定清空。
                WaveEngine.trackSummoned(game, spawned);
            };
            if (delayTicks <= 0) spawnOne.run();
            else Bukkit.getScheduler().runTaskLater(MaggoteersPlugin.getInstance(), spawnOne, delayTicks);
        }
    }

    /** PARTICLE_RING：以 origin 为圆心绘制。duration_ticks>0 → ParticleLoopService 周期模式
     *  （key=预设名，周期=预设固定缺省 3s）；<=0（缺省）→ 本轮一次性（原行为不变）。 */
    private static void executeParticleRingCore(LivingEntity caster, EffectContext params, TriggerContext ctx) {
        if (caster.getWorld() == null) return;
        double radius = scaledRadius(caster, params.getOrDefault(EffectKeys.RADIUS, 5.0));
        Particle particle = resolveParticle(params.get(EffectKeys.PARTICLE_NAME));
        int count = params.getOrDefault(EffectKeys.COUNT, 32);
        Location center = resolveOrigin(caster, params, ctx);
        if (center == null || center.getWorld() == null) return;
        String presetRaw = params.get(EffectKeys.PRESET);
        boolean isShell = presetRaw != null && "SHIELD_SHELL".equalsIgnoreCase(presetRaw.trim());
        boolean shellLiftSelf = isShell && "self".equals(resolveOriginTag(params, ctx));
        Runnable oneShot;
        if (presetRaw != null && "SHIELD_AXES".equalsIgnoreCase(presetRaw.trim())) {
            oneShot = () -> ParticleEffects.playShieldAxes(center.getWorld(), center, radius, particle, count);
        } else if (isShell) {
            oneShot = () -> {
                Location c = resolveOrigin(caster, params, ctx);
                if (c == null || c.getWorld() == null) return;
                if (shellLiftSelf) c = c.add(0, caster.getHeight() * 0.5, 0);
                ParticleEffects.playShieldShell(c.getWorld(), c, radius, particle, count);
            };
        } else {
            oneShot = () -> ParticleEffects.playThickRing(
                    center.getWorld(), center.clone().add(0, 0.15, 0), radius, particle, count);
        }
        int durationTicks = params.getOrDefault(EffectKeys.DURATION_TICKS, 0);
        if (durationTicks > 0 && presetRaw != null) {
            ParticleLoopService.start(caster, presetRaw.trim().toUpperCase(Locale.ROOT),
                    ParticleLoopService.periodTicksForPreset(presetRaw), durationTicks, oneShot);
            return;
        }
        oneShot.run();
    }

    /** SOUND：向本局冒险玩家播放音效（位置取施法者）。 */
    private static void executeSoundCore(LivingEntity caster, MaggoteersGame game, EffectContext params) {
        String raw = params.get(EffectKeys.SOUND_NAME);
        if (raw == null || raw.isBlank()) return;
        Sound sound = GameRegistries.sound(raw);
        if (sound == null) return;
        float volume = params.getOrDefault(EffectKeys.SOUND_VOLUME, 1.0f);
        float pitch = params.getOrDefault(EffectKeys.SOUND_PITCH, 1.0f);
        if (game == null) return;
        Location at = caster.getLocation();
        for (Player pl : adventurePlayers(game)) {
            pl.playSound(at, sound, volume, pitch);
        }
    }

    /** COUNTER_ARM：武装 requireArm 计数器。 */
    private static void executeCounterArmCore(LivingEntity caster, EffectContext params) {
        String id = params.get(EffectKeys.COUNTER_ID);
        if (id == null || id.isBlank()) return;
        MobCounterRegistry.arm(caster, id);
    }

    // ===== 2026-10-26：Boss 读条大招 / 叠层 / 吞噬 / 场地传送 =====

    /**
     * DELAYED_AREA：读条型范围爆发。
     * <ol>
     *   <li>立刻在施法者与目标点各画一根"冲天粒子柱"、在目标点画半径环；</li>
     *   <li>读条期间**每 10 tick 刷新一次**环内玩家的移速削减（{@code slow_percent}/{@code slow_ticks}）
     *       与 {@code potion}——之前只在施法瞬间施加一次，玩家中途走进圈里完全没感觉；</li>
     *   <li>{@code delay_ticks} 后对环内玩家造成 {@code damage}，或按目标药水层数 × {@code per_level}；
     *       同一次落点再补一次 {@code potion}，并按 {@code clear_potions} 清掉指定药水（读条代价）。</li>
     * </ol>
     * {@code origin: other_boss} = 圆心取**另一个带 boss_bar 的 Boss**；双王之一死后没有可联动的 Boss
     * → 落点改为**最近的玩家**（用户口径）；再没有玩家则糊在自己脚下。见 {@link DelayedAreaOrigin}。
     */
    private static void executeDelayedAreaCore(LivingEntity caster, MaggoteersGame game, EffectContext params) {
        if (caster.getWorld() == null) return;
        double radius = params.getOrDefault(EffectKeys.RADIUS, 8.0);
        Particle particle = resolveParticle(params.get(EffectKeys.PARTICLE_NAME));
        String originTag = params.get(EffectKeys.ORIGIN);
        Location center = caster.getLocation().clone();
        if (originTag != null && "other_boss".equalsIgnoreCase(originTag.trim())) {
            center = resolveOtherBossOrPlayerCenter(caster, game);
        }
        // 粒子柱：施法者 + 圆心（两点相同时只画一根）
        drawPillar(caster.getLocation(), particle);
        if (center.distanceSquared(caster.getLocation()) > 0.25) {
            drawPillar(center, particle);
        }
        ParticleEffects.playThickRing(center.getWorld(), center.clone().add(0, 0.15, 0), radius, particle, 48);

        double slowPercent = params.getOrDefault(EffectKeys.SLOW_PERCENT, 0.0);
        int slowTicks = params.getOrDefault(EffectKeys.SLOW_TICKS, 60);
        PotionEffectType ringPotion = params.get(EffectKeys.POTION);
        if (ringPotion == null && params.get(EffectKeys.POTION_NAME) != null) {
            ringPotion = GameRegistries.potionEffect(params.get(EffectKeys.POTION_NAME));
        }
        int ringPotionAmp = params.getOrDefault(EffectKeys.AMP, 0);
        int ringPotionTicks = Math.max(1, params.getOrDefault(EffectKeys.DURATION_TICKS, 60));
        List<PotionEffectType> clears = params.get(EffectKeys.CLEAR_POTIONS);

        int delay = Math.max(1, params.getOrDefault(EffectKeys.DELAY_TICKS, 60));
        double flat = params.getOrDefault(EffectKeys.DAMAGE, 0.0);
        String scalePotion = params.get(EffectKeys.SCALE_BY_POTION);
        double perLevel = params.getOrDefault(EffectKeys.PER_LEVEL, 0.0);

        final Location ringCenter = center.clone();
        final PotionEffectType ringPot = ringPotion;      // ringPotion 上面有再赋值，lambda 里必须用 final 副本
        final int ringPotAmp = ringPotionAmp;
        final int ringPotTicks = ringPotionTicks;
        // 读条期间持续圈内效果（每 10 tick 一次，含 t=0 的第一次）
        if (slowPercent > 0 || ringPot != null) {
            final double sp = slowPercent;
            final int st = slowTicks;
            new BukkitRunnable() {
                int elapsed = 0;
                @Override public void run() {
                    if (game == null || ringCenter.getWorld() == null) { cancel(); return; }
                    for (Player pl : adventurePlayersInRadius(game, ringCenter, radius)) {
                        if (sp > 0) applySlow(pl, sp, st);
                        if (ringPot != null) {
                            pl.addPotionEffect(new PotionEffect(ringPot, ringPotTicks, ringPotAmp, false, true));
                        }
                    }
                    elapsed += 10;
                    if (elapsed >= delay) cancel();
                }
            }.runTaskTimer(MaggoteersPlugin.getInstance(), 0L, 10L);
        }

        final Location impact = center.clone();
        Bukkit.getScheduler().runTaskLater(MaggoteersPlugin.getInstance(), () -> {
            if (impact.getWorld() == null || game == null) return;
            for (Player pl : adventurePlayersInRadius(game, impact, radius)) {
                double dmg = flat;
                if (scalePotion != null && perLevel > 0) {
                    PotionEffectType type = GameRegistries.potionEffect(scalePotion);
                    PotionEffect eff = type == null ? null : pl.getPotionEffect(type);
                    int levels = eff == null ? 0 : eff.getAmplifier() + 1;
                    dmg = perLevel * levels;
                }
                if (dmg > 0) {
                    MagicDamage.applyIndependent(pl, dmg, caster.isValid() ? caster : null);
                }
                // 落点仍是"圈里"：补一次药水（黑暗等），再按 clear_potions 清掉读条代价
                if (ringPot != null) {
                    pl.addPotionEffect(new PotionEffect(ringPot, ringPotTicks, ringPotAmp, false, true));
                }
                if (clears != null) {
                    for (PotionEffectType type : clears) {
                        if (type != null) pl.removePotionEffect(type);
                    }
                }
            }
        }, delay);
    }

    /**
     * {@code origin: other_boss} 的圆心解析：**只认带 {@code boss_bar: true} 的被追踪怪**
     * （旧实现取"最近的另一个被追踪怪"，Boss 波里的疣猪兽/凋灵骷髅/猪灵蛮兵会把环抢走）。
     * 没有其它 Boss（双王之一已死）→ 最近的冒险玩家；都没有 → 施法者脚下。
     */
    private static Location resolveOtherBossOrPlayerCenter(LivingEntity caster, MaggoteersGame game) {
        UUID casterId = caster.getUniqueId();
        Location casterLoc = caster.getLocation();
        List<DelayedAreaOrigin.Candidate> bosses = new ArrayList<>();
        for (LivingEntity boss : WaveEngine.trackedBosses(game)) {
            if (boss == null || boss.isDead() || !boss.isValid()) continue;
            if (boss.getUniqueId().equals(casterId)) continue;
            bosses.add(new DelayedAreaOrigin.Candidate(boss.getUniqueId(),
                    boss.getLocation().distanceSquared(casterLoc)));
        }
        List<DelayedAreaOrigin.Candidate> players = new ArrayList<>();
        for (Player pl : adventurePlayers(game)) {
            players.add(new DelayedAreaOrigin.Candidate(pl.getUniqueId(),
                    pl.getLocation().distanceSquared(casterLoc)));
        }
        DelayedAreaOrigin.Choice choice = DelayedAreaOrigin.choose(casterId, bosses, players);
        switch (choice.kind()) {
            case OTHER_BOSS -> {
                Entity raw = Bukkit.getEntity(choice.uuid());
                if (raw instanceof LivingEntity other) return other.getLocation().clone();
            }
            case PLAYER -> {
                Player pl = Bukkit.getPlayer(choice.uuid());
                if (pl != null) return pl.getLocation().clone();
            }
            case SELF -> { }
        }
        return casterLoc.clone();
    }

    /**
     * FANG_PATTERN（2026-10-26）：唤魔尖牙法阵。
     *
     * <p>对**每个冒险玩家**在脚下生成一套法阵：先按预设逐格播预警粒子，到点判定
     * "该玩家的脚下方块 == 这一格"，命中则由本插件直接结算 {@code damage} 点**魔法**伤害
     * （伤害来源 = 施法者，走 {@link MagicDamage#applyIndependent}）——法阵不使用原版尖牙的伤害。
     *
     * <p>参数：{@code preset}（ring_out|ring_in|star）、{@code radius}（环状最大半径 6 / 星形延伸格数 10）、
     * {@code interval_ticks}（波间隔，默认 10）、{@code warn_ticks}（预警提前量，默认 10）、
     * {@code damage}（默认 20）、{@code particle}（默认 CRIT）。
     */
    private static void executeFangPatternCore(LivingEntity caster, MaggoteersGame game, EffectContext params) {
        if (caster.getWorld() == null || game == null) return;
        String preset = params.getOrDefault(EffectKeys.PRESET, "ring_out");
        int radius = (int) Math.round(params.getOrDefault(EffectKeys.RADIUS, 6.0));
        int interval = Math.max(1, params.getOrDefault(EffectKeys.INTERVAL_TICKS, 10));
        int warn = Math.max(0, params.getOrDefault(EffectKeys.WARN_TICKS, 10));
        double damage = params.getOrDefault(EffectKeys.DAMAGE, 20.0);
        Particle particle = resolveParticle(params.get(EffectKeys.PARTICLE_NAME));
        if (particle == null) particle = Particle.CRIT;

        // 2026-10-26：不能用 adventurePlayers（只认冒险模式）——调试/创造模式的玩家会被过滤掉，
        // 实测导致 players=0、整段法阵空转（只有施法特效、没有粒子和尖牙）。
        var players = new ArrayList<Player>();
        for (var pe : game.getPlayers()) {
            Player p2 = pe.player();
            if (p2 == null || p2.isDead() || p2.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue;
            players.add(p2);
        }
        for (Player pl : players) {
            if (pl == null || pl.isDead() || pl.getWorld() != caster.getWorld()) continue;
            Location base = pl.getLocation();
            final int bx = base.getBlockX();
            final int by = base.getBlockY();
            final int bz = base.getBlockZ();
            List<io.mczju.maggoteers.effect.FangPattern.Cell> cells =
                    io.mczju.maggoteers.effect.FangPattern.build(preset, radius, interval, warn);
            for (var cell : cells) {
                final int cx = bx + cell.dx();
                final int cz = bz + cell.dz();
                final var w = base.getWorld();
                final int cellTick = cell.tick();
                // 预警：按预设逐格亮起（无伤害）
                if (warn > 0) {
                    final Location warnAt = new Location(w, cx + 0.5, by + 0.15, cz + 0.5);
                    final Particle warnParticle = particle;
                    Bukkit.getScheduler().runTaskLater(MaggoteersPlugin.getInstance(), () -> {
                        w.spawnParticle(warnParticle, warnAt, 6, 0.25, 0.05, 0.25, 0.0);
                    }, Math.max(0, cellTick));
                }
                // 命中：站在该格上的玩家吃一次魔法伤害（含队友，符合"站别人阵里也挨打"）
                Bukkit.getScheduler().runTaskLater(MaggoteersPlugin.getInstance(), () -> {
                    Location at = new Location(w, cx + 0.5, by, cz + 0.5);
                    // 2026-10-26：先粒子预警、到点再生成**真·唤魔尖牙**（其自身伤害被
                    // ScriptedFangListener 取消，伤害仍由本插件按格结算，见下）
                    if (w.getNearbyEntities(at, 1.2, 1.2, 1.2).stream()
                            .noneMatch(en -> en.getType() == EntityType.EVOKER_FANGS)) {
                        if (w.spawnEntity(at, EntityType.EVOKER_FANGS) instanceof org.bukkit.entity.EvokerFangs fang) {
                            fang.setMetadata("maggoteers_scripted_fang", new org.bukkit.metadata.FixedMetadataValue(
                                    MaggoteersPlugin.getInstance(), true));
                        }
                    }
                    w.spawnParticle(Particle.SWEEP_ATTACK, at, 1, 0.0, 0.0, 0.0, 0.0);
                    int hits = 0;
                    for (var pe2 : game.getPlayers()) {
                        Player victim = pe2.player();
                        if (victim == null || victim.isDead() || victim.getWorld() != w) continue;
                        Location v = victim.getLocation();
                        if (v.getBlockX() != cx || v.getBlockZ() != cz) continue;
                        if (Math.abs(v.getBlockY() - by) > 1) continue;
                        MagicDamage.applyIndependent(victim, damage, caster.isValid() ? caster : null);
                        hits++;
                    }
                }, Math.max(1, cellTick + warn));
            }
        }
    }


    /**
     * STEAL_ITEM（thief，2026-10-26）：夺走目标主手武器，**掉在施法者脚下**（用户口径）。
     * 目标为空手时什么也不做。
     */
    private static void executeStealItemCore(LivingEntity caster, EffectContext params, TriggerContext ctx) {
        LivingEntity victim = resolveMobTarget(caster, ctx, params);
        if (!(victim instanceof Player pl) || pl.isDead()) return;
        org.bukkit.inventory.ItemStack hand = pl.getInventory().getItemInMainHand();
        if (hand == null || hand.getType().isAir()) return;
        pl.getInventory().setItemInMainHand(new org.bukkit.inventory.ItemStack(org.bukkit.Material.AIR));
        pl.getWorld().dropItemNaturally(caster.getLocation(), hand);
        pl.playSound(pl.getLocation(), org.bukkit.Sound.ENTITY_ITEM_BREAK, 0.8f, 1.2f);
        pl.sendActionBar(net.kyori.adventure.text.Component.text("你的武器被夺走了！",
                net.kyori.adventure.text.format.NamedTextColor.RED));
    }

    /**
     * CURSE_PLAYERS（2026-10-26，门扉缔造者阶段 4）：给**全场冒险玩家**挂一条常驻负面，
     * 走 PlayerState（{@code Effect.ADD_ATTRIBUTE} + {@code stack: ADD}），而不是直接改 Bukkit 属性——
     * 否则下一次 {@code resyncDerived} 会把它冲掉。
     *
     * <p>参数：{@code attr} / {@code op} / {@code value}（与奖励的 ADD_ATTRIBUTE 完全一致），
     * 例如 {@code attr: MAX_HEALTH, op: PERCENT, value: -0.5}（扣 50% 生命上限）、
     * {@code attr: COOLDOWN_COEFFICIENT, op: ADD, value: 0.5}（冷却系数 +50）。
     */
    private static void executeCursePlayersCore(LivingEntity caster, MaggoteersGame game, EffectContext params) {
        if (game == null || params == null) return;
        if (params.get(EffectKeys.ATTR) == null && params.get(EffectKeys.ATTR_NAME) == null) return;
        String key = params.get(EffectKeys.ATTR_NAME);
        String id = "curse:" + (key == null ? "attr" : key.toLowerCase(java.util.Locale.ROOT))
                + ":" + System.currentTimeMillis();
        for (Player pl : adventurePlayers(game)) {
            if (pl == null || pl.isDead()) continue;
            PlayerEffect pe = new PlayerEffect(id, Effect.ADD_ATTRIBUTE, params,
                    null, null, 0, 0, null, io.mczju.maggoteers.effect.Stack.ADD, 0, 0);
            apply(pl, pe, game);
        }
    }

    /** STUN：定身（移速/跳跃归零）。{@code targets: players} = 全场；否则按 {@code radius} 范围。 */
    private static void executeStunCore(LivingEntity caster, MaggoteersGame game, EffectContext params) {
        int ticks = Math.max(1, params.getOrDefault(EffectKeys.DURATION_TICKS, 20));
        List<Player> targets;
        if (isPlayersTargets(params)) {
            targets = adventurePlayers(game);
        } else {
            double radius = params.getOrDefault(EffectKeys.RADIUS, 8.0);
            targets = adventurePlayersInRadius(game, caster.getLocation(), radius);
        }
        for (Player pl : targets) {
            stun(pl, ticks);
        }
    }

    /**
     * DEVOUR：吞噬半径内最近的同类型被追踪怪（静默移除、不掉落），回复 {@code amount} 血，
     * 并获得 1 层 {@code gain_potion}（叠加等级，伊祖米克的"吞噬史莱姆长力量"）。
     */
    private static void executeDevourCore(LivingEntity caster, MaggoteersGame game, EffectContext params) {
        if (game == null) return;
        String typeName = params.get(EffectKeys.ENTITY);
        double radius = params.getOrDefault(EffectKeys.RADIUS, 6.0);
        LivingEntity victim = nearestTrackedOfType(caster, radius, typeName);
        if (victim == null) return;
        WaveEngine.devourTracked(game, victim);
        double heal = params.getOrDefault(EffectKeys.AMOUNT, 0.0);
        if (heal > 0) {
            AttributeInstance max = caster.getAttribute(Attribute.MAX_HEALTH);
            double cap = max == null ? caster.getHealth() : max.getValue();
            caster.setHealth(Math.min(cap, caster.getHealth() + heal));
        }
        String gain = params.get(EffectKeys.GAIN_POTION);
        if (gain != null && !gain.isBlank()) {
            PotionEffectType type = GameRegistries.potionEffect(gain);
            if (type != null) {
                int amp = 0;
                PotionEffect existing = caster.getPotionEffect(type);
                if (existing != null) amp = Math.min(255, existing.getAmplifier() + 1);
                caster.addPotionEffect(new PotionEffect(type, MOB_PERMANENT_POTION_TICKS, amp, false, true));
            }
        }
    }

    /**
     * CONSUME_STACKS：消耗自身 {@code gain_potion} 的全部层数，每层对全场玩家施加
     * {@code per_stack_ticks} 的晕眩（伊祖米克的读条大招）。
     *
     * <p>2026-10-26 用户口径：**即使一层都没有，也保底 2 秒（40 tick）眩晕**——
     * 否则"读条大招打空"完全没有惩罚，玩家只要把史莱姆清光就白拿。
     */
    private static void executeConsumeStacksCore(LivingEntity caster, MaggoteersGame game, EffectContext params) {
        if (game == null) return;
        String potionName = params.get(EffectKeys.GAIN_POTION);
        if (potionName == null || potionName.isBlank()) potionName = params.get(EffectKeys.POTION_NAME);
        PotionEffectType type = potionName == null ? null : GameRegistries.potionEffect(potionName);
        if (type == null) return;
        PotionEffect existing = caster.getPotionEffect(type);
        int levels = existing == null ? 0 : existing.getAmplifier() + 1;
        caster.removePotionEffect(type);
        double radius = params.getOrDefault(EffectKeys.RADIUS, 8.0);
        ParticleEffects.playThickRing(caster.getWorld(), caster.getLocation().clone().add(0, 0.15, 0),
                radius, resolveParticle(params.get(EffectKeys.PARTICLE_NAME)), 64);
        int perStack = Math.max(1, params.getOrDefault(EffectKeys.PER_STACK_TICKS, 20));
        int ticks = consumeStacksStunTicks(levels, perStack);
        for (Player pl : adventurePlayers(game)) {
            stun(pl, ticks);
        }
    }

    /** 保底 2 秒眩晕（0 层也有），其余按层数线性。纯函数，便于单测。 */
    static int consumeStacksStunTicks(int levels, int perStackTicks) {
        int per = Math.max(1, perStackTicks);
        return Math.max(40, Math.max(0, levels) * per);
    }

    /**
     * FREEZE：把目标置入 powder-snow 冻结（`setFreezeTicks`；≥140 tick 会持续掉血并大幅减速）。
     * 用于"寒渊惜别"的 refrigerate 词条（2026-10-26 用户要求从"缓慢"改为"冻结"）。
     * <p>2026-10-28：新增 {@code targets: enemies} + {@code radius}/{@code enemy_scope}（范围寒冷），
     * 供凝结核「全场寒冷」、赫拉芬格「3 格寒冷」这类武器/藏品使用；单体分支默认 {@code hit_target}。
     */
    private static void executeFreezeCore(LivingEntity caster, MaggoteersGame game,
                                          EffectContext params, TriggerContext ctx) {
        int ticks = Math.max(1, params.getOrDefault(EffectKeys.DURATION_TICKS, 200));
        String targets = params.get(EffectKeys.TARGETS);
        String norm = targets == null ? "hit_target" : targets.trim().toLowerCase(Locale.ROOT);
        if ("players".equals(norm)) {
            for (Player pl : adventurePlayers(game)) {
                freeze(pl, ticks);
            }
            return;
        }
        if ("enemies".equals(norm)) {
            Location center = resolveOrigin(caster, params, ctx);
            if (center == null) return;
            double radius = scaledRadius(caster, params.getOrDefault(EffectKeys.RADIUS, 5.0));
            for (LivingEntity le : TargetResolver.collect(game, caster, center, radius, Effect.FREEZE, params)) {
                freeze(le, ticks);
            }
            return;
        }
        if ("allies".equals(norm)) {
            for (LivingEntity le : mobAlliesInRadius(caster, params)) {
                freeze(le, ticks);
            }
            return;
        }
        LivingEntity victim = switch (norm) {
            case "self" -> caster;
            case "attacker" -> ctx != null && ctx.attacker() != null ? ctx.attacker() : caster;
            default -> ctx != null && ctx.hitTarget() != null ? ctx.hitTarget() : caster;
        };
        freeze(victim, ticks);
    }

    private static void freeze(LivingEntity victim, int ticks) {
        // 2026-10-29：一次性 setFreezeTicks 会被 vanilla 每 tick 扣 2，10 秒实际只看得见一两秒；
        // 统一交给 FreezeService 持续续冻（寒霜领域这类 AURA 本来就每秒刷新，现在所有来源一致）。
        FreezeService.apply(victim, ticks);
    }

    /**
     * 2026-10-29：**扇形打击（CONE）**。
     * <p>半径吃 RANGE、张角不吃（用户口径）；几何判定见 {@link ConeMath#inCone}。
     * <p>{@code targets}: {@code enemies}（默认；伤害 + 药水 + 冻结 + 点燃）/
     * {@code allies}（治疗 + 药水，{@code include_self} 可含自己）/ {@code both}（敌人吃伤害 + 冻结 + 点燃，
     * 友军吃治疗 + 药水）——沧溟星梦"一个扇形搞定伤害/治疗/伤害吸收"就是 both。
     */
    private static void executeConeCore(LivingEntity caster, MaggoteersGame game,
                                        EffectContext params, TriggerContext ctx, MagicUseFx weaponFx) {
        if (caster == null || caster.getWorld() == null) return;
        Location origin = caster.getEyeLocation();
        if (origin.getWorld() == null) return;
        Vector dir = origin.getDirection();
        if (!(caster instanceof Player)) {
            LivingEntity aimAt = ctx != null && ctx.hitTarget() != null ? ctx.hitTarget()
                    : (ctx != null ? ctx.attacker() : null);
            if (aimAt != null && aimAt.getWorld() != null && aimAt.getWorld().equals(origin.getWorld())) {
                dir = aimAt.getLocation().add(0, aimAt.getHeight() * 0.5, 0).toVector()
                        .subtract(origin.toVector());
            }
        }
        if (dir == null || dir.lengthSquared() < 1e-9) return;
        dir = dir.clone().normalize();

        double radius = scaledRadius(caster, params.getOrDefault(EffectKeys.RADIUS, 8.0));
        double angle = Math.max(1.0, Math.min(360.0, params.getOrDefault(EffectKeys.ANGLE, 60.0)));
        double dmg = params.getOrDefault(EffectKeys.DAMAGE, 0.0);
        double healAmount = params.getOrDefault(EffectKeys.HEAL_AMOUNT, 0.0);
        int freezeTicks = params.getOrDefault(EffectKeys.FREEZE_TICKS, 0);
        int igniteTicks = params.getOrDefault(EffectKeys.IGNITE_TICKS, 0);
        List<BuffPotionSpec> potions = params.get(EffectKeys.POTIONS);
        String targets = params.getOrDefault(EffectKeys.TARGETS, "enemies").trim().toLowerCase(Locale.ROOT);
        boolean toAlliesOnly = "allies".equals(targets);
        boolean toBoth = "both".equals(targets) || "all".equals(targets);
        boolean toEnemies = !toAlliesOnly;

        MagicUseFx coneFx = weaponFx;
        if (coneFx == null) {
            EffectContext fxCtx = params.get(EffectKeys.FX);
            coneFx = fxCtx != null ? MagicFxBuilder.fromContext(fxCtx, MagicUseFx.defaults())
                    : MagicFxConfig.currentDefaults();
        }
        MagicFxPresets.playConeAlong(origin, dir, radius, angle, coneFx,
                params.getOrDefault(EffectKeys.OFFSET_Y, 0.0));

        if (toEnemies) {
            EffectContext enemyParams = params.copy();
            enemyParams.put(EffectKeys.TARGETS, AuraParams.TARGET_ENEMIES);
            List<LivingEntity> candidates;
            // 与 DAMAGE_AREA 同口径：怪物施法打冒险玩家，玩家武器打敌对怪
            if (!(caster instanceof Player)) {
                candidates = new ArrayList<>(adventurePlayersInRadius(game, origin, radius));
            } else {
                candidates = TargetResolver.collect(game, caster, origin, radius, Effect.CONE, enemyParams);
            }
            for (LivingEntity le : candidates) {
                if (le == null || le.isDead() || !le.isValid()) continue;
                if (!ConeMath.inCone(origin, dir, angle, radius, centerOf(le))) continue;
                if (dmg > 0) MagicDamage.applyIndependent(le, dmg, caster);
                if (!le.isValid() || le.isDead()) continue;
                if (!toBoth) applyConePotions(le, potions);
                if (freezeTicks > 0) freeze(le, freezeTicks);     // 走 FreezeService 续冻，否则立刻衰减
                if (igniteTicks > 0) ignite(le, igniteTicks);
            }
        }
        if ((toAlliesOnly || toBoth) && game != null) {
            double healAmt = healAmount > 0 ? scaledHealAmount(caster, healAmount) : 0;
            boolean includeSelf = AuraParams.includeSelf(params);
            List<Player> allies = new ArrayList<>();
            if (caster instanceof Player self && includeSelf
                    && ConeMath.inCone(origin, dir, angle, radius, centerOf(self))) {
                allies.add(self);
            }
            if (caster instanceof Player casterPlayer) {
                for (Player ally : AllyTargeting.alliesInRadius(game, casterPlayer, radius, false)) {
                    if (allies.contains(ally)) continue;
                    if (ConeMath.inCone(origin, dir, angle, radius, centerOf(ally))) allies.add(ally);
                }
            }
            for (Player ally : allies) {
                if (healAmt > 0) heal(ally, healAmt);
                applyConePotions(ally, potions);
            }
        }
    }

    /** Core 通道（怪物施法 / 奖励触发）没有武器 fx，粒子退回流式 fx 或默认预设。 */
    private static void executeConeCore(LivingEntity caster, MaggoteersGame game,
                                        EffectContext params, TriggerContext ctx) {
        executeConeCore(caster, game, params, ctx, null);
    }

    private static Location centerOf(LivingEntity le) {
        return le.getLocation().add(0, le.getHeight() * 0.5, 0);
    }

    private static void applyConePotions(LivingEntity le, List<BuffPotionSpec> potions) {
        if (potions == null || potions.isEmpty()) return;
        for (BuffPotionSpec spec : potions) {
            if (spec == null || spec.type() == null) continue;
            PotionMerge.applyIfNeeded(le, spec.type(), spec.amp(), spec.durationTicks());
        }
    }

    /**
     * 2026-10-29（用户口径）：**光束撞墙即停，不再穿墙**——玩家武器光束与怪物光束共用。
     * <p>{@code rayTraceBlocks} 找第一个不透明方块（忽略草/花等可穿过方块与流体），
     * 把长度夹到命中点前 0.2 格；没有命中就返回原长度。
     */
    static double clampBeamByWalls(Location eye, Vector dir, double length) {
        if (eye == null || eye.getWorld() == null || dir == null || length <= 0) return length;
        Vector d = dir.clone();
        if (d.lengthSquared() < 1e-9) return length;
        d.normalize();
        org.bukkit.util.RayTraceResult hit = eye.getWorld().rayTraceBlocks(
                eye, d, length, org.bukkit.FluidCollisionMode.NEVER, true);
        if (hit == null || hit.getHitPosition() == null) return length;
        double dist = hit.getHitPosition().distance(eye.toVector());
        return Math.max(0.6, dist - 0.2);
    }

    /**
     * 2026-10-29：**IGNITE**（点燃）。targets 语义与 FREEZE 一致：
     * 默认 {@code hit_target}；{@code enemies} + {@code radius} 走范围点燃（活化火石光环的武器侧等价物）。
     */
    private static void executeIgniteCore(LivingEntity caster, MaggoteersGame game,
                                          EffectContext params, TriggerContext ctx) {
        int ticks = Math.max(20, params.getOrDefault(EffectKeys.IGNITE_TICKS, 60));
        String norm = String.valueOf(params.getOrDefault(EffectKeys.TARGETS, "hit_target"))
                .trim().toLowerCase(Locale.ROOT);
        if ("enemies".equals(norm)) {
            Location center = resolveOrigin(caster, params, ctx);
            if (center == null) return;
            double radius = scaledRadius(caster, params.getOrDefault(EffectKeys.RADIUS, 5.0));
            List<LivingEntity> targets = caster instanceof Player
                    ? TargetResolver.collect(game, caster, center, radius, Effect.IGNITE, params)
                    : new ArrayList<>(adventurePlayersInRadius(game, center, radius));
            for (LivingEntity le : targets) ignite(le, ticks);
            return;
        }
        LivingEntity victim = switch (norm) {
            case "self" -> caster;
            case "attacker" -> ctx != null && ctx.attacker() != null ? ctx.attacker() : caster;
            default -> ctx != null && ctx.hitTarget() != null ? ctx.hitTarget() : caster;
        };
        ignite(victim, ticks);
    }

    private static void ignite(LivingEntity victim, int ticks) {
        if (victim == null || victim.isDead()) return;
        if (victim.getFireTicks() < ticks) victim.setFireTicks(ticks);
    }

    /** 冲天粒子柱（视觉预告，不造成伤害）。 */
    private static void drawPillar(Location base, Particle particle) {        if (base == null || base.getWorld() == null) return;
        for (int i = 0; i < 24; i++) {
            Location at = base.clone().add(0, 0.6 + i * 0.6, 0);
            base.getWorld().spawnParticle(particle, at, 2, 0.08, 0.08, 0.08, 0.0);
        }
    }

    /** 移速削减（mul）：{@code percent=0.5} → -50%；到期自动移除（玩家下线时随实体销毁，无需持久化）。 */
    private static void applySlow(Player pl, double percent, int ticks) {
        AttributeInstance speed = pl.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed == null) return;
        NamespacedKey key = new NamespacedKey(MaggoteersPlugin.getInstance(), "delayed_area_slow");
        removeModifier(speed, key);
        speed.addTransientModifier(new AttributeModifier(key, -Math.min(0.95, percent),
                AttributeModifier.Operation.MULTIPLY_SCALAR_1));
        Bukkit.getScheduler().runTaskLater(MaggoteersPlugin.getInstance(), () -> {
            AttributeInstance cur = pl.getAttribute(Attribute.MOVEMENT_SPEED);
            if (cur != null) removeModifier(cur, key);
        }, Math.max(1, ticks));
    }

    private static void removeModifier(AttributeInstance inst, NamespacedKey key) {
        for (AttributeModifier m : new ArrayList<>(inst.getModifiers())) {
            if (m.getKey().equals(key)) inst.removeModifier(m);
        }
    }

    /** 晕眩：移速清零 + 无法跳跃（跳跃提升 amp 128 = 反向）。 */
    private static void stun(Player pl, int ticks) {
        pl.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, Math.max(1, ticks), 250, false, true));
        pl.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, Math.max(1, ticks), 128, false, false));
    }

    private static boolean isPlayersTargets(EffectContext p) {
        String t = p.get(EffectKeys.TARGETS);
        return t != null && "players".equalsIgnoreCase(t.trim());
    }

    /** 半径内最近的"另一个被追踪怪"（排除自身；迈入永恒双 Boss 联动用）。 */
    private static LivingEntity nearestOtherTracked(LivingEntity caster, double radius) {
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (LivingEntity le : trackedMobsInRadius(caster.getLocation(), radius, caster)) {
            double d = le.getLocation().distanceSquared(caster.getLocation());
            if (d < bestDist) {
                bestDist = d;
                best = le;
            }
        }
        return best;
    }

    /** 半径内最近的、类型匹配的被追踪怪（DEVOUR 用）。 */
    private static LivingEntity nearestTrackedOfType(LivingEntity caster, double radius, String typeName) {
        EntityType want = typeName == null ? null : GameRegistries.entityType(typeName);
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (LivingEntity le : trackedMobsInRadius(caster.getLocation(), radius, caster)) {
            if (want != null && le.getType() != want) continue;
            double d = le.getLocation().distanceSquared(caster.getLocation());
            if (d < bestDist) {
                bestDist = d;
                best = le;
            }
        }
        return best;
    }

    // —— mob 效果执行辅助（由 MobEffectExecutor 迁移而来）——

    /** 单目标解析：self/缺省→caster；target/hit_target→ctx.hitTarget（null 回落 caster）；attacker→ctx.attacker。 */
    private static LivingEntity resolveMobTarget(LivingEntity caster, TriggerContext ctx, EffectContext params) {
        String t = params.get(EffectKeys.TARGETS);
        if (t == null) return caster;
        String norm = t.trim().toLowerCase(Locale.ROOT);
        LivingEntity hitTarget = ctx != null ? ctx.hitTarget() : null;
        LivingEntity attacker = ctx != null ? ctx.attacker() : null;
        return switch (norm) {
            case "target", "hit_target" -> hitTarget != null && !hitTarget.isDead() ? hitTarget : caster;
            case "attacker" -> attacker != null && !attacker.isDead() ? attacker : caster;
            default -> caster;
        };
    }

    /** HEAL 单体结算：amount>0 治疗（含死亡复活路径）、amount<0 精确扣血、damage>0 独立伤害。 */
    private static void applyHealSingle(LivingEntity victim, Double amount, Double damage, LivingEntity damager) {
        if (victim == null) return;
        if (amount != null && amount > 0 && damager != null) {
            amount = scaledHealAmount(damager, amount);
        }
        if (amount != null && amount > 0 && victim.isDead()) {
            victim.setHealth(Math.min(victim.getMaxHealth(), victim.getHealth() + amount));
            return;
        }
        if (victim.isDead()) return;
        if (amount != null) {
            if (amount > 0) victim.setHealth(Math.min(victim.getMaxHealth(), victim.getHealth() + amount));
            else victim.damage(-amount, victim);
        } else if (damage != null && damage > 0) {
            victim.damage(damage, damager != null ? damager : victim);
        }
    }

    private static boolean isPlayers(EffectContext p) {
        String t = p.get(EffectKeys.TARGETS);
        return t != null && "players".equalsIgnoreCase(t.trim());
    }

    private static boolean isArea(EffectContext p) {
        String t = p.get(EffectKeys.TARGETS);
        return t != null && "area".equalsIgnoreCase(t.trim());
    }

    /** {@code targets: allies} —— 怪物给半径内的被追踪友军（含自己，默认 include_self=true）上效果。 */
    private static boolean isAllies(EffectContext p) {
        String t = p.get(EffectKeys.TARGETS);
        return t != null && "allies".equalsIgnoreCase(t.trim());
    }

    /** 怪物友军集合：半径内被追踪怪 + （include_self 时）自己。 */
    private static List<LivingEntity> mobAlliesInRadius(LivingEntity caster, EffectContext params) {
        double radius = params.getOrDefault(EffectKeys.RADIUS, 12.0);
        List<LivingEntity> out = new ArrayList<>(trackedMobsInRadius(caster.getLocation(), radius, caster));
        if (AuraParams.includeSelf(params)) out.add(caster);
        return out;
    }

    /** targets 是否"最近玩家"（旧 player / 新 nearest_player）。 */
    private static boolean isNearestPlayer(String targets) {
        String t = targets.trim().toLowerCase(Locale.ROOT);
        return "player".equals(t) || "nearest_player".equals(t);
    }

    private static List<Player> adventurePlayers(MaggoteersGame game) {
        List<Player> out = new ArrayList<>();
        if (game == null) return out;
        for (var pe : game.getPlayers()) {
            Player pl = pe.player();
            if (pl == null || !pl.isValid() || pl.isDead()) continue;
            if (pl.getGameMode() != GameMode.ADVENTURE) continue;
            out.add(pl);
        }
        return out;
    }

    private static List<Player> adventurePlayersInRadius(MaggoteersGame game, Location center, double radius) {
        if (center == null || center.getWorld() == null) return List.of();
        double rsq = radius * radius;
        List<Player> out = new ArrayList<>();
        for (Player pl : adventurePlayers(game)) {
            if (!pl.getWorld().equals(center.getWorld())) continue;
            if (pl.getLocation().distanceSquared(center) <= rsq) out.add(pl);
        }
        return out;
    }

    /** mob 半径内最近的存活玩家。 */
    private static Player nearestPlayer(LivingEntity mob, double radius) {
        Player best = null;
        double bestDist = Double.MAX_VALUE;
        if (mob.getWorld() == null) return null;
        for (Entity en : mob.getWorld().getNearbyEntities(mob.getLocation(), radius, radius, radius)) {
            if (!(en instanceof Player pl) || !pl.isValid() || pl.isDead()) continue;
            double d = pl.getLocation().distanceSquared(mob.getLocation());
            if (d < bestDist) {
                bestDist = d;
                best = pl;
            }
        }
        return best;
    }

    /** mob 半径内敌对实体（非玩家 + WaveEngine 追踪怪，排除自身）。 */
    private static List<LivingEntity> nearbyHostiles(LivingEntity mob, double radius) {
        return trackedMobsInRadius(mob.getLocation(), radius, mob);
    }

    /** 以 center 为圆心取半径内的"被追踪怪"（不含玩家、不含 exclude 自身）。 */
    private static List<LivingEntity> trackedMobsInRadius(Location center, double radius, LivingEntity exclude) {
        List<LivingEntity> out = new ArrayList<>();
        if (center == null || center.getWorld() == null) return out;
        for (Entity en : center.getWorld().getNearbyEntities(center, radius, radius, radius)) {
            if (!(en instanceof LivingEntity le) || en instanceof Player) continue;
            if (en.equals(exclude)) continue;
            if (!WaveEngine.isTracked(le.getUniqueId())) continue;
            out.add(le);
        }
        return out;
    }

    private static Particle resolveParticle(String name) {
        Particle p = GameRegistries.particle(name);
        return p != null ? p : Particle.END_ROD;
    }

    /** 当前层原点 x/z（Act2/3 非 (0,0)）；无 cursor/无对局回落 (0,0)。 */
    private static double[] layerOriginXZ(MaggoteersGame game) {
        if (game == null) return new double[]{0, 0};
        int[] prog = WaveScheduler.progress(game);
        List<ActPlan> acts = game.getPlannedActs();
        if (acts != null && prog[0] >= 0 && prog[0] < acts.size()) {
            Vec3 spawn = acts.get(prog[0]).playerSpawn();
            return new double[]{spawn.x(), spawn.z()};
        }
        return new double[]{0, 0};
    }

    /** 喀戎之引：玩家是否处于「不再获得普通货币」状态。 */
    public static boolean isCurrencyLocked(Player p) {
        if (p == null) return false;
        var st = PlayerStateManager.get(PlayerStateManager.gameOf(p), p.getUniqueId());
        return isCurrencyLocked(st);
    }

    public static boolean isCurrencyLocked(PlayerState st) {
        if (st == null) return false;
        for (PlayerEffect e : st.effects()) {
            if (e.effect() == Effect.CURRENCY_LOCK && e.isPermanent()) return true;
        }
        return false;
    }

    /** 2026-10-28：票券附带的"范围强力击"——只在消耗票券的那一刀执行。 */
    /**
     * 2026-10-28：蓄力那一刀的范围强力击。
     * <p>为什么不走 {@code executeEffectCore(DAMAGE_AREA)} 了：那条路要经过 {@code TargetResolver} + {@code resolveOrigin}，
     * 而下一 tick 目标很可能已经被这一刀打死；一旦拿不到 hitTarget，圆心会退化到玩家脚下 ⇒
     * 表现为"有特效、没伤害"。这里直接用**命中坐标**收集半径内的被追踪怪并打独立伤害。
     */
    public static void castBonusAreas(Player p, MaggoteersGame game,
                                      java.util.List<EffectContext> bonuses, LivingEntity victim) {
        if (p == null || game == null || bonuses == null || bonuses.isEmpty()) return;
        final org.bukkit.Location hitLoc = victim != null ? victim.getLocation().clone() : null;
        if (hitLoc == null) return;
        java.util.UUID victimId = victim.getUniqueId();
        java.util.List<EffectContext> copy = new java.util.ArrayList<>(bonuses);
        // 2026-10-28：延到下一 tick，先让主伤害（200%）落地，避免抢断/取消主伤害事件。
        org.bukkit.Bukkit.getScheduler().runTask(
                io.mczju.maggoteers.MaggoteersPlugin.getInstance(), () -> {
                    Player online = p.isOnline() ? p : org.bukkit.Bukkit.getPlayer(p.getUniqueId());
                    if (online == null) return;
                    boolean debug = io.mczju.maggoteers.MaggoteersPlugin.getInstance()
                            .getConfig().getBoolean("debug.enabled", false);
                    for (EffectContext bonus : copy) {
                        double radius = bonus.getOrDefault(EffectKeys.RADIUS, 3.0) * rangeMultiplierOf(online);
                        double dmg = bonus.getOrDefault(EffectKeys.DAMAGE, 0.0);
                        if (dmg <= 0 || radius <= 0) continue;
                        io.mczju.maggoteers.util.ParticleEffects.playAreaRing(hitLoc.getWorld(), hitLoc, radius);
                        java.util.Set<java.util.UUID> hit = new java.util.HashSet<>();
                        LivingEntity direct = null;
                        var raw = org.bukkit.Bukkit.getEntity(victimId);
                        if (raw instanceof LivingEntity le && le.isValid() && !le.isDead()) direct = le;
                        java.util.List<LivingEntity> targets = new java.util.ArrayList<>();
                        if (direct != null) targets.add(direct);
                        for (LivingEntity le : trackedMobsInRadius(hitLoc, radius, online)) {
                            targets.add(le);   // 去重留给下面的伤害循环（否则这里 put 过就会被 continue）
                        }
                        int n = 0;
                        int[] applied = {0};
                        // 2026-10-28：Q 的范围伤害也是"事件触发的伤害"，不能再回喂蓄力计数器。
                        MagicDamageContext.run(game, online.getUniqueId(), () -> {
                            for (LivingEntity le : targets) {
                                if (le == null || le.isDead() || !le.isValid()) continue;
                                if (!hit.add(le.getUniqueId())) continue;
                                MagicDamage.applyIndependent(le, dmg, online);
                                applied[0]++;
                            }
                        });
                        n = applied[0];
                        if (debug) {
                            LOG.info("[bonus_area] " + online.getName() + " dmg=" + dmg
                                    + " r=" + String.format(java.util.Locale.ROOT, "%.1f", radius)
                                    + " hits=" + n);
                        }
                    }
                });
    }

    /** 下次攻击票券已消耗后的收尾（给 EffectListener 用）。 */
    public static void afterNextHitTicketsConsumed(Player p, PlayerState st, java.util.Set<String> ids,
                                                   LivingEntity victim) {
        if (st != null && ids != null && !ids.isEmpty()) {
            st.effects().removeIf(x -> ids.contains(x.id()));
        }
        if (victim != null && ids != null && !ids.isEmpty()) {
            for (String itemId : EmpowerFxCoalesce.itemIdsTouched(ids)) {
                ItemAbility ab = ItemAbilityRegistry.get(itemId).orElse(null);
                if (ab == null) continue;
                MagicUseFx empower = MagicFxConfig.resolveEmpowerFx(ab);
                if (empower != null) MagicFxService.playOnEntity(victim, empower);
            }
        }
    }

    private static boolean executeGrantItem(Player p, EffectContext params) {
        return GrantItemParams.parse(params)
                .map(g -> io.mczju.maggoteers.item.ItemService.give(p, g.itemId(), g.amount()))
                .orElse(false);
    }

    private static boolean executeSummon(Player p, MaggoteersGame game, EffectContext raw,
                                         TriggerContext ctx, boolean weaponPath) {
        var parsed = SummonParamsParser.parse(raw, weaponPath);
        if (parsed.isEmpty()) return false;
        SummonParams sp = parsed.get();
        int[] spawned = {0};
        Runnable action = () -> spawned[0] = SummonExecutor.spawn(p, game, sp, ctx);
        if (isCombatSummon(ctx)) {
            MagicDamageContext.run(game, p.getUniqueId(), action);
        } else {
            action.run();
        }
        return spawned[0] > 0;
    }

    private static boolean isCombatSummon(TriggerContext ctx) {
        return shouldWrapMagicDamage(ctx);
    }

    /** Wrap magic/summon damage so same-tick owner DEALT/TAKEN combat triggers are suppressed. */
    private static boolean shouldWrapMagicDamage(TriggerContext ctx) {
        if (ctx == null || ctx.fired() == null) return false;
        return switch (ctx.fired()) {
            case ON_KILL, ON_BOSS_KILL, ON_DAMAGE_DEALT, ON_DAMAGE_TAKEN, ON_DEATH -> true;
            default -> false;
        };
    }

    private static void executeDisableAi(Player p, MaggoteersGame game, EffectContext params,
                                         MagicUseFx weaponFx, TriggerContext ctx, boolean weaponPath,
                                         String effectId) {
        if (!weaponPath) {
            playStatFx(p, params, effectId);
        }

        int duration = params.getOrDefault(EffectKeys.DURATION_TICKS, 0);
        if (duration <= 0) return;

        List<LivingEntity> targets = DisableAiTargets.resolve(game, p, withScaledRadius(p, params), ctx);
        EffectContext markFxCtx = params.get(EffectKeys.MARK_FX);
        MagicUseFx markFx = markFxCtx == null ? null
                : MagicFxBuilder.fromContext(markFxCtx, MagicUseFx.defaults());

        for (LivingEntity le : targets) {
            MobAiLockRegistry.lock(le, duration, game);
            if (weaponPath && markFx != null) {
                playMarkFx(le, markFx);
            }
        }
        if (!weaponPath && markFx != null) {
            LivingEntity markTarget = resolveStatMarkTarget(params, ctx);
            if (markTarget != null) {
                playMarkFx(markTarget, markFx);
            }
        }
    }

    private static void executeBuffArea(Player p, MaggoteersGame game, EffectContext params,
                                        MagicUseFx weaponFx, TriggerContext ctx, boolean weaponPath,
                                        String effectId) {
        if (!weaponPath) {
            playStatFx(p, params, effectId);
        }
        List<BuffPotionSpec> potions = params.get(EffectKeys.POTIONS);
        List<PotionEffectType> clears = params.get(EffectKeys.CLEAR_POTIONS);
        boolean hasPotions = potions != null && !potions.isEmpty();
        boolean hasClear = clears != null && !clears.isEmpty();
        if (!hasPotions && !hasClear) return;

        List<LivingEntity> targets = BuffAreaTargets.resolve(game, p, withScaledRadius(p, params), ctx);
        EffectContext markFxCtx = params.get(EffectKeys.MARK_FX);
        MagicUseFx markFx = markFxCtx == null ? null
                : MagicFxBuilder.fromContext(markFxCtx, MagicUseFx.defaults());

        for (LivingEntity le : targets) {
            if (!le.isValid()) continue;
            if (!le.isDead()) {
                PotionClear.apply(le, clears);
                if (hasPotions) {
                    for (BuffPotionSpec spec : potions) {
                        PotionMerge.applyIfNeeded(le, spec.type(), spec.amp(), spec.durationTicks());
                    }
                }
            }
            if (weaponPath && markFx != null) {
                playMarkFx(le, markFx);
            }
        }
        if (!weaponPath && markFx != null) {
            LivingEntity markTarget = resolveStatMarkTarget(params, ctx);
            if (markTarget != null) {
                playMarkFx(markTarget, markFx);
            }
        }
    }

    /** 触发型 STAT {@code params.fx}：durationTicks>0 → ParticleLoopService 周期模式
     *  （key="fx:预设:效果id"，实体中心上方半高为球心，周期=预设固定缺省 3s）；
     *  <=0（缺省）→ 一次性播放（原行为不变）。 */
    private static void playStatFx(Player p, EffectContext params, String effectId) {
        EffectContext fxCtx = params.get(EffectKeys.FX);
        if (fxCtx == null || p == null) return;
        MagicUseFx fx = MagicFxBuilder.fromContext(fxCtx, MagicUseFx.defaults());
        if (fx.preset() == null || fx.preset() == MagicFxPreset.NONE) return;
        // 2026-10-28：周期模式走 Location 重载（以玩家为圆心），施法范围 RANGE 在这里先缩放一次
        // ——否则「施法范围」藏品只影响一次性 FX，周期环却仍是原始半径。
        MagicUseFx scaled = MagicFxPresets.scaledForCaster(p, fx);
        if (scaled.durationTicks() <= 0) {
            MagicFxPresets.play(p, scaled);
            return;
        }
        String presetName = scaled.preset().name();
        String key = "fx:" + presetName + ":" + (effectId != null ? effectId : "");
        ParticleLoopService.start(p, key, ParticleLoopService.periodTicksForPreset(presetName),
                scaled.durationTicks(), () -> MagicFxPresets.play(
                        p.getLocation().add(0, p.getHeight() * 0.5, 0), scaled));
    }

    private static LivingEntity resolveStatMarkTarget(EffectContext params, TriggerContext ctx) {
        String markOn = params.getOrDefault(EffectKeys.MARK_ON, "none");
        return switch (markOn.toLowerCase(java.util.Locale.ROOT)) {
            case "hit_target" -> ctx == null ? null : ctx.hitTarget();
            case "attacker" -> ctx == null ? null : ctx.attacker();
            default -> null;
        };
    }

    private static void playMarkFx(LivingEntity le, MagicUseFx markFx) {
        if (le == null || markFx == null) return;
        Location loc = le.getLocation();
        if (loc.getWorld() == null) return;
        if (markFx.preset() == MagicFxPreset.THICK_RING) {
            io.mczju.maggoteers.util.ParticleEffects.playThickRing(
                    loc.getWorld(), loc, markFx.radius(), markFx.particle(), markFx.density());
        } else {
            MagicFxPresets.dotAbove(loc, markFx);
        }
    }

    /** @return first damaged LivingEntity (for shared TriggerContext hitTarget), or null */
    private static LivingEntity executeDamageBeam(Player p, MaggoteersGame game, EffectContext params,
                                                  MagicUseFx weaponFx) {
        double maxRange = scaledRayLength(p, params.getOrDefault(EffectKeys.RAY_LENGTH, 32.0));
        double beamRadius = params.getOrDefault(EffectKeys.BEAM_RADIUS, 1.25);
        double baseDmg = params.getOrDefault(EffectKeys.DAMAGE, 0.0);
        double healAmount = params.getOrDefault(EffectKeys.HEAL_AMOUNT, 0.0);
        double dmg = baseDmg * PlayerCombatStats.magicDamageMultiplier(game, p.getUniqueId());

        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        Entity target = p.getTargetEntity((int) maxRange);
        Double targetDist = null;
        if (target != null) {
            targetDist = eye.distance(target.getLocation().add(0, target.getHeight() * 0.5, 0));
        }
        // 2026-10-29：撞墙即停（用户口径：光束不该穿墙）；final 以便 lambda 捕获
        final double length = clampBeamByWalls(eye, dir, BeamLogic.resolveLength(maxRange, targetDist));

        MagicUseFx fx = weaponFx != null ? weaponFx : MagicFxConfig.currentDefaults();
        fx = new MagicUseFx(fx.sound(), fx.soundVolume(), fx.soundPitch(), fx.preset(), fx.particle(),
                fx.radius(), length, fx.density(), fx.rippleRings(), fx.expandSteps(), fx.spiralTicks(),
                fx.durationTicks());
        if (fx.sound() != null) {
            p.playSound(eye, fx.sound(), fx.soundVolume(), fx.soundPitch());
        }
        MagicFxPresets.playBeamAlong(eye, dir, length, fx);

        boolean pierce = Boolean.TRUE.equals(params.get(EffectKeys.PIERCE));
        Set<UUID> hit = new HashSet<>();
        Set<UUID> healedAllies = new HashSet<>();
        AtomicReference<LivingEntity> first = new AtomicReference<>();
        List<LivingEntity> victims = new ArrayList<>();
        int steps = Math.max(16, (int) (length * 2));
        boolean includeSelf = AuraParams.includeSelf(params);
        MagicDamageContext.run(game, p.getUniqueId(), () -> {
            for (int i = 0; i <= steps; i++) {
                Location pt = eye.clone().add(dir.clone().multiply(length * i / (double) steps));
                for (Entity en : p.getWorld().getNearbyEntities(pt, beamRadius, beamRadius, beamRadius)) {
                    if (!(en instanceof LivingEntity le)) continue;
                    if (TargetResolver.isTarget(game, p, le, params)) {
                        if (!hit.add(le.getUniqueId())) continue;
                        first.compareAndSet(null, le);
                        victims.add(le);
                        MagicDamage.applyIndependent(le, dmg, p);
                        if (!pierce) return;
                    }
                }
                if (!pierce && !victims.isEmpty()) return;
            }
        });
        if (healAmount > 0) {
            double healAmt = scaledHealAmount(p, healAmount);
            // 施法者位于 beam 线段起点（eye）后方，垂足 t<0 恒不命中，includeSelf 时无条件自疗
            if (includeSelf) {
                healedAllies.add(p.getUniqueId());
                heal(p, healAmt);
            }
            // 用 hitbox→beam 线段最短距离判定：alliesNear 的「脚底→眼睛高度采样点」3D 球距离会漏掉站立玩家
            for (Player ally : AllyTargeting.alliesOnBeam(game, eye, dir, length, beamRadius, p, includeSelf)) {
                if (!healedAllies.add(ally.getUniqueId())) continue;
                heal(ally, healAmt);
            }
        }
        return first.get();
    }

    // —— 派生视图施加（常驻型）——
    private static void applyDerived(Player p, java.util.List<PlayerEffect> effects, PlayerEffect e) {
        switch (e.effect()) {
            case ADD_ATTRIBUTE -> {
                // 下次攻击票券：不写属性，等近战伤害事件结算
                if (isNextHitTicket(e)) return;
                if (!isVirtualAttribute(effects, e)) applyAttribute(p, effects, e);
            }
            case DYNAMIC_ATTRIBUTE -> {
                if (!isVirtualAttribute(effects, e)) applyDynamicAttribute(p, effects, e);
            }
            case MAX_HEALTH_PER_REVIVE -> {
                // 获取时一次性转化已把总生命写入 params.VALUE；此处按 ADD_ATTRIBUTE(MAX_HEALTH) 施加
                if (e.params().get(EffectKeys.ATTR) == null) {
                    e.params().put(EffectKeys.ATTR, Attribute.MAX_HEALTH);
                }
                if (!isVirtualAttribute(effects, e)) applyAttribute(p, effects, e);
            }
            case ADD_POTION -> applyPotionPermanent(p, e);
            default -> { }
        }
    }

    /**
     * 下次攻击票券（2026-10-27）：武器 {@code use_ability} 的延期
     * {@code ADD_ATTRIBUTE + expiry ON_DAMAGE_DEALT} 不再作为属性模缩存在（避免 resync/切武器时序问题），
     * 改由 {@code EffectListener} 在近战伤害事件里直接结算并消耗。
     */
    public static boolean isNextHitTicket(PlayerEffect e) {
        if (e == null || e.effect() != Effect.ADD_ATTRIBUTE) return false;
        if (e.expiryTrigger() != Trigger.ON_DAMAGE_DEALT) return false;
        String id = e.id();
        if (id == null) return false;
        boolean weaponTemp = id.startsWith(WeaponAbilityIds.PREFIX);
        // 2026-10-28：切肉刀类蓄力 grant（held:<item>:<n>:grant）也走票券，
        // 否则属性 modifier 会在 sweep 之前就生效且可能额外多吃一刀。
        boolean heldGrant = id.startsWith(WeaponHeldService.HELD_PREFIX)
                && id.endsWith(TriggeredGrantAttribute.GRANT_SUFFIX);
        if (!weaponTemp && !heldGrant) return false;
        String attr = PlayerCombatStats.attrNameOf(e);
        return attr != null && "ATTACK_DAMAGE".equalsIgnoreCase(attr.trim());
    }

    /** 有效属性名按认知失调互换（SWAP_ATTACK_MAGIC）后判定是否虚拟。 */
    private static boolean isVirtualAttribute(java.util.List<PlayerEffect> effects, PlayerEffect e) {
        String name = PlayerCombatStats.effectiveAttrName(effects, e.params().get(EffectKeys.ATTR_NAME));
        return name != null && GameRegistries.isVirtualAttribute(name);
    }

    /** 是否存在真实属性的 DYNAMIC_ATTRIBUTE（需心跳指纹检测→resyncDerived 动态刷新；
     *  虚拟属性由 PlayerCombatStats 现算，无需刷新；认知失调互换后按有效属性名判定）。 */
    public static boolean hasRealDynamicAttribute(java.util.List<PlayerEffect> effects) {
        if (effects == null) return false;
        for (PlayerEffect e : effects) {
            if (e.effect() == Effect.DYNAMIC_ATTRIBUTE && !isVirtualAttribute(effects, e)) return true;
        }
        return false;
    }

    private static void applyAttribute(Player p, java.util.List<PlayerEffect> effects, PlayerEffect e) {
        if (isVirtualAttribute(effects, e)) return;
        applyAttributeValue(p, effects, e, e.params().getOrDefault(EffectKeys.VALUE, 0.0));
    }

    /** DYNAMIC_ATTRIBUTE：按当前金币数/复活次数现算值施加（真实属性；虚拟属性由 PlayerCombatStats 现算）。 */
    private static void applyDynamicAttribute(Player p, java.util.List<PlayerEffect> effects, PlayerEffect e) {
        if (isVirtualAttribute(effects, e)) return;
        applyAttributeValue(p, effects, e, PlayerCombatStats.dynamicAttributeValue(p, e));
    }

    private static void applyAttributeValue(Player p, java.util.List<PlayerEffect> effects, PlayerEffect e, double value) {
        if (isVirtualAttribute(effects, e)) return;
        Attribute attr = e.params().get(EffectKeys.ATTR);
        if (attr == null) {
            String name = PlayerCombatStats.effectiveAttrName(effects, e.params().get(EffectKeys.ATTR_NAME));
            if (name != null) attr = GameRegistries.attribute(name);
        }
        if (attr == null) return;
        AttributeInstance inst = p.getAttribute(attr);
        if (inst == null) return;
        String op = e.params().getOrDefault(EffectKeys.OP, "FLAT");
        AttributeModifier.Operation operation = AttributeModifierKeys.attributeOperation(op);
        double maxBefore = attr == Attribute.MAX_HEALTH ? inst.getValue() : 0;
        // D5：唯一 key = effect/<sanitize(id)_level_seq>（与物品 maggoteers:xxx_attack_damage 隔离，防 strip 剥武器伤）
        NamespacedKey key = AttributeModifierKeys.effectKey(MaggoteersPlugin.getInstance(),
                e.id() + "_" + e.level() + "_" + KEY_SEQ.incrementAndGet());
        inst.addModifier(new AttributeModifier(key, value, operation));
        if (attr == Attribute.MAX_HEALTH && !BULK_RESYNC.get()) {
            bumpHealthForMaxGain(p, maxBefore, inst.getValue());
        }
    }

    private static void applyPotionPermanent(Player p, PlayerEffect e) {
        PotionEffectType type = e.params().get(EffectKeys.POTION);
        if (type == null) return;
        if (Boolean.TRUE.equals(e.params().get(EffectKeys.IMMUNITY))) {
            if (PotionImmunity.matchesImmunityEffect(e, type)) {
                p.removePotionEffect(type);
            }
            return;
        }
        int amp = e.params().getOrDefault(EffectKeys.AMP, 0);
        if (PotionImmunity.isImmune(p, type)) {
            return;
        }
        // Permanent potions: long duration refreshed each second by GameplayTickListener
        p.addPotionEffect(new PotionEffect(type, PERMANENT_POTION_TICKS, amp, false, false, true));
    }

    /**
     * Refresh permanent potion derived view (called each second by GameplayTickListener).
     */
    public static void refreshPermanentPotions(Player p) {
        io.mczju.maggoteers.game.MaggoteersGame game = currentGame(p);
        if (game == null) return;
        var st = PlayerStateManager.get(game, p.getUniqueId());
        if (st == null) return;
        for (PlayerEffect e : st.effects()) {
            if (e.isPermanent() && e.effect() == Effect.ADD_POTION) {
                PotionEffectType type = e.params().get(EffectKeys.POTION);
                if (type == null) continue;
                if (Boolean.TRUE.equals(e.params().get(EffectKeys.IMMUNITY))) {
                    if (PotionImmunity.matchesImmunityEffect(e, type)) {
                        p.removePotionEffect(type);
                    }
                    continue;
                }
                int amp = e.params().getOrDefault(EffectKeys.AMP, 0);
                if (PotionImmunity.isImmune(p, type)) {
                    continue;
                }
                p.addPotionEffect(new PotionEffect(type, PERMANENT_POTION_TICKS, amp, false, false, true));
            }
        }
    }

    private static MaggoteersGame currentGame(Player p) {
        return PlayerStateManager.gameOf(p);
    }

    private EffectService() {}
}
