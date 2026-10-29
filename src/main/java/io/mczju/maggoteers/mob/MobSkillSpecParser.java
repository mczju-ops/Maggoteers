package io.mczju.maggoteers.mob;

import io.mczju.maggoteers.effect.Effect;
import io.mczju.maggoteers.effect.EffectContext;
import io.mczju.maggoteers.util.GameDifficulty;
import io.mczju.maggoteers.util.GameRegistries;
import io.mczju.maggoteers.effect.EffectKey;
import io.mczju.maggoteers.effect.EffectKeys;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** 解析 mob_skills.yml 的单条技能（Map→MobSkillSpec）。纯逻辑，可单测。 */
public final class MobSkillSpecParser {

    private MobSkillSpecParser() {}

    public static Optional<MobSkillSpec> parse(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) {
            return Optional.empty();
        }
        String id = m.get("id") == null ? "" : String.valueOf(m.get("id")).trim();
        if (id.isBlank()) {
            return Optional.empty();
        }
        MobTrigger trigger = enumOf(m.get("trigger"), MobTrigger.class);
        if (trigger == null) {
            return Optional.empty();
        }
        if (trigger == MobTrigger.COUNTER && m.get("counter") == null) {
            return Optional.empty();                       // counter 触发必须有 counter 块
        }
        int cooldownSec = m.get("cooldown_sec") instanceof Number n ? n.intValue() : 0;
        if (trigger == MobTrigger.TICK && cooldownSec <= 0) {
            return Optional.empty();                       // 光环类必须有冷却，否则每 tick 触发
        }
        boolean invisible = Boolean.parseBoolean(String.valueOf(m.get("invisible")));
        MobCounterSpec counter = parseCounter(m.get("counter"));
        MobCondition condition = parseCondition(m.get("condition"));
        List<MobEffectSpec> effects = parseEffects(m.get("effects"));
        GameDifficulty difficulty = m.get("difficulty") == null ? null
                : GameDifficulty.parse(String.valueOf(m.get("difficulty"))).orElse(null);
        DamageReduction reduction = parseDamageReduction(m.get("damage_reduction"));
        // chance（0~1）：未写 = 1.0（必触发）；对齐 InfernalMobs 的 chance 语义（refrigerate 25%）
        double chance = m.get("chance") instanceof Number n ? n.doubleValue() : 1.0;
        // D1（2026-10-26）：once = 一局只触发一次（配 hp_below 做"血线阶段"）
        boolean once = Boolean.parseBoolean(String.valueOf(m.get("once")));
        // D2（2026-10-26）：伤害门（单次上限 / 真免疫）
        MobDamageGate gate = parseDamageGate(m);
        // 2026-10-26：阶段限定（Boss 阶段技能）+ 永久关 AI
        java.util.Set<Integer> phases = new java.util.LinkedHashSet<>();
        if (m.get("phases") instanceof List<?> pl) {
            for (Object o : pl) if (o instanceof Number n) phases.add(n.intValue());
        } else if (m.get("phases") instanceof Number n) {
            phases.add(n.intValue());
        }
        boolean noAi = Boolean.parseBoolean(String.valueOf(m.get("no_ai")));
        return Optional.of(new MobSkillSpec(id, trigger, condition, invisible, counter, cooldownSec,
                effects, difficulty, reduction, chance, once, gate, phases, noAi));
    }

    /**
     * 伤害门（D2，2026-10-26 扩展）：{@code damage_cap} / {@code damage_cap_percent} /
     * {@code immune_damage} / {@code immune_all} / {@code immune_beyond_radius}（解析见 {@link MobDamageGate#parse}）。
     */
    private static MobDamageGate parseDamageGate(Map<?, ?> m) {
        return MobDamageGate.parse(m);
    }

    /** {@code damage_reduction: { type: magic|physical, percent: 0.5 }}（被动减伤，可无 effects）。 */
    private static DamageReduction parseDamageReduction(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) return null;
        String type = m.get("type") == null ? DamageReduction.PHYSICAL : String.valueOf(m.get("type"));
        double percent = m.get("percent") instanceof Number n ? n.doubleValue() : 0.5;
        if (percent <= 0) return null;
        return new DamageReduction(type, percent);
    }

    private static MobCounterSpec parseCounter(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) {
            return null;
        }
        String id = m.get("id") == null ? "" : String.valueOf(m.get("id")).trim();
        if (id.isBlank()) {
            return null;
        }
        MobTrigger count = enumOf(m.get("count"), MobTrigger.class);
        if (count == null) {
            return null;
        }
        int amount = m.get("amount") instanceof Number n ? n.intValue() : 1;
        MobTrigger resetOn = enumOf(m.get("reset"), MobTrigger.class);
        MobCondition condition = parseCondition(m.get("condition"));
        boolean requireArm = Boolean.parseBoolean(String.valueOf(m.get("armed")));
        return new MobCounterSpec(id, count, amount, resetOn, condition, requireArm);
    }

    /** 供 {@code waves.yml} 的 {@code phases:} 复用同一套 effect 解析（2026-10-26）。 */
    public static List<MobEffectSpec> parseEffectsPublic(Object raw) {
        return parseEffects(raw);
    }

    private static List<MobEffectSpec> parseEffects(Object raw) {
        List<MobEffectSpec> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            return out;
        }
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> em)) {
                continue;
            }
            Effect effect = resolveEffect(em.get("effect"));
            if (effect == null) {
                continue;
            }
            out.add(new MobEffectSpec(effect, parseEffectParams(effect, em)));
        }
        return List.copyOf(out);
    }

    private static Effect resolveEffect(Object raw) {
        String s = raw == null ? null : String.valueOf(raw).trim();
        if (s == null || s.isBlank()) return null;
        String u = s.toUpperCase(Locale.ROOT);
        try {
            return Effect.valueOf(u);                      // 统一名直达
        } catch (IllegalArgumentException e) {
            return switch (u) {                            // 旧 mob 枚举名（仅 3 个不同名）
                case "HEALTH" -> Effect.HEAL;
                case "ATTRIBUTE" -> Effect.ADD_ATTRIBUTE;
                case "POTION" -> Effect.ADD_POTION;
                default -> null;                           // 未知枚举：静默跳过（调用方 warning）
            };
        }
    }

    private static EffectContext parseEffectParams(Effect effect, Map<?, ?> em) {
        EffectContext ctx = new EffectContext();
        // 统一 schema：targets（复数，新）优先；旧 target（单数）兼容（值映射在 Task 7 消费端做）
        Object targetsRaw = em.containsKey("targets") ? em.get("targets") : em.get("target");
        if (targetsRaw != null) {
            ctx.put(EffectKeys.TARGETS, String.valueOf(targetsRaw).toLowerCase(Locale.ROOT));
        }
        // 参数字段：优先 params 子块，回落顶层（mob_skills.yml 两种写法皆可）
        Map<?, ?> pm = em.get("params") instanceof Map<?, ?> p ? p : em;
        for (var e : pm.entrySet()) {
            String k = String.valueOf(e.getKey());
            if ("effect".equals(k) || "target".equals(k) || "targets".equals(k)) {
                continue;
            }
            Object v = e.getValue();
            switch (k) {
                case "amount", "damage" -> putNum(ctx, k.equals("damage")
                        ? EffectKeys.DAMAGE : EffectKeys.AMOUNT, v);
                case "radius" -> putNum(ctx, EffectKeys.RADIUS, v);
                case "ray_length" -> putNum(ctx, EffectKeys.RAY_LENGTH, v);
                case "beam_radius" -> putNum(ctx, EffectKeys.BEAM_RADIUS, v);
                case "attr" -> putAttr(ctx, v);
                case "op" -> ctx.put(EffectKeys.OP, String.valueOf(v).toUpperCase(Locale.ROOT));
                case "value" -> putNum(ctx, EffectKeys.VALUE, v);
                case "potion" -> ctx.put(EffectKeys.POTION_NAME, String.valueOf(v));
                case "amp" -> putInt(ctx, EffectKeys.AMP, v);
                case "duration_ticks" -> putInt(ctx, EffectKeys.DURATION_TICKS, v);
                case "entity" -> ctx.put(EffectKeys.ENTITY, String.valueOf(v));
                case "count" -> putInt(ctx, EffectKeys.COUNT, v);
                case "homing_target" -> ctx.put(EffectKeys.HOMING_TARGET, String.valueOf(v));
                case "projectile_speed" -> putNum(ctx, EffectKeys.PROJECTILE_SPEED, v);
                case "force" -> putNum(ctx, EffectKeys.FORCE, v);
                case "upward" -> putNum(ctx, EffectKeys.UPWARD, v);
                case "particle" -> ctx.put(EffectKeys.PARTICLE_NAME, String.valueOf(v));
                case "sound" -> ctx.put(EffectKeys.SOUND_NAME, String.valueOf(v));
                case "sound_volume" -> putFloat(ctx, EffectKeys.SOUND_VOLUME, v);
                case "sound_pitch" -> putFloat(ctx, EffectKeys.SOUND_PITCH, v);
                case "delay_ticks" -> putInt(ctx, EffectKeys.DELAY_TICKS, v);
            case "warn_ticks" -> putInt(ctx, EffectKeys.WARN_TICKS, v);
            case "skills" -> {
                if (v instanceof List<?> sl) {
                    java.util.List<String> ids = new java.util.ArrayList<>();
                    for (Object o : sl) if (o != null) ids.add(String.valueOf(o));
                    if (!ids.isEmpty()) ctx.put(EffectKeys.SUMMON_SKILLS, ids);
                }
            }
            case "scale" -> putNum(ctx, EffectKeys.SCALE, v);
            case "hp_mult" -> putNum(ctx, EffectKeys.HP_MULT, v);
                case "interval_ticks" -> putInt(ctx, EffectKeys.INTERVAL_TICKS, v);
                case "repeats" -> putInt(ctx, EffectKeys.REPEATS, v);
                case "preset" -> ctx.put(EffectKeys.PRESET, String.valueOf(v));
                case "origin" -> ctx.put(EffectKeys.ORIGIN, String.valueOf(v).toLowerCase(Locale.ROOT));
                case "aim" -> ctx.put(EffectKeys.AIM, String.valueOf(v).toLowerCase(Locale.ROOT));
                case "anchor" -> ctx.put(EffectKeys.ANCHOR, String.valueOf(v).toLowerCase(Locale.ROOT));
                case "clear_potions", "potions" -> putPotionList(ctx, v);
                case "counter" -> ctx.put(EffectKeys.COUNTER_ID, String.valueOf(v));
                // 2026-10-26：Boss 读条大招 / 叠层 / 吞噬 / 场地传送
                case "stack_amp" -> ctx.put(EffectKeys.STACK_AMP, Boolean.parseBoolean(String.valueOf(v)));
                case "scale_by_potion" -> ctx.put(EffectKeys.SCALE_BY_POTION, String.valueOf(v));
                case "per_level" -> putNum(ctx, EffectKeys.PER_LEVEL, v);
                case "slow_percent" -> putNum(ctx, EffectKeys.SLOW_PERCENT, v);
                case "slow_ticks" -> putInt(ctx, EffectKeys.SLOW_TICKS, v);
                case "gain_potion" -> ctx.put(EffectKeys.GAIN_POTION, String.valueOf(v));
                case "per_stack_ticks" -> putInt(ctx, EffectKeys.PER_STACK_TICKS, v);
                default -> { }
            }
        }
        return ctx;
    }

    private static void putNum(EffectContext ctx, EffectKey<Double> key, Object v) {
        if (v instanceof Number n) {
            ctx.put(key, n.doubleValue());
        }
    }

    private static void putInt(EffectContext ctx, EffectKey<Integer> key, Object v) {
        if (v instanceof Number n) {
            ctx.put(key, n.intValue());
        }
    }

    private static void putFloat(EffectContext ctx, EffectKey<Float> key, Object v) {
        if (v instanceof Number n) {
            ctx.put(key, n.floatValue());
        }
    }

    @SuppressWarnings("unchecked")
    private static void putPotionList(EffectContext ctx, Object v) {
        if (!(v instanceof List<?> list)) return;
        java.util.List<org.bukkit.potion.PotionEffectType> out = new java.util.ArrayList<>();
        for (Object o : list) {
            org.bukkit.potion.PotionEffectType t = GameRegistries.potionEffect(String.valueOf(o));
            if (t != null) out.add(t);
        }
        if (!out.isEmpty()) ctx.put(EffectKeys.CLEAR_POTIONS, out);
    }

    private static void putAttr(EffectContext ctx, Object v) {
        String name = String.valueOf(v);
        ctx.put(EffectKeys.ATTR_NAME, name);
    }

    private static MobCondition parseCondition(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) {
            return null;
        }
        if (m.containsKey("and")) {
            return new MobCondition.And(parseChildren(m.get("and")));
        }
        if (m.containsKey("or")) {
            return new MobCondition.Or(parseChildren(m.get("or")));
        }
        if (m.containsKey("not")) {
            MobCondition child = parseCondition(m.get("not"));
            return child == null ? null : new MobCondition.Not(child);
        }
        if (m.containsKey("hold_item_pdc")) {
            return new MobCondition.HoldItemPdc(String.valueOf(m.get("hold_item_pdc")));
        }
        if (m.containsKey("player_in_radius")) {
            double r = m.get("player_in_radius") instanceof Number n ? n.doubleValue() : 0.0;
            return new MobCondition.PlayerInRadius(r);
        }
        if (m.containsKey("counter_running")) {
            return new MobCondition.CounterRunning(String.valueOf(m.get("counter_running")));
        }
        // D1（2026-10-26）：血线条件 —— condition: { hp_below: 0.5 }
        if (m.containsKey("hp_below")) {
            double r = m.get("hp_below") instanceof Number n ? n.doubleValue() : 0.5;
            return new MobCondition.HpBelow(r);
        }
        return null;
    }

    private static List<MobCondition> parseChildren(Object raw) {
        List<MobCondition> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            return out;
        }
        for (Object o : list) {
            MobCondition c = parseCondition(o);
            if (c != null) {
                out.add(c);
            }
        }
        return out;
    }

    private static <T extends Enum<T>> T enumOf(Object raw, Class<T> type) {
        if (raw == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, String.valueOf(raw).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
