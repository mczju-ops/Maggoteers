package io.mczju.maggoteers.effect;

import com.github.mczjuops.mczjugamecore.game.AbstractGame;
import io.mczju.maggoteers.MaggoteersPlugin;
import io.mczju.maggoteers.game.AllyTargeting;
import io.mczju.maggoteers.game.MaggoteersGame;
import io.mczju.maggoteers.state.PlayerStateManager;
import io.mczju.maggoteers.wave.WaveEngine;
import io.mczju.maggoteers.item.fx.MagicFxBuilder;
import io.mczju.maggoteers.item.fx.MagicFxPreset;
import io.mczju.maggoteers.item.fx.MagicFxPresets;
import io.mczju.maggoteers.item.fx.MagicUseFx;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 每秒刷新 AURA 派生：友军/敌军范围内 attribute/potion；友军 HEAL 脉冲。
 * 派生 key 命名空间 {@code maggoteers:aura/{source}/{instance}/...}。
 */
public final class AuraService {

    private static final Map<AbstractGame, Set<String>> ACTIVE_LINKS = new IdentityHashMap<>();
    /**
     * 2026-10-28：每个 link 上次脉冲的秒数。历史实现用 {@code tickSec % pulse == 0} 判断，
     * 而 refresh（切主手/重派生）随时可能发生 → pulse=1 时每次 refresh 都立即治疗一次。
     */
    private static final Map<AbstractGame, Map<String, Long>> LINK_PULSE_AT = new IdentityHashMap<>();
    /** carrierFx 节流：game → (carrierUuid|instanceKey → 上次播放的秒级时钟) */
    private static final Map<AbstractGame, Map<String, Long>> LAST_CARRIER_FX_SEC = new IdentityHashMap<>();

    private AuraService() {}

    public static void refresh(MaggoteersGame game) {
        if (game == null) return;
        Set<String> desired = new HashSet<>();
        long tickSec = Bukkit.getCurrentTick() / 20L;

        for (UUID carrierId : PlayerStateManager.uuidsInGame(game)) {
            Player carrier = Bukkit.getPlayer(carrierId);
            if (carrier == null) continue;
            if (!PlayerStateManager.isRunParticipant(game, carrier)) continue;

            var ps = PlayerStateManager.get(game, carrier.getUniqueId());
            if (ps == null) continue;

            List<PlayerEffect> effects = ps.effects();
            for (int i = 0; i < effects.size(); i++) {
                PlayerEffect aura = effects.get(i);
                if (aura.effect() != Effect.AURA || aura.fireTrigger() != null) continue;

                syncAuraParamsFromCatalog(aura);
                String instanceKey = aura.id() + "_" + i;
                EffectContext params = aura.params();
                UUID sourceId = carrier.getUniqueId();
                // 2026-10-27：光环半径吃携带者的 RANGE 系数（技能范围）
                double radius = AuraParams.radius(params)
                        * PlayerCombatStats.rangeMultiplier(game, sourceId);

                if (AuraParams.targetsAllies(params)) {
                    for (Player ally : AllyTargeting.alliesInRadius(game, carrier, radius, AuraParams.includeSelf(params))) {
                        String link = playerLink(ally.getUniqueId(), sourceId, instanceKey);
                        desired.add(link);
                        applyGrantToPlayer(ally, sourceId, instanceKey, aura, params, tickSec, link, game);
                        markHeadIfNeeded(ally, params);
                    }
                }
                if (AuraParams.targetsEnemies(params)) {
                    for (LivingEntity mob : enemiesInRange(game, carrier, radius, AuraParams.enemyScope(params))) {
                        String link = mobLink(mob.getUniqueId(), sourceId, instanceKey);
                        desired.add(link);
                        applyGrantToMob(mob, sourceId, instanceKey, aura, params);
                        markHeadIfNeeded(mob, params);
                    }
                }
                playCarrierFxIfDue(game, carrier, instanceKey, params);
            }
        }

        Set<String> prev = ACTIVE_LINKS.getOrDefault(game, Set.of());
        for (String old : prev) {
            if (!desired.contains(old)) revertLink(game, old);
        }
        ACTIVE_LINKS.put(game, desired);
    }

    /** 该 link 本秒是否应该发一次脉冲（按 pulse 秒节流，不受 refresh 频率影响）。 */
    private static boolean pulseDue(AbstractGame game, String link, long tickSec, int pulseSec) {
        if (game == null || link == null) return true;
        int p = Math.max(1, pulseSec);
        Map<String, Long> m = LINK_PULSE_AT.computeIfAbsent(game, k -> new HashMap<>());
        Long last = m.get(link);
        if (last != null && tickSec - last < p) return false;
        m.put(link, tickSec);
        return true;
    }

    public static void clearGame(AbstractGame game) {
        LINK_PULSE_AT.remove(game);
        Set<String> prev = ACTIVE_LINKS.remove(game);
        if (prev != null) {
            for (String link : prev) revertLink(game, link);
        }
        LAST_CARRIER_FX_SEC.remove(game);
    }

    /** 剥除某来源玩家的全部光环派生（死亡/观察者/离开）。 */
    public static void stripAllFromSource(AbstractGame game, UUID sourceUuid) {
        Set<String> prev = ACTIVE_LINKS.get(game);
        if (prev == null) return;
        Set<String> next = new HashSet<>();
        for (String link : prev) {
            if (linkContainsSource(link, sourceUuid)) revertLink(game, link);
            else next.add(link);
        }
        ACTIVE_LINKS.put(game, next);
    }

    /** 剥除施加到某接收者身上的全部光环派生（该玩家 leave）。 */
    public static void stripAllFromReceiver(AbstractGame game, UUID receiverUuid) {
        Set<String> prev = ACTIVE_LINKS.get(game);
        if (prev == null) return;
        Set<String> next = new HashSet<>();
        String prefix = "p:" + receiverUuid + "|";
        for (String link : prev) {
            if (link.startsWith(prefix)) revertLink(game, link);
            else next.add(link);
        }
        ACTIVE_LINKS.put(game, next);
    }

    private static boolean linkContainsSource(String link, UUID sourceUuid) {
        return link.contains("|s:" + sourceUuid + "|");
    }

    private static String playerLink(UUID receiver, UUID source, String instance) {
        return "p:" + receiver + "|s:" + source + "|i:" + instance;
    }

    private static String mobLink(UUID mob, UUID source, String instance) {
        return "m:" + mob + "|s:" + source + "|i:" + instance;
    }

    private static List<LivingEntity> enemiesInRange(MaggoteersGame game, Player carrier, double radius, String scope) {
        List<LivingEntity> out = new ArrayList<>();
        var world = carrier.getWorld();
        for (Entity en : world.getNearbyEntities(carrier.getLocation(), radius, radius, radius)) {
            if (!(en instanceof LivingEntity le) || en instanceof Player) continue;
            if (AuraParams.ENEMY_TRACKED.equals(scope) && !WaveEngine.isTracked(le.getUniqueId())) continue;
            out.add(le);
        }
        return out;
    }

    private static void playCarrierFxIfDue(MaggoteersGame game, Player carrier, String instanceKey,
                                            EffectContext params) {
        EffectContext cfx = AuraParams.carrierFx(params);
        if (cfx == null) return;
        MagicUseFx fx = MagicFxBuilder.fromContext(cfx, MagicUseFx.defaults());
        // 2026-10-29：边界环（THICK_RING）每 10 tick 重画 = 稳定的一圈；扩散环(expand)会一直往外扫，
        // 玩家反馈"很挡视野"。其余预设仍按 config 的秒级节流。
        long nowTick = Bukkit.getCurrentTick();
        int intervalTicks = fx.preset() == MagicFxPreset.THICK_RING
                ? 10
                : Math.max(1, carrierFxIntervalSec(fx.preset())) * 20;
        String throttleKey = carrier.getUniqueId() + "|" + instanceKey;
        Map<String, Long> perGame = LAST_CARRIER_FX_SEC.computeIfAbsent(game, g -> new HashMap<>());
        Long last = perGame.get(throttleKey);
        if (last != null && nowTick - last < intervalTicks) return;
        perGame.put(throttleKey, nowTick);
        MagicFxPresets.play(carrier, fx);
    }

    private static int carrierFxIntervalSec(MagicFxPreset preset) {
        var cfg = MaggoteersPlugin.getInstance().getConfig();
        String presetPath = "aura.carrier_fx.by_preset." + preset.name();
        if (cfg.contains(presetPath)) {
            return Math.max(1, cfg.getInt(presetPath));
        }
        return Math.max(1, cfg.getInt("aura.carrier_fx.default_interval_sec", 3));
    }

    private static void markHeadIfNeeded(LivingEntity le, EffectContext params) {
        if (!AuraParams.markHead(params)) return;
        EffectContext mfx = AuraParams.markFx(params);
        MagicUseFx fx = MagicFxBuilder.fromContext(mfx,
                new MagicUseFx(null, 0, 0, io.mczju.maggoteers.item.fx.MagicFxPreset.DOT_ABOVE,
                        org.bukkit.Particle.WITCH, 0.5, 0, 24, 0, 0, 0, 0));
        MagicFxPresets.dotAbove(le.getLocation(), fx);
    }

    private static void applyGrantToPlayer(Player receiver, UUID sourceId, String instanceKey,
                                           PlayerEffect aura, EffectContext params, long tickSec, String link,
                                           AbstractGame game) {
        Effect grant = AuraParams.grantEffect(params);
        if (grant == null) return;
        EffectContext gp = AuraParams.grantParams(params);
        switch (grant) {
            case ADD_ATTRIBUTE -> applyAttributeDerived(receiver, sourceId, instanceKey, gp, aura.level());
            case ADD_POTION -> applyPotionDerived(receiver, sourceId, instanceKey, gp,
                    AuraParams.grantPulseSec(params));
            case HEAL -> {
                int pulse = AuraParams.grantPulseSec(params);
                if (!pulseDue(game, link, tickSec, pulse)) return;
                double amount = gp.getOrDefault(EffectKeys.AMOUNT, 0.0);
                // 治疗强度按**光环携带者**（施术者）的 HEAL 系数缩放
                EffectService.heal(receiver, amount * PlayerCombatStats.healMultiplierByUuid(sourceId));
            }
            default -> {
                // 2026-10-26 扩展：任意"逐目标脉冲"效果（伤害/冻结/定身/关 AI/清药…）
                int pulse = AuraParams.grantPulseSec(params);
                if (!pulseDue(game, link, tickSec, pulse)) return;
                AuraPulse.apply(receiver, grant, gp, sourceId);
            }
        }
    }

    private static void applyGrantToMob(LivingEntity mob, UUID sourceId, String instanceKey,
                                        PlayerEffect aura, EffectContext params) {
        Effect grant = AuraParams.grantEffect(params);
        if (grant == null) return;
        EffectContext gp = AuraParams.grantParams(params);
        switch (grant) {
            case ADD_ATTRIBUTE -> applyAttributeDerived(mob, sourceId, instanceKey, gp, aura.level());
            case ADD_POTION -> applyPotionDerived(mob, sourceId, instanceKey, gp,
                    AuraParams.grantPulseSec(params));
            default -> AuraPulse.apply(mob, grant, gp);
        }
    }

    private static void applyAttributeDerived(LivingEntity le, UUID sourceId, String instanceKey,
                                              EffectContext gp, int level) {
        Attribute attr = resolveAttr(gp);
        if (attr == null) return;
        AttributeInstance inst = le.getAttribute(attr);
        if (inst == null) return;
        String op = gp.getOrDefault(EffectKeys.OP, "FLAT");
        double value = gp.getOrDefault(EffectKeys.VALUE, 0.0) * level;
        AttributeModifier.Operation operation = AttributeModifierKeys.attributeOperation(op);
        NamespacedKey key = auraKey(sourceId, instanceKey, "attr/" + attr.getKey().getKey());
        double maxBefore = attr == Attribute.MAX_HEALTH ? inst.getValue() : 0;
        AttributeModifierKeys.replace(inst, key, value, operation);
        if (attr == Attribute.MAX_HEALTH && le instanceof Player player) {
            EffectService.bumpHealthForMaxGain(player, maxBefore, inst.getValue());
        }
    }

    private static final int AURA_POTION_TICKS = 20 * 40;

    private static void applyPotionDerived(LivingEntity le, UUID sourceId, String instanceKey,
                                           EffectContext gp, int pulseSec) {
        // 2026-10-29：光环给的药水**不允许**活得比光环久（否则离开范围还在挂，玩家反馈"漂浮消不掉"）。
        // 上限 = 一次脉冲 + 1 秒缓冲；脉冲刷新期间持续生效，离开后最多 1 秒掉掉。
        int cap = Math.max(20, Math.max(1, pulseSec) * 20 + 20);
        List<BuffPotionSpec> pots = gp.get(EffectKeys.POTIONS);
        if (pots != null && !pots.isEmpty()) {
            for (BuffPotionSpec spec : pots) {
                if (spec == null || spec.type() == null) continue;
                // force：避免常驻 ADD_POTION / resync 残留的低 amp 挡住光环
                // 2026-10-29：时长必须吃 grant 里的 duration_ticks（之前恒 AURA_POTION_TICKS=40s，
                // 于是"1 秒漂浮/凋零"离开光环后还要挂 40 秒，玩家反馈"永远消不掉"）。
                int dur = Math.min(spec.durationTicks() > 0 ? spec.durationTicks() : AURA_POTION_TICKS, cap);
                le.addPotionEffect(new PotionEffect(spec.type(), dur, spec.amp(), false, true, true), true);
            }
            return;
        }
        PotionEffectType type = resolvePotion(gp);
        if (type == null) return;
        int amp = gp.getOrDefault(EffectKeys.AMP, 0);
        int dur = gp.getOrDefault(EffectKeys.DURATION_TICKS, 0);
        if (dur <= 0) dur = AURA_POTION_TICKS;
        dur = Math.min(dur, cap);
        le.addPotionEffect(new PotionEffect(type, dur, amp, false, true, true), true);
    }

    private static PotionEffectType resolvePotion(EffectContext gp) {
        PotionEffectType type = gp.get(EffectKeys.POTION);
        if (type != null) return type;
        String name = gp.get(EffectKeys.POTION_NAME);
        if (name == null || name.isBlank()) return null;
        type = io.mczju.maggoteers.util.GameRegistries.potionEffect(name);
        if (type != null) gp.put(EffectKeys.POTION, type);
        return type;
    }

    /** 用 rewards 池内最新定义补全 carrier_fx / grant（本局已存条目可能缺字段）。 */
    private static void syncAuraParamsFromCatalog(PlayerEffect aura) {
        io.mczju.maggoteers.reward.RewardService.findOptionById(aura.id()).ifPresent(canon -> {
            if (canon.params() == null) return;
            EffectContext live = aura.params();
            EffectContext src = canon.params();
            if (!live.has(EffectKeys.GRANT_EFFECT) && src.has(EffectKeys.GRANT_EFFECT)) {
                live.put(EffectKeys.GRANT_EFFECT, src.get(EffectKeys.GRANT_EFFECT));
            }
            if (!live.has(EffectKeys.GRANT_PARAMS) && src.get(EffectKeys.GRANT_PARAMS) != null) {
                live.put(EffectKeys.GRANT_PARAMS, src.get(EffectKeys.GRANT_PARAMS).copy());
            }
            if (!live.has(EffectKeys.CARRIER_FX) && src.get(EffectKeys.CARRIER_FX) != null) {
                live.put(EffectKeys.CARRIER_FX, src.get(EffectKeys.CARRIER_FX).copy());
            }
            if (!live.has(EffectKeys.MARK_FX) && src.get(EffectKeys.MARK_FX) != null) {
                live.put(EffectKeys.MARK_FX, src.get(EffectKeys.MARK_FX).copy());
            }
            EffectContext grant = live.get(EffectKeys.GRANT_PARAMS);
            EffectContext canonGrant = src.get(EffectKeys.GRANT_PARAMS);
            if (grant != null && canonGrant != null) {
                if (grant.get(EffectKeys.ATTR) == null && canonGrant.get(EffectKeys.ATTR) != null) {
                    grant.put(EffectKeys.ATTR, canonGrant.get(EffectKeys.ATTR));
                }
                if (!grant.has(EffectKeys.ATTR_NAME) && canonGrant.has(EffectKeys.ATTR_NAME)) {
                    grant.put(EffectKeys.ATTR_NAME, canonGrant.get(EffectKeys.ATTR_NAME));
                }
                if (grant.get(EffectKeys.POTION) == null && canonGrant.get(EffectKeys.POTION) != null) {
                    grant.put(EffectKeys.POTION, canonGrant.get(EffectKeys.POTION));
                }
                if (!grant.has(EffectKeys.POTION_NAME) && canonGrant.has(EffectKeys.POTION_NAME)) {
                    grant.put(EffectKeys.POTION_NAME, canonGrant.get(EffectKeys.POTION_NAME));
                }
                if (!grant.has(EffectKeys.OP) && canonGrant.has(EffectKeys.OP)) {
                    grant.put(EffectKeys.OP, canonGrant.get(EffectKeys.OP));
                }
                if (!grant.has(EffectKeys.VALUE) && canonGrant.has(EffectKeys.VALUE)) {
                    grant.put(EffectKeys.VALUE, canonGrant.get(EffectKeys.VALUE));
                }
                if (!grant.has(EffectKeys.AMOUNT) && canonGrant.has(EffectKeys.AMOUNT)) {
                    grant.put(EffectKeys.AMOUNT, canonGrant.get(EffectKeys.AMOUNT));
                }
                if (!grant.has(EffectKeys.AMP) && canonGrant.has(EffectKeys.AMP)) {
                    grant.put(EffectKeys.AMP, canonGrant.get(EffectKeys.AMP));
                }
                if ((!grant.has(EffectKeys.POTIONS) || grant.get(EffectKeys.POTIONS) == null
                        || grant.get(EffectKeys.POTIONS).isEmpty())
                        && canonGrant.get(EffectKeys.POTIONS) != null) {
                    grant.put(EffectKeys.POTIONS, java.util.List.copyOf(canonGrant.get(EffectKeys.POTIONS)));
                }
            }
        });
    }

    private static Attribute resolveAttr(EffectContext gp) {
        Attribute attr = gp.get(EffectKeys.ATTR);
        if (attr != null) return attr;
        String name = gp.get(EffectKeys.ATTR_NAME);
        if (name == null || name.isBlank()) return null;
        attr = io.mczju.maggoteers.util.GameRegistries.attribute(name);
        if (attr != null) gp.put(EffectKeys.ATTR, attr);
        return attr;
    }

    private static NamespacedKey auraKey(UUID sourceId, String instanceKey, String suffix) {
        return AttributeModifierKeys.pluginKey(MaggoteersPlugin.getInstance(),
                "aura/" + sourceId + "/" + instanceKey + "/" + suffix);
    }

    private static void revertLink(AbstractGame game, String link) {
        if (link.startsWith("p:")) {
            UUID receiver = UUID.fromString(link.substring(2, link.indexOf("|s:")));
            Player p = Bukkit.getPlayer(receiver);
            if (p != null) stripAuraDerived(p, link);
        } else if (link.startsWith("m:")) {
            UUID mobId = UUID.fromString(link.substring(2, link.indexOf("|s:")));
            Entity e = Bukkit.getEntity(mobId);
            if (e instanceof LivingEntity le) stripAuraDerived(le, link);
        }
    }

    private static void stripAuraDerived(LivingEntity le, String link) {
        String instancePart = link.substring(link.indexOf("|i:") + 3);
        String instanceKey = instancePart;
        UUID sourceId = UUID.fromString(link.substring(link.indexOf("|s:") + 3, link.indexOf("|i:")));
        String prefix = AttributeModifierKeys.sanitizeKeyPath(
                "aura/" + sourceId + "/" + instanceKey + "/");
        DerivedViewAttributes.stripPluginModifiers(
                le, MaggoteersPlugin.getInstance().getName().toLowerCase(), prefix);
        // 药水：按 key 前缀无法精确剥除 amp，用 grant 类型重刷时覆盖；移除常见派生（下次 refresh 不再 add）
        // 可选：link 编码 potion type — v1 依赖实体死亡或 refresh 停止 add，旧 potion 自然过期
    }
}
