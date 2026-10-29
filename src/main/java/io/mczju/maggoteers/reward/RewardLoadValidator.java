package io.mczju.maggoteers.reward;

import io.mczju.maggoteers.effect.AttributeModifierKeys;
import io.mczju.maggoteers.effect.AuraParams;
import io.mczju.maggoteers.effect.BoundEquipParams;
import io.mczju.maggoteers.effect.BuffAreaTargets;
import io.mczju.maggoteers.effect.Effect;
import io.mczju.maggoteers.effect.EffectCompat;
import io.mczju.maggoteers.effect.EffectContext;
import io.mczju.maggoteers.effect.EffectKeys;
import io.mczju.maggoteers.effect.Fake255Rules;
import io.mczju.maggoteers.effect.GrantItemParams;
import io.mczju.maggoteers.effect.MagicEffectParams;
import io.mczju.maggoteers.effect.Stack;
import io.mczju.maggoteers.effect.SummonParamsParser;
import io.mczju.maggoteers.effect.Trigger;
import io.mczju.maggoteers.effect.TriggeredGrantAttribute;
import io.mczju.maggoteers.effect.VirtualStats;
import io.mczju.maggoteers.item.ItemService;
import org.bukkit.attribute.Attribute;
import org.bukkit.potion.PotionEffectType;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

/** Load-time validation for {@code rewards.yml} STAT options (config magic abilities P3). */
public final class RewardLoadValidator {

    private static final Logger LOG = Logger.getLogger("Maggoteers");

    /**
     * 内容可用的 fireTrigger（2026-10-26 起与 {@link Trigger} 全集一致：{@code ON_TICK_1S}/{@code ON_REVIVE}
     * 已从枚举删除，计时改走 {@code counter{count: tick}}）。保留白名单是有意的"人工闸门"：
     * 将来新增 trigger 时必须显式决定它是否对奖励开放（{@code RewardLoadValidationTest} 会断言本集合 == 枚举全集，
     * 新增值不同步登记即红）。
     */
    private static final Set<Trigger> ALLOWED_FIRE = Set.of(
            Trigger.ON_KILL,
            Trigger.ON_DAMAGE_DEALT,
            Trigger.ON_DAMAGE_TAKEN,
            Trigger.ON_DEATH,
            Trigger.ON_BOSS_KILL,
            Trigger.ON_WAVE_CLEAR,
            Trigger.ON_ACT_ENTER,
            Trigger.ON_SPEND,
            Trigger.ON_COUNTER
    );

    /** 奖励/武器内容允许写的 fireTrigger 集合（只读快照，供测试与文档核对）。 */
    public static Set<Trigger> allowedFireTriggers() {
        return ALLOWED_FIRE;
    }

    private static final Set<Trigger> GRANT_SUMMON_TRIGGERS = Set.of(
            Trigger.ON_WAVE_CLEAR,
            Trigger.ON_ACT_ENTER,
            Trigger.ON_KILL,
            Trigger.ON_DAMAGE_DEALT,
            Trigger.ON_DAMAGE_TAKEN,
            Trigger.ON_DEATH,
            Trigger.ON_BOSS_KILL,       // 2026-10-27：击杀 Boss 发放（霜晶树）
            Trigger.ON_COUNTER          // 1.3.2：计数器计满发放物品（紫水晶茄子：每击杀 50 敌获得 1 金币）
    );

    private RewardLoadValidator() {}

    public record ValidationResult(Optional<String> skipReason, EffectContext normalizedParams) {}

    /**
     * @return empty if the option may be loaded; otherwise a reason to warn+skip
     */
    public static Optional<String> validateStatOption(RewardOption opt) {
        if (opt == null || opt.category() != RewardOption.Category.STAT) {
            if (opt != null && opt.isBundle()) {
                return validateBundleOption(opt);
            }
            return Optional.empty();
        }
        Trigger fire = opt.trigger();
        // 效果 × 触发通道：玩家被动只支持 executeEffect 的 11 个 effect（+ 5 个"只能常驻"）。
        // 其余（DAMAGE/SALVO/BARRAGE/TELEPORT/DISPLACE/PERMANENT_ATTRIBUTE/COUNTER_ARM/SOUND/
        // PARTICLE_RING/CLEAR_POTIONS）只有怪物施法或武器 use_ability 能执行 —— 写进奖励会静默无效果。
        if (opt.effect() != null && !EffectCompat.expressibleForPlayerPassive(opt.effect())) {
            return Optional.of("effect " + opt.effect()
                    + " 仅怪物/武器路径可用，玩家奖励里会静默无效果（允许：" + EffectCompat.PLAYER_TRIGGERED
                    + " 或常驻：" + EffectCompat.PERMANENT_ONLY + "）");
        }
        if (opt.effect() == Effect.GRANT_ITEM || opt.effect() == Effect.SUMMON) {
            return validateGrantSummon(opt);
        }
        if (fire != null && !ALLOWED_FIRE.contains(fire)) {
            return Optional.of("forbidden fireTrigger: " + fire);
        }
        if (fire == Trigger.ON_COUNTER) {
            if (opt.params() == null || opt.params().get(EffectKeys.COUNTER_SPEC) == null) {
                return Optional.of("ON_COUNTER requires params.counter");
            }
        }
        if (opt.effect() == Effect.ADD_ATTRIBUTE && opt.params() != null
                && TriggeredGrantAttribute.isRevoke(opt.params())) {
            return validateRevokeGrants(opt);
        }
        if (opt.effect() == Effect.AURA && (fire != null || opt.expiryTrigger() != null)) {
            return Optional.of("AURA reward must not have trigger or expiry");
        }
        if (opt.effect() == Effect.BOUND_EQUIP) {
            if (fire != null || opt.expiryTrigger() != null) {
                return Optional.of("BOUND_EQUIP reward must not have trigger or expiry");
            }
            if (opt.stack() != Stack.UPGRADE_LEVEL) {
                return Optional.of("BOUND_EQUIP requires stack UPGRADE_LEVEL");
            }
            int upgradeMax = opt.upgradeMax();
            if (upgradeMax <= 0) {
                return Optional.of("BOUND_EQUIP requires upgrade_max > 0");
            }
            Optional<String> paramsErr = BoundEquipParams.validate(opt.params(), upgradeMax)
                    .map(msg -> "BOUND_EQUIP " + msg);
            if (paramsErr.isPresent()) {
                return paramsErr;
            }
            if (io.mczju.maggoteers.MaggoteersPlugin.getInstance() != null && opt.params() != null) {
                java.util.Map<Integer, String> byLevel = opt.params().get(EffectKeys.ITEMS_BY_LEVEL);
                if (byLevel != null) {
                    for (String itemId : byLevel.values()) {
                        if (itemId != null && !itemId.isBlank() && !ItemService.hasItem(itemId)) {
                            return Optional.of("BOUND_EQUIP unknown item: " + itemId);
                        }
                    }
                }
            }
            return Optional.empty();
        }
        Optional<String> opErr = validateAttributeOp(opt);
        if (opErr.isPresent()) {
            return opErr;
        }
        Optional<String> magic = validateMagicDamagePercent(opt);
        if (magic.isPresent()) {
            return magic;
        }
        Optional<String> cooldownReduction = validateReloadSpeed(opt);
        if (cooldownReduction.isPresent()) {
            return cooldownReduction;
        }
        Optional<String> immunity = validatePotionImmunity(opt);
        if (immunity.isPresent()) {
            return immunity;
        }
        Optional<String> fake255 = validateNoFake255(opt);
        if (fake255.isPresent()) {
            return fake255;
        }
        if (opt.effect() == Effect.HEAL_AREA) {
            return MagicEffectParams.validateHealAreaTargets(opt.params())
                    .map(msg -> "HEAL_AREA " + msg);
        }
        if (opt.effect() == Effect.BUFF_AREA) {
            return validateAndNormalizeBuffArea(opt).skipReason();
        }
        if (opt.effect() == Effect.DISABLE_AI) {
            return validateAndNormalizeDisableAi(opt).skipReason();
        }
        Optional<String> dynamic = validateDynamicAttribute(opt);
        if (dynamic.isPresent()) {
            return dynamic;
        }
        Optional<String> mhp = validateMaxHealthPerRevive(opt);
        if (mhp.isPresent()) {
            return mhp;
        }
        Optional<String> swap = validateSwapAttackMagic(opt);
        if (swap.isPresent()) {
            return swap;
        }
        return Optional.empty();
    }

    /** DYNAMIC_ATTRIBUTE 允许的动态来源（累计类走"每 threshold 次折叠一层"）。 */
    private static final Set<String> DYNAMIC_SOURCES = Set.of(
            "coin_count", "revive_zero",
            "kills", "hits_taken", "deaths", "waves_cleared", "acts_cleared");

    /** 需要 {@code params.per_unit} 的来源（按次数/枚数线性贡献）。 */
    private static final Set<String> DYNAMIC_PER_UNIT_SOURCES = Set.of(
            "coin_count", "kills", "hits_taken", "deaths", "waves_cleared", "acts_cleared");

    /** DYNAMIC_ATTRIBUTE：常驻、source 合法、按 source 带必填数值键；op/attr 约束复用 ADD_ATTRIBUTE 校验。 */
    private static Optional<String> validateDynamicAttribute(RewardOption opt) {
        if (opt.effect() != Effect.DYNAMIC_ATTRIBUTE || opt.params() == null) {
            return Optional.empty();
        }
        if (opt.trigger() != null) {
            return Optional.of("DYNAMIC_ATTRIBUTE must not have trigger");
        }
        if (opt.expiryTrigger() != null) {
            return Optional.of("DYNAMIC_ATTRIBUTE must not have expiry");
        }
        String source = opt.params().get(EffectKeys.SOURCE);
        if (source == null || source.isBlank()) {
            return Optional.of("DYNAMIC_ATTRIBUTE requires params.source (" + DYNAMIC_SOURCES + ")");
        }
        if (!DYNAMIC_SOURCES.contains(source)) {
            return Optional.of("DYNAMIC_ATTRIBUTE unknown source: " + source);
        }
        if (DYNAMIC_PER_UNIT_SOURCES.contains(source) && !opt.params().has(EffectKeys.PER_UNIT)) {
            return Optional.of("DYNAMIC_ATTRIBUTE source=" + source + " requires params.per_unit");
        }
        if ("revive_zero".equals(source) && !opt.params().has(EffectKeys.VALUE)) {
            return Optional.of("DYNAMIC_ATTRIBUTE source=revive_zero requires params.value");
        }
        if (opt.params().has(EffectKeys.THRESHOLD)) {
            int threshold = opt.params().getOrDefault(EffectKeys.THRESHOLD, 0);
            if (threshold < 1) {
                return Optional.of("DYNAMIC_ATTRIBUTE threshold must be >= 1 (default 10)");
            }
        }
        return Optional.empty();
    }

    /** MAX_HEALTH_PER_REVIVE：常驻、value>0（每失去一次复活获得的生命上限）。 */
    private static Optional<String> validateMaxHealthPerRevive(RewardOption opt) {
        if (opt.effect() != Effect.MAX_HEALTH_PER_REVIVE) {
            return Optional.empty();
        }
        if (opt.trigger() != null || opt.expiryTrigger() != null) {
            return Optional.of("MAX_HEALTH_PER_REVIVE must be permanent (no trigger/expiry)");
        }
        double value = opt.params() == null ? 0.0 : opt.params().getOrDefault(EffectKeys.VALUE, 0.0);
        if (value <= 0) {
            return Optional.of("MAX_HEALTH_PER_REVIVE requires params.value > 0");
        }
        return Optional.empty();
    }

    /** SWAP_ATTACK_MAGIC（认知失调）：常驻、无 params（读时互换物攻/魔攻，不改变参数）。 */
    private static Optional<String> validateSwapAttackMagic(RewardOption opt) {
        if (opt.effect() != Effect.SWAP_ATTACK_MAGIC) {
            return Optional.empty();
        }
        if (opt.trigger() != null || opt.expiryTrigger() != null) {
            return Optional.of("SWAP_ATTACK_MAGIC must be permanent (no trigger/expiry)");
        }
        return Optional.empty();
    }

    public static Optional<String> chestBoundEquipConflict(
            List<RewardOption> alreadyAccepted, RewardOption candidate) {
        if (candidate == null || candidate.effect() != Effect.BOUND_EQUIP) return Optional.empty();
        EffectContext params = candidate.params();
        String slot = params == null ? null : params.get(EffectKeys.SLOT);
        if (slot == null || !BoundEquipParams.SLOT_CHEST.equalsIgnoreCase(slot)) {
            return Optional.empty();
        }
        // Same id may repeat across act pools (UPGRADE_LEVEL draw, like collectibles).
        // Different CHEST BOUND_EQUIP ids still conflict (one bound chestpiece type).
        boolean otherIdExists = alreadyAccepted.stream().anyMatch(o ->
                o.effect() == Effect.BOUND_EQUIP
                        && o.params() != null
                        && BoundEquipParams.SLOT_CHEST.equalsIgnoreCase(
                                String.valueOf(o.params().get(EffectKeys.SLOT)))
                        && !java.util.Objects.equals(o.id(), candidate.id()));
        return otherIdExists
                ? Optional.of("duplicate BOUND_EQUIP slot CHEST: " + candidate.id())
                : Optional.empty();
    }

    private static Optional<String> validateGrantSummon(RewardOption opt) {
        Trigger fire = opt.trigger();
        if (fire == null) {
            return Optional.of("missing trigger");
        }
        if (!GRANT_SUMMON_TRIGGERS.contains(fire)) {
            return Optional.of("forbidden fireTrigger: " + fire);
        }
        EffectContext params = opt.params() == null ? new EffectContext() : opt.params();
        if (fire == Trigger.ON_COUNTER && params.get(EffectKeys.COUNTER_SPEC) == null) {
            return Optional.of("ON_COUNTER requires params.counter");
        }
        if (opt.effect() == Effect.GRANT_ITEM) {
            if (GrantItemParams.parse(params).isEmpty()) {
                return Optional.of("GRANT_ITEM missing item");
            }
            return Optional.empty();
        }
        Optional<String> err = SummonParamsParser.validate(params, fire, false);
        if (err.isPresent()) {
            return Optional.of("SUMMON " + err.get());
        }
        if (io.mczju.maggoteers.MaggoteersPlugin.getInstance() != null) {
            String entity = params.get(EffectKeys.ENTITY);
            if (SummonParamsParser.resolveEntity(entity).isEmpty()) {
                return Optional.of("SUMMON unknown entity: " + entity);
            }
        }
        return Optional.empty();
    }

    /** Validates BUFF_AREA and returns normalized params (mark_on coercion). */
    public static ValidationResult validateAndNormalizeBuffArea(RewardOption opt) {
        if (opt == null || opt.effect() != Effect.BUFF_AREA) {
            return new ValidationResult(Optional.empty(), opt == null ? null : opt.params());
        }
        EffectContext params = opt.params() == null ? new EffectContext() : opt.params().copy();
        params = MagicEffectParams.withDefaults(Effect.BUFF_AREA, params);

        Optional<String> general = MagicEffectParams.validateBuffArea(params, opt.trigger(), false);
        if (general.isPresent()) {
            return new ValidationResult(general, params);
        }

        Trigger fire = opt.trigger();
        String targets = params.get(EffectKeys.TARGETS);
        if (targets != null && BuffAreaTargets.TARGET_ATTACKER.equalsIgnoreCase(targets)) {
            if (fire == Trigger.ON_KILL || fire == Trigger.ON_DAMAGE_DEALT) {
                return new ValidationResult(
                        Optional.of("targets=attacker incompatible with " + fire), params);
            }
        }
        if (targets != null && BuffAreaTargets.TARGET_HIT_TARGET.equalsIgnoreCase(targets)) {
            if (fire == Trigger.ON_DAMAGE_TAKEN) {
                return new ValidationResult(
                        Optional.of("targets=hit_target incompatible with ON_DAMAGE_TAKEN"), params);
            }
            if (fire == Trigger.ON_KILL) {
                LOG.warning("rewards.yml 选项 " + opt.id()
                        + " ON_KILL + targets=hit_target：尸体通常无法上药，mark 仍可用");
            }
        }

        String markOn = params.get(EffectKeys.MARK_ON);
        if (markOn != null && !markOn.isBlank()) {
            String m = markOn.toLowerCase(Locale.ROOT);
            if ("attacker".equals(m) && (fire == Trigger.ON_KILL || fire == Trigger.ON_DAMAGE_DEALT)) {
                LOG.warning("rewards.yml 选项 " + opt.id()
                        + " mark_on=attacker 与 " + fire + " 不兼容，已强制为 none");
                params.put(EffectKeys.MARK_ON, "none");
            } else if ("hit_target".equals(m) && fire == Trigger.ON_DAMAGE_TAKEN) {
                LOG.warning("rewards.yml 选项 " + opt.id()
                        + " mark_on=hit_target 与 ON_DAMAGE_TAKEN 不兼容，已强制为 none");
                params.put(EffectKeys.MARK_ON, "none");
            } else if (!Set.of("none", "hit_target", "attacker").contains(m)) {
                LOG.warning("rewards.yml 选项 " + opt.id() + " 未知 mark_on=" + markOn + "，视为 none");
                params.put(EffectKeys.MARK_ON, "none");
            }
        }

        return new ValidationResult(Optional.empty(), params);
    }

    /** Validates DISABLE_AI and returns normalized params (mark_on coercion). */
    public static ValidationResult validateAndNormalizeDisableAi(RewardOption opt) {
        if (opt == null || opt.effect() != Effect.DISABLE_AI) {
            return new ValidationResult(Optional.empty(), opt == null ? null : opt.params());
        }
        EffectContext params = opt.params() == null ? new EffectContext() : opt.params().copy();
        params = MagicEffectParams.withDefaults(Effect.DISABLE_AI, params);

        Optional<String> general = MagicEffectParams.validateDisableAi(params, opt.trigger(), false);
        if (general.isPresent()) {
            return new ValidationResult(general, params);
        }

        Trigger fire = opt.trigger();
        String markOn = params.get(EffectKeys.MARK_ON);
        if (markOn != null && !markOn.isBlank()) {
            String m = markOn.trim().toLowerCase(Locale.ROOT);
            if ("attacker".equals(m) && fire != Trigger.ON_DAMAGE_TAKEN) {
                LOG.warning("rewards.yml 选项 " + opt.id()
                        + " mark_on=attacker 与 " + fire + " 不兼容，已强制为 none");
                params.put(EffectKeys.MARK_ON, "none");
            } else if ("hit_target".equals(m) && fire == Trigger.ON_DAMAGE_TAKEN) {
                LOG.warning("rewards.yml 选项 " + opt.id()
                        + " mark_on=hit_target 与 ON_DAMAGE_TAKEN 不兼容，已强制为 none");
                params.put(EffectKeys.MARK_ON, "none");
            } else if (!Set.of("none", "hit_target", "attacker").contains(m)) {
                LOG.warning("rewards.yml 选项 " + opt.id() + " 未知 mark_on=" + markOn + "，视为 none");
                params.put(EffectKeys.MARK_ON, "none");
            }
        }

        return new ValidationResult(Optional.empty(), params);
    }

    private static Optional<String> validateBundleOption(RewardOption opt) {
        if (opt.grants().isEmpty()) {
            return Optional.of("bundle grants empty");
        }
        for (RewardOption grant : opt.grants()) {
            if (grant.category() == RewardOption.Category.STAT) {
                Optional<String> err = validateStatOption(grant);
                if (err.isPresent()) return err;
            }
        }
        return validateBundleGrantReferences(opt);
    }

    private static Optional<String> validateRevokeGrants(RewardOption opt) {
        if (opt.trigger() == null) {
            return Optional.of("REVOKE_GRANTS requires trigger");
        }
        String sourceId = opt.params().get(EffectKeys.SOURCE_ID);
        if (sourceId == null || sourceId.isBlank()) {
            return Optional.of("REVOKE_GRANTS requires source_id");
        }
        if (opt.params().get(EffectKeys.ATTR) != null || opt.params().get(EffectKeys.ATTR_NAME) != null) {
            return Optional.of("REVOKE_GRANTS must not have attr");
        }
        if (opt.params().has(EffectKeys.VALUE)) {
            return Optional.of("REVOKE_GRANTS must not have value");
        }
        return Optional.empty();
    }

    private static Optional<String> validateBundleGrantReferences(RewardOption bundle) {
        java.util.Set<String> grantIds = new java.util.HashSet<>();
        for (RewardOption g : bundle.grants()) {
            grantIds.add(g.id());
        }
        for (RewardOption g : bundle.grants()) {
            if (g.effect() != Effect.ADD_ATTRIBUTE || g.params() == null) continue;
            if (!TriggeredGrantAttribute.isRevoke(g.params())) continue;
            String sid = g.params().get(EffectKeys.SOURCE_ID);
            if (sid != null && !sid.isBlank() && !grantIds.contains(sid)) {
                return Optional.of("REVOKE source_id not in bundle grants: " + sid);
            }
        }
        return Optional.empty();
    }

    private static Optional<String> validateAttributeOp(RewardOption opt) {
        if ((opt.effect() != Effect.ADD_ATTRIBUTE && opt.effect() != Effect.DYNAMIC_ATTRIBUTE)
                || opt.params() == null) {
            return Optional.empty();
        }
        if (TriggeredGrantAttribute.isRevoke(opt.params())) {
            return Optional.empty(); // REVOKE_GRANTS 由 validateRevokeGrants 处理
        }
        String op = opt.params().getOrDefault(EffectKeys.OP, "FLAT");
        if (AttributeModifierKeys.isValidAttributeOp(op)) {
            return Optional.empty();
        }
        // 系数类虚拟属性（HEAL / RANGE / COOLDOWN_COEFFICIENT，基础 100）用 op: ADD 表示百分点加算
        if ("ADD".equalsIgnoreCase(op.trim())) {
            String attrName = opt.params().get(EffectKeys.ATTR_NAME);
            if (attrName != null && VirtualStats.isCoefficient(attrName)) {
                return Optional.empty();
            }
        }
        return Optional.of("ADD_ATTRIBUTE unknown op: " + op);
    }

    private static Optional<String> validateMagicDamagePercent(RewardOption opt) {
        if ((opt.effect() != Effect.ADD_ATTRIBUTE && opt.effect() != Effect.DYNAMIC_ATTRIBUTE)
                || opt.params() == null) {
            return Optional.empty();
        }
        EffectContext params = opt.params();
        String attrName = params.get(EffectKeys.ATTR_NAME);
        if (attrName == null) {
            Attribute attr = params.get(EffectKeys.ATTR);
            if (attr != null) {
                attrName = attr.getKey().getKey().toUpperCase();
            }
        }
        if (!VirtualStats.isMagicDamage(attrName)) {
            return Optional.empty();
        }
        String op = params.getOrDefault(EffectKeys.OP, "FLAT");
        // PERCENT = 加法累加（默认）；MULTIPLY = 独立最终乘算（诅咒之刃，value 3.0 → ×(1+3)=×4）
        if (!"PERCENT".equalsIgnoreCase(op) && !"MULTIPLY".equalsIgnoreCase(op)) {
            return Optional.of("MAGIC_DAMAGE requires op: PERCENT or MULTIPLY");
        }
        return Optional.empty();
    }

    /**
     * 2026-10-28：装填速度（{@code RELOAD_SPEED}）是**加算系数**属性：只认 op ADD/缺省，值可正可负（诅咒）。
     * <p>旧的 {@code COOLDOWN_REDUCTION}（乘积）已废弃；如果内容里还写着它，直接报错提示迁移。
     */
    private static Optional<String> validateReloadSpeed(RewardOption opt) {
        if ((opt.effect() != Effect.ADD_ATTRIBUTE && opt.effect() != Effect.DYNAMIC_ATTRIBUTE)
                || opt.params() == null) {
            return Optional.empty();
        }
        EffectContext params = opt.params();
        String attrName = params.get(EffectKeys.ATTR_NAME);
        if (attrName == null) {
            Attribute attr = params.get(EffectKeys.ATTR);
            if (attr != null) {
                attrName = attr.getKey().getKey().toUpperCase();
            }
        }
        if ("COOLDOWN_REDUCTION".equalsIgnoreCase(attrName) || "COOLDOWN_COEFFICIENT".equalsIgnoreCase(attrName)) {
            return Optional.of("COOLDOWN_REDUCTION/COOLDOWN_COEFFICIENT 已废弃，请改用 RELOAD_SPEED（op: ADD）");
        }
        if (!VirtualStats.isReloadSpeed(attrName)) {
            return Optional.empty();
        }
        String op = params.getOrDefault(EffectKeys.OP, "ADD");
        if (!"ADD".equalsIgnoreCase(op) && !"PERCENT".equalsIgnoreCase(op) && !"FLAT".equalsIgnoreCase(op)) {
            return Optional.of("RELOAD_SPEED requires op ADD (or omitted)");
        }
        return Optional.empty();
    }

    private static Optional<String> validatePotionImmunity(RewardOption opt) {
        if (opt.effect() != Effect.ADD_POTION || opt.params() == null) {
            return Optional.empty();
        }
        if (!Boolean.TRUE.equals(opt.params().get(EffectKeys.IMMUNITY))) {
            return Optional.empty();
        }
        if (opt.trigger() != null) {
            return Optional.of("immunity ADD_POTION must not have trigger");
        }
        if (opt.expiryTrigger() != null) {
            return Optional.of("immunity ADD_POTION must not have expiry");
        }
        PotionEffectType type = opt.params().get(EffectKeys.POTION);
        if (type == null) {
            return Optional.of("immunity ADD_POTION missing potion");
        }
        if (type.isInstant()) {
            return Optional.of("immunity forbids instant potion: " + type.getKey());
        }
        return Optional.empty();
    }

    private static Optional<String> validateNoFake255(RewardOption opt) {
        if (opt.params() == null) {
            return Optional.empty();
        }
        EffectContext p = opt.params();
        boolean immunity = Boolean.TRUE.equals(p.get(EffectKeys.IMMUNITY));
        if (opt.effect() == Effect.ADD_POTION && p.get(EffectKeys.POTION) != null) {
            int amp = p.getOrDefault(EffectKeys.AMP, 0);
            int dur = p.getOrDefault(EffectKeys.DURATION_TICKS, 0);
            if (Fake255Rules.isFakeClear(amp, dur, immunity)) {
                return Optional.of("forbidden fake amp-255 clear/immunity; use immunity: true or clear_potions");
            }
        }
        // Nested potions[] already parsed into BuffPotionSpec — check amp/duration on specs
        java.util.List<io.mczju.maggoteers.effect.BuffPotionSpec> pots = p.get(EffectKeys.POTIONS);
        if (pots != null) {
            for (int i = 0; i < pots.size(); i++) {
                var spec = pots.get(i);
                if (Fake255Rules.isFakeClear(spec.amp(), spec.durationTicks(), false)) {
                    return Optional.of("params.potions[" + i + "] forbidden fake amp-255; use clear_potions");
                }
            }
        }
        return Optional.empty();
    }
}
