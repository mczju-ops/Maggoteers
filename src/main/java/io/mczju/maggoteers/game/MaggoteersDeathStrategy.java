package io.mczju.maggoteers.game;

import com.github.mczjuops.mczjugamecore.game.AbstractGame;
import com.github.mczjuops.mczjugamecore.player.PlayerExt;
import com.github.mczjuops.mczjugamecore.player.strategy.AbstractPlayerDeathStrategy;
import io.mczju.maggoteers.MaggoteersPlugin;
import io.mczju.maggoteers.config.MessageService;
import io.mczju.maggoteers.effect.EffectService;
import io.mczju.maggoteers.effect.Trigger;
import io.mczju.maggoteers.effect.TriggerContext;
import io.mczju.maggoteers.state.PlayerState;
import io.mczju.maggoteers.state.PlayerStateManager;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 死亡策略（借鉴前代 VampireSurvivor SurvivorPlayerDeathStrategy，改 reviveCount 复活机制）。
 * <p>须在取消死亡后立刻拉离 0 血，并防重入：否则 ON_DEATH/resync 窗口内可连扣多次复活并秒 fail。
 */
public class MaggoteersDeathStrategy extends AbstractPlayerDeathStrategy {

    /** 正在处理死亡的玩家（防 PlayerDeathEvent 重入连扣 reviveCount）。 */
    private static final Set<UUID> HANDLING = ConcurrentHashMap.newKeySet();

    public MaggoteersDeathStrategy(AbstractGame game) {
        super(game);
    }

    @Override
    public void onPlayerDeath(PlayerExt victim, PlayerDeathEvent event) {
        Player p = victim.player();
        UUID uuid = p.getUniqueId();
        event.setCancelled(true);
        // 立刻离开 0 血，避免本 tick 内再次触发死亡
        stabilizeAlive(p);

        if (!HANDLING.add(uuid)) {
            MaggoteersPluginLog.info("DeathStrategy 忽略重入死亡: " + p.getName());
            return;
        }
        try {
            handleDeath(p, uuid);
        } finally {
            HANDLING.remove(uuid);
        }
    }

    private void handleDeath(Player p, UUID uuid) {
        MaggoteersGame mg = AllyTargeting.resolveForPlayer(p, game);
        if (mg == null && game instanceof MaggoteersGame g) mg = g;
        if (mg == null) {
            mg = PlayerStateManager.gameForPlayer(uuid);
        }

        PlayerState st = PlayerStateManager.getByUuid(uuid);
        if (st == null && mg != null) {
            st = PlayerStateManager.get(mg, uuid);
        }
        if (st == null) {
            PlayerStateManager.warnMissingState("DeathStrategy", game, uuid);
            p.setGameMode(GameMode.ADVENTURE);
            PlayerStateManager.restoreFullHealth(p);
            MaggoteersPluginLog.warn("死亡时无 PlayerState，已强制复活: " + p.getName());
            if (mg != null) {
                mg.sender().warn(MessageService.raw("death.missing_state", Map.of("player", p.getName())));
            }
            return;
        }

        if (mg == null) {
            PlayerStateManager.restoreFullHealth(p);
            MaggoteersPluginLog.warn("死亡时无法解析 MaggoteersGame，已强制复活: " + p.getName());
            return;
        }

        Location deathLoc = p.getLocation().clone();
        // DYNAMIC_ATTRIBUTE source=deaths：每次进入死亡处理都记一次（自动复活与转观察者都算），
        // 与 reviveCount 无关（复活石加次数不会倒扣死亡次数）。
        st.incrementDeaths();
        MaggoteersPluginLog.info("DeathStrategy: " + p.getName()
                + " reviveCount=" + st.getReviveCount()
                + " alive=" + st.isAlive()
                + " gameIdentity=" + System.identityHashCode(mg));

        try {
            EffectService.fireTriggerPlayer(mg, p, Trigger.ON_DEATH,
                    TriggerContext.atEvent(Trigger.ON_DEATH, deathLoc));
        } catch (RuntimeException ex) {
            MaggoteersPluginLog.warn("ON_DEATH 触发异常: " + ex.getMessage());
        }
        // 触发效果可能再次把血打空
        stabilizeAlive(p);

        if (st.tryAutoRevive()) {
            Location spawn = io.mczju.maggoteers.wave.WaveScheduler.currentSpawnLocation(mg);
            if (spawn == null) {
                spawn = deathLoc;
            }
            p.teleport(spawn);
            p.setGameMode(GameMode.ADVENTURE);
            PlayerStateManager.clearStaleInvulnerable(p);   // 复活后必须能被怪打（剥外来无敌标记）
            stabilizeAlive(p);
            try {
                // 死亡已 cancel，原版不会清药水；先剥临时药水，再 resync 常驻（PlayerState 真相源）
                EffectService.clearAllActivePotions(p);
                // 2026-10-26：ON_REVIVE 已从 Trigger 删除（复活不是内容触发通道），复活只做视图重建。
                EffectService.resync(p);
            } catch (RuntimeException ex) {
                MaggoteersPluginLog.warn("复活 resync 异常: " + ex.getMessage());
            } finally {
                PlayerStateManager.restoreFullHealth(p);
                PlayerStateManager.applyReviveBuffs(p);
            }
            MaggoteersPluginLog.info("自动复活: " + p.getName()
                    + " 剩余 reviveCount=" + st.getReviveCount());
            mg.sender().info(MessageService.raw("death.auto_revive", Map.of(
                    "player", p.getName(),
                    "remaining", String.valueOf(st.getReviveCount()))));
            return;
        }

        st.markDown();
        // 成就：记"首次倒下时已过层数"（天才少年要判"其余人都在一层 Boss 倒下前出局"），
        // 并在自伤有效窗内顺手判「魔芋爽」。
        io.mczju.maggoteers.achievement.AchievementService.onPlayerDowned(mg, p);
        io.mczju.maggoteers.effect.AuraService.stripAllFromSource(mg, uuid);
        stabilizeAlive(p);
        // 旁观者无法接收原版药水（LivingEntity.addEffect 对 isSpectator 拒绝；GameplayTickListener 也跳过非存活）：
        // 切模式前先把长效夜视挂上，切后 resync 只会 strip 掉已有夜视再重施加失败。
        // 切模式前挂无限时长夜视（与 EffectService.SPECTATOR_NIGHT_VISION_TICKS 同值）；旁观者收不到 resync 重施加。
        if (MaggoteersPlugin.getInstance().getConfig().getBoolean("player.night_vision", true)) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION,
                    EffectService.SPECTATOR_NIGHT_VISION_TICKS, 0, false, true, true));
        }
        // 不调用 EffectService.resync：旁观者不可接收药水，重施加会失败；残留属性/药水由 cleanupRun 统一剥离。
        p.setGameMode(GameMode.SPECTATOR);
        // 观战视角：不强制跟随、不瞬移（用户明确不要）——观察者留在原地自由飞行；SPECTATE 传送由 RunRulesListener 拦截。
        boolean noneAlive = !PlayerStateManager.isAnyAlive(mg);
        p.setSpectatorTarget(null);
        MaggoteersPluginLog.info("转观察者: " + p.getName() + "（复活次数耗尽）");
        mg.sender().warn(MessageService.raw("death.to_spectator", Map.of("player", p.getName())));

        if (noneAlive) {
            MaggoteersPluginLog.info("全员倒下，markDefeated(): " + p.getName());
            mg.markDefeated();
        }
    }

    /** 取消死亡后立刻保证 hp≥1，堵住重入窗口。 */
    private static void stabilizeAlive(Player p) {
        if (p == null || !p.isOnline()) return;
        var maxHp = p.getAttribute(Attribute.MAX_HEALTH);
        double max = maxHp != null ? maxHp.getValue() : 20.0;
        if (max <= 0) max = 20.0;
        double hp = p.getHealth();
        if (hp < 1.0) {
            p.setHealth(Math.min(1.0, max));
        }
        PlayerStateManager.clearFallFire(p);
        p.setNoDamageTicks(Math.max(p.getNoDamageTicks(), 40));
    }

    /** 避免 DeathStrategy 直接依赖 MaggoteersPlugin 循环引用时的编译噪音。 */
    private static final class MaggoteersPluginLog {
        private static final java.util.logging.Logger LOG =
                java.util.logging.Logger.getLogger("Maggoteers");
        static void info(String msg) { LOG.info(msg); }
        static void warn(String msg) { LOG.warning(msg); }
    }
}
