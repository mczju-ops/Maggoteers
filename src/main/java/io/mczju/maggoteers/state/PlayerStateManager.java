package io.mczju.maggoteers.state;

import com.github.mczjuops.mczjugamecore.game.AbstractGame;
import io.mczju.maggoteers.MaggoteersPlugin;
import io.mczju.maggoteers.game.MaggoteersGame;
import io.mczju.maggoteers.effect.AttributeModifierKeys;
import io.mczju.maggoteers.effect.EffectService;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * 本局每玩家 {@link PlayerState} 注册表（借鉴前代 VampireSurvivor SessionManager）。
 * <p>除按对局 Identity 索引外，另有 UUID 索引：避免 MGC {@code getGame()} 与开局
 * {@code this} 非同一实例时查找失败（会导致职业属性不生效、死亡直接 fail）。
 */
public final class PlayerStateManager {
    private static final Logger LOG = Logger.getLogger("Maggoteers");
    /** 复活无敌窗：5s 抗性提升 V + 击退抗性 +1。 */
    private static final int REVIVE_BUFF_TICKS = 100;
    private static final Map<UUID, Integer> REVIVE_KB_GEN = new ConcurrentHashMap<>();
    private static final Map<AbstractGame, Map<UUID, PlayerState>> BY_GAME = new IdentityHashMap<>();
    private static final Map<UUID, PlayerState> BY_UUID = new HashMap<>();
    private static final Map<UUID, MaggoteersGame> GAME_BY_UUID = new HashMap<>();

    /** 开局为某玩家建状态（reviveCount 来自 config lives.default）。 */
    public static PlayerState init(AbstractGame game, UUID uuid, int reviveCount) {
        PlayerState st = new PlayerState(uuid, reviveCount);
        BY_GAME.computeIfAbsent(game, g -> new HashMap<>()).put(uuid, st);
        BY_UUID.put(uuid, st);
        if (game instanceof MaggoteersGame mg) {
            GAME_BY_UUID.put(uuid, mg);
        }
        return st;
    }

    public static PlayerState get(AbstractGame game, UUID uuid) {
        if (uuid == null) return null;
        if (game != null) {
            Map<UUID, PlayerState> m = BY_GAME.get(game);
            if (m != null) {
                PlayerState st = m.get(uuid);
                if (st != null) return st;
            }
        }
        // 回退：不依赖 game 实例 identity（MGC getGame() 偶发不一致）
        return BY_UUID.get(uuid);
    }

    /** 仅按 UUID 取状态（死亡 / 效果分发优先用这个）。 */
    public static PlayerState getByUuid(UUID uuid) {
        return uuid == null ? null : BY_UUID.get(uuid);
    }

    /** 按玩家 UUID 查找所在对局（不依赖 MGC {@code getGame()} 实例是否一致）。 */
    public static MaggoteersGame gameForPlayer(UUID uuid) {
        if (uuid == null) return null;
        MaggoteersGame direct = GAME_BY_UUID.get(uuid);
        if (direct != null) return direct;
        for (var e : BY_GAME.entrySet()) {
            if (e.getValue().containsKey(uuid) && e.getKey() instanceof MaggoteersGame mg) {
                return mg;
            }
        }
        return null;
    }

    /** 解析玩家所在对局：先 registry（更稳），回退 MGC {@code PlayerExt} 实例判断。 */
    public static MaggoteersGame gameOf(Player p) {
        if (p == null) return null;
        MaggoteersGame bound = gameForPlayer(p.getUniqueId());
        if (bound != null) return bound;
        var pe = new com.github.mczjuops.mczjugamecore.player.PlayerExt(p);
        if (!pe.isInGame()) return null;
        AbstractGame g = pe.getGame();
        return g instanceof MaggoteersGame mg ? mg : null;
    }

    /**
     * 本局仍在役：有 {@link PlayerState} 且 {@link PlayerState#isAlive()}。
     * 不检查 GameMode（测试/创造模式切换不影响光环、治疗等）。
     */
    public static boolean isRunParticipant(AbstractGame game, Player p) {
        if (p == null || !p.isOnline()) return false;
        PlayerState st = get(game, p.getUniqueId());
        return st != null && st.isAlive();
    }

    /** 场上是否还有仍在役玩家（仅看 PlayerState）。 */
    public static boolean isAnyAlive(AbstractGame game) {
        if (game != null) {
            Map<UUID, PlayerState> m = BY_GAME.get(game);
            if (m != null && m.values().stream().anyMatch(PlayerState::isAlive)) {
                return true;
            }
            // MGC getPlayers() + UUID 索引：不依赖 IdentityHashMap 的 game 实例是否一致
            for (var pe : game.getPlayers()) {
                if (pe == null || pe.player() == null) continue;
                PlayerState st = BY_UUID.get(pe.player().getUniqueId());
                if (st != null && st.isAlive()) return true;
            }
        }
        // game identity 对不上时：看该局登记过的 UUID
        if (game instanceof MaggoteersGame) {
            for (var e : GAME_BY_UUID.entrySet()) {
                if (e.getValue() == game) {
                    PlayerState st = BY_UUID.get(e.getKey());
                    if (st != null && st.isAlive()) return true;
                }
            }
        }
        return false;
    }

    /**
     * 复活币 · 对死者：拉回冒险模式（仅复活，不加次数）。返回是否成功（死者才需复活）。
     * <p>Plan 6 复活币 GUI 对死者头像调用。
     */
    public static boolean revivePlayer(AbstractGame game, UUID uuid) {
        PlayerState st = get(game, uuid);
        if (st == null || st.isAlive()) return false;
        st.setAlive(true);
        Player p = Bukkit.getPlayer(uuid);
        if (p == null) return true;
        applyAdventure(p);
        MaggoteersGame mg = game instanceof MaggoteersGame g ? g : gameForPlayer(uuid);
        if (mg != null) {
            EffectService.clearAllActivePotions(p);
            // 2026-10-26：复活不再有 ON_REVIVE 内容通道（Trigger 已删）；派生视图由 resync 全量重建。
            EffectService.resync(p);
        }
        restoreFullHealth(p);
        applyReviveBuffs(p);
        return true;
    }

    /** 复活后短暂无敌窗：5s 抗性提升 V + 击退抗性 +1（自动复活 / 复活币共用）。 */
    public static void applyReviveBuffs(Player p) {
        if (p == null || !p.isOnline()) return;
        p.addPotionEffect(new PotionEffect(
                PotionEffectType.RESISTANCE, REVIVE_BUFF_TICKS, 4, false, true, true));
        AttributeInstance kb = p.getAttribute(Attribute.KNOCKBACK_RESISTANCE);
        if (kb == null) return;
        NamespacedKey key = reviveKnockbackKey();
        AttributeModifierKeys.replace(kb, key, 1.0, AttributeModifier.Operation.ADD_NUMBER);
        UUID uuid = p.getUniqueId();
        int gen = REVIVE_KB_GEN.merge(uuid, 1, Integer::sum);
        Bukkit.getScheduler().runTaskLater(MaggoteersPlugin.getInstance(), () -> {
            if (!Objects.equals(REVIVE_KB_GEN.get(uuid), gen)) return;
            Player live = Bukkit.getPlayer(uuid);
            if (live == null || !live.isOnline()) return;
            AttributeInstance inst = live.getAttribute(Attribute.KNOCKBACK_RESISTANCE);
            if (inst != null) AttributeModifierKeys.removeIfMatches(inst, key);
        }, REVIVE_BUFF_TICKS);
    }

    private static NamespacedKey reviveKnockbackKey() {
        return new NamespacedKey(MaggoteersPlugin.getInstance(), "revive/knockback_resistance");
    }

    /**
     * 复活币 · 对活者：reviveCount + n（不改变当前存活状态）。
     * <p>Plan 6 复活币 GUI 对活者头像调用。
     */
    public static void addReviveCount(AbstractGame game, UUID uuid, int n) {
        PlayerState st = get(game, uuid);
        if (st == null) return;
        st.setReviveCount(st.getReviveCount() + n);
    }

    /** 把玩家设为冒险模式（血量须另调 {@link #restoreFullHealth}，通常在 resync 之后）。 */
    static void applyAdventure(Player p) {
        if (p == null) return;
        p.setGameMode(GameMode.ADVENTURE);
        clearStaleInvulnerable(p);
        clearFallFire(p);
        p.setNoDamageTicks(40);
    }

    /**
     * 清掉**别处（通常是别的插件的对局逻辑）遗留在玩家实体上的无敌标记**。
     *
     * <p>血泪根因（2026-10-26 实测）：`Player#setInvulnerable(true)` 会写进玩家实体的 NBT
     * (`Invulnerable:1b`)，**跨重启持久化**。原版索敌走
     * {@code TargetingConditions.test} → {@code LivingEntity.canBeSeenAsEnemy()} =
     * {@code !isInvulnerable() && ...} ⇒ 玩家一旦带着这个标记，**任何怪物都不会把他当目标**
     * （症状：局内所有怪站着不追人），而且他身上的一切伤害都会被实体标记直接吃掉（打不死）。
     * 本局实测到 InfernalClash 的「玩家索敌排除」把玩家设为无敌后没有还原
     * （日志：生效 9 次 / 还原 0 次，玩家数据里 `Invulnerable` 落成 1）。
     *
     * <p>本插件是冒险模式的 PvE 局：**任何进入对局的玩家都必须能被怪打**，所以开局/复活
     * 这两处入口统一把外来无敌剥掉，并**打一行 warning 指认玩家名**——绝不静默（否则下次
     * 还是查不出是谁留下的）。
     *
     * <p>只动这一个 boolean，不碰同世界的其它实体/玩家，也不碰创造/旁观模式的免伤
     * （那是 GameMode 语义，不读这个标记）。
     */
    public static void clearStaleInvulnerable(Player p) {
        if (p == null) return;
        try {
            if (!p.isInvulnerable()) return;
            p.setInvulnerable(false);
            LOG.warning("已清除玩家身上遗留的「无敌」标记：" + p.getName()
                    + "（Invulnerable=1 会让**所有怪物都不把他当目标**、并吃掉一切伤害；"
                    + "通常是别的插件对局收尾没还原。已在本局开局/复活时强制归零）");
        } catch (RuntimeException ex) {
            LOG.warning("清除「无敌」标记失败 " + p.getName() + "：" + ex.getMessage());
        }
    }

    /** 按当前 MAX_HEALTH 属性回满血（须在 EffectService.resync 之后调用）。 */
    public static void restoreFullHealth(Player p) {
        if (p == null) return;
        healToMax(p);
        clearFallFire(p);
        p.setNoDamageTicks(40);
    }

    /** 清摔落距离与着火状态（复活/进层/回大厅共用）。 */
    public static void clearFallFire(Player p) {
        if (p == null) return;
        p.setFallDistance(0f);
        p.setFireTicks(0);
    }

    /**
     * 局内摔落免疫（2026-10-26 用户口径）：安全摔落高度抬到 40 格，并把摔落伤害倍率归零。
     * 二者都写**基础值**（不是 modifier），所以不会被 {@code EffectService.stripDerived} 剥掉。
     */
    public static void applyFallSafety(Player p) {
        if (p == null) return;
        AttributeInstance safe = p.getAttribute(Attribute.SAFE_FALL_DISTANCE);
        if (safe != null) safe.setBaseValue(SAFE_FALL_DISTANCE);
        AttributeInstance mult = p.getAttribute(Attribute.FALL_DAMAGE_MULTIPLIER);
        if (mult != null) mult.setBaseValue(0.0);
        p.setFallDistance(0f);
    }

    /** 回大厅/退出时把摔落属性还原为原版默认（安全高度 3、倍率 1）。 */
    public static void restoreFallSafety(Player p) {
        if (p == null) return;
        AttributeInstance safe = p.getAttribute(Attribute.SAFE_FALL_DISTANCE);
        if (safe != null) safe.setBaseValue(3.0);
        AttributeInstance mult = p.getAttribute(Attribute.FALL_DAMAGE_MULTIPLIER);
        if (mult != null) mult.setBaseValue(1.0);
    }

    /** 局内安全摔落高度（格）。 */
    public static final double SAFE_FALL_DISTANCE = 40.0;

    /** 按当前 MAX_HEALTH 回满血；MAX_HEALTH 异常 ≤0 时回落 20.0（保留 restoreFullHealth 原守卫语义）。 */
    public static void healToMax(Player p) {
        if (p == null) return;
        var maxHp = p.getAttribute(Attribute.MAX_HEALTH);
        double max = maxHp != null ? maxHp.getValue() : 20.0;
        if (max <= 0) max = 20.0;
        p.setHealth(max);
    }

    /** 结束钩子：清掉本局所有玩家状态。 */
    public static void destroyAll(AbstractGame game) {
        Map<UUID, PlayerState> m = BY_GAME.remove(game);
        if (m != null) {
            for (UUID uuid : m.keySet()) {
                BY_UUID.remove(uuid);
                GAME_BY_UUID.remove(uuid);
                REVIVE_KB_GEN.remove(uuid);
            }
            return;
        }
        // identity 对不上：按 MaggoteersGame 引用扫 UUID 索引
        if (game instanceof MaggoteersGame mg) {
            GAME_BY_UUID.entrySet().removeIf(e -> {
                if (e.getValue() == mg) {
                    BY_UUID.remove(e.getKey());
                    return true;
                }
                return false;
            });
        }
    }

    /** 仍有 PlayerState 的对局（含 MGC gameList 未收录的运行中实例）。 */
    public static Set<AbstractGame> gamesWithState() {
        return Set.copyOf(BY_GAME.keySet());
    }

    public static Set<UUID> uuidsInGame(AbstractGame game) {
        if (game != null) {
            Map<UUID, PlayerState> m = BY_GAME.get(game);
            if (m != null) return Set.copyOf(m.keySet());
        }
        if (game instanceof MaggoteersGame mg) {
            Set<UUID> out = new java.util.HashSet<>();
            for (var e : GAME_BY_UUID.entrySet()) {
                if (e.getValue() == mg) out.add(e.getKey());
            }
            return Set.copyOf(out);
        }
        return Set.of();
    }

    /** 诊断：状态缺失时打日志（勿在热路径高频调用）。 */
    public static void warnMissingState(String where, AbstractGame game, UUID uuid) {
        LOG.warning(where + ": PlayerState 缺失 uuid=" + uuid
                + " gameIdentity=" + (game == null ? "null" : System.identityHashCode(game))
                + " byUuid=" + (BY_UUID.containsKey(uuid))
                + " gamesTracked=" + BY_GAME.size());
    }

    private PlayerStateManager() {}
}
