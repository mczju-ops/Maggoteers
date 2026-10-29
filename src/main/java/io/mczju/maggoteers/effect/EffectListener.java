package io.mczju.maggoteers.effect;

import io.mczju.maggoteers.MaggoteersPlugin;
import io.mczju.maggoteers.game.MaggoteersGame;
import io.mczju.maggoteers.state.PlayerStateManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.scheduler.BukkitTask;

/** 战斗 trigger 监听 + 每秒玩家心跳。lifecycle/player trigger 在各 fire-point 直接调 EffectService。 */
public final class EffectListener implements Listener {

    private static BukkitTask tickTask;
    private static int tickRef = 0;

    /**
     * 启动每秒玩家心跳（引用计数：多个对局共享同一个心跳任务）。
     * <p>本心跳只做两件事（详见 {@link EffectService#tickGame}）：
     * ① 推进玩家侧 {@code counter{count: tick}} 计数器（计满走 {@code ON_COUNTER}）；
     * ② 推进 {@code recurring} 周期性效果（{@link RecurringClock} 内部计时）。
     * <p>与 {@link io.mczju.maggoteers.listener.GameplayTickListener}（光环/常驻药水/满腹维护）
     * 职责不同、枚举源不同（这里 {@code ActiveMaggoteersGames}，那边 {@code gamesWithState()}），
     * 有意分开，勿合并——合并会改变两者各自的遍历与时机语义。
     */
    public static void startTick() {
        if (++tickRef > 1) return;          // 已有心跳，只增引用计数
        tickTask = Bukkit.getScheduler().runTaskTimer(MaggoteersPlugin.getInstance(), () -> {
            io.mczju.maggoteers.game.ActiveMaggoteersGames.forEach(EffectService::tickGame);
        }, 20L, 20L);   // 每秒
    }

    public static void stopTick() {
        if (tickRef <= 0) return;
        if (--tickRef > 0) return;          // 还有对局在用
        if (tickTask != null) { tickTask.cancel(); tickTask = null; }
    }

    /** 玩家击杀实体 → ON_KILL（玩家方）。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKill(EntityDeathEvent e) {
        Player killer = e.getEntity().getKiller();
        if (killer == null) return;
        MaggoteersGame g = PlayerStateManager.gameOf(killer);
        if (g == null) return;
        io.mczju.maggoteers.state.PlayerState st = PlayerStateManager.get(g, killer.getUniqueId());
        if (st != null) st.incrementKills();   // 局内击杀统计（侧边栏）
        LivingEntity victim = e.getEntity();
        TriggerContext ctx = new TriggerContext(
                Trigger.ON_KILL, victim, null, victim.getLocation().clone());
        EffectService.fireTriggerPlayer(g, killer, Trigger.ON_KILL, ctx);
        // 2026-10-28：ON_BOSS_KILL 已移到 MobDeathListener.onDeath（HIGHEST、handleMobDeath **之前**）。
        // 那里发出才认得出 Boss：handleMobDeath 会清 bossBars，而本监听器是 MONITOR（更晚），
        // isTrackedBoss 恒 false → 霜晶树少发一颗星石。
    }

    /**
     * 下次攻击票券（2026-10-27）：{@code ability:*} 的 ADD_ATTRIBUTE + expiry ON_DAMAGE_DEALT 不再当属性模缩存在，
     * 在这里直接结算到本次近战伤害（只有当前主手 == 票券来源武器才生效与消耗）。
     * 顺序：HIGH（伤害定案前）→ MONITOR（负责 fireTrigger）。
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onNextHitTicket(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        if (e.getCause() != org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        MaggoteersGame g = PlayerStateManager.gameOf(p);
        if (g == null) return;
        if (MagicDamageContext.shouldSuppressOnDamageDealt(g, p.getUniqueId())) return;
        var st = PlayerStateManager.get(g, p.getUniqueId());
        if (st == null) return;
        String heldId = io.mczju.maggoteers.item.ItemService.itemIdOf(p.getInventory().getItemInMainHand());
        if (heldId == null) return;
        double mult = 1.0;
        double flat = 0.0;
        java.util.Set<String> consumed = new java.util.HashSet<>();
        java.util.List<EffectContext> bonuses = new java.util.ArrayList<>();
        for (PlayerEffect pe : new java.util.ArrayList<>(st.effects())) {
            if (!EffectService.isNextHitTicket(pe)) continue;
            // 2026-10-28：票券来源既可能是 {@code ability:} （右键蓄力）也可能是
            // {@code held:*:grant}（计数器蓄力，如乌萨斯切肉刀）。
            String src = WeaponAbilityIds.itemIdOf(pe.id())
                    .orElseGet(() -> WeaponHeldService.parseHeldItemId(pe.id()));
            if (src == null || !src.equals(heldId)) continue;
            double value = pe.params().getOrDefault(EffectKeys.VALUE, 0.0);
            String op = pe.params().getOrDefault(EffectKeys.OP, "FLAT");
            if ("FLAT".equalsIgnoreCase(op)) flat += value;
            else mult *= 1.0 + value;
            if (pe.expiryCharges() == -1) {
                consumed.add(pe.id());
            } else if (pe.expiryCharges() > 0) {
                pe.setExpiryCharges(pe.expiryCharges() - 1);
                if (pe.expiryCharges() <= 0) consumed.add(pe.id());
            }
            EffectContext bonus = pe.params().get(EffectKeys.BONUS_AREA);
            if (bonus != null) bonuses.add(bonus);
        }
        if (consumed.isEmpty() && mult == 1.0 && flat == 0.0) return;
        if (mult != 1.0) e.setDamage(e.getDamage() * mult);
        if (flat != 0.0) e.setDamage(e.getDamage() + flat);
        // 这一刀是"蓄力那一刀"：不计入下一轮蓄力（AAQ AAQ）。无票券时不设标记。
        if (!consumed.isEmpty()) st.skipAttackCounterOnce();
        EffectService.afterNextHitTicketsConsumed(p, st, consumed,
                e.getEntity() instanceof LivingEntity le ? le : null);
        // 2026-10-28：蓄力那一刀的范围伤害（只在消耗票券时生效）
        if (e.getEntity() instanceof LivingEntity victim) {
            EffectService.castBonusAreas(p, g, bonuses, victim);
        }
    }

    /** 玩家造成伤害 → ON_DAMAGE_DEALT（v1 近战 Player 仅）。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamageDealt(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        MaggoteersGame g = PlayerStateManager.gameOf(p);
        if (g == null) return;
        if (MagicDamageContext.shouldSuppressOnDamageDealt(g, p.getUniqueId())) return;
        Entity damager = e.getDamager();
        if (SummonRegistry.isTrackedSummon(damager) || SummonRegistry.isTrackedProjectile(damager)) return;
        LivingEntity victim = e.getEntity() instanceof LivingEntity le ? le : null;
        TriggerContext ctx = new TriggerContext(Trigger.ON_DAMAGE_DEALT, victim, null);
        EffectService.fireTriggerPlayer(g, p, Trigger.ON_DAMAGE_DEALT, ctx);
    }

    /** 玩家受敌人攻击 → ON_DAMAGE_TAKEN（环境/友军伤害不触发）。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamageTaken(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        MaggoteersGame g = PlayerStateManager.gameOf(p);
        if (g == null) return;
        // Guardian spike reflect + thorns DAMAGE_AREA would recurse without this.
        if (MagicDamageContext.shouldSuppressOnDamageTaken(g, p.getUniqueId())) return;
        var attacker = EnemyAttackResolver.resolveAttacker(e, g);
        if (attacker.isEmpty()) return;
        // DYNAMIC_ATTRIBUTE source=hits_taken：只统计"敌人攻击"（与 ON_DAMAGE_TAKEN 同口径，
        // 环境/友伤不计）。先记账再分发，本次受击触发的效果即可读到最新次数。
        var st = PlayerStateManager.get(g, p.getUniqueId());
        if (st != null && st.isAlive()) st.incrementHitsTaken();
        TriggerContext ctx = new TriggerContext(Trigger.ON_DAMAGE_TAKEN, null, attacker.get());
        EffectService.fireTriggerPlayer(g, p, Trigger.ON_DAMAGE_TAKEN, ctx);
    }

}
