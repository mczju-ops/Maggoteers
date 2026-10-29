package io.mczju.maggoteers.reward;

import io.mczju.maggoteers.effect.Effect;
import io.mczju.maggoteers.effect.EffectContext;
import io.mczju.maggoteers.effect.EffectKey;
import io.mczju.maggoteers.effect.EffectKeys;
import io.mczju.maggoteers.effect.Stack;
import io.mczju.maggoteers.effect.Trigger;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.attribute.Attribute;
import org.bukkit.potion.PotionEffectType;

import java.util.Map;

/**
 * 奖励项（§12.1）。
 * <ul>
 *   <li>STAT：{@code effect/params/stack/trigger} 描述一条 {@link io.mczju.maggoteers.effect.PlayerEffect}（trigger 缺省=常驻）。</li>
 *   <li>WEAPON/SUPPLY：{@code item/amount} 发物品。</li>
 * </ul>
 */
public record RewardOption(
        String id, String display, String description, Category category,
        String item, int amount,
        Trigger trigger, Effect effect, EffectContext params, Stack stack,
        Trigger expiryTrigger, int expiryCharges,
        boolean requiresUnlock, int unlockCost, boolean unique,
        int upgradeMax, java.util.List<RewardOption> grants
) {
    public enum Category { STAT, WEAPON, SUPPLY, BUNDLE }

    public RewardOption {
        if (amount < 1) amount = 1;
        if (stack == null) stack = Stack.ADD;
        grants = grants == null ? java.util.List.of() : java.util.List.copyOf(grants);
    }

    public boolean isBundle() {
        return !grants.isEmpty();
    }

    /** Returns a copy with replaced STAT params (load-time coercion). */
    public RewardOption withParams(EffectContext newParams) {
        return new RewardOption(
                id, display, description, category,
                item, amount,
                trigger, effect, newParams, stack,
                expiryTrigger, expiryCharges,
                requiresUnlock, unlockCost, unique,
                upgradeMax, grants);
    }

    public static RewardOption fromMap(Map<?, ?> m) {
        String id = str(m, "id");
        java.util.List<RewardOption> grants = parseGrants(m, id);
        Category cat;
        if (!grants.isEmpty()) {
            Object catRaw = m.get("category");
            cat = catRaw == null ? Category.BUNDLE
                    : Category.valueOf(String.valueOf(catRaw).toUpperCase());
        } else {
            cat = Category.valueOf(str(m, "category", "SUPPLY").toUpperCase());
        }
        Trigger trigger = optEnum(m, "trigger", Trigger.class);
        Effect effect = optEnum(m, "effect", Effect.class);
        EffectContext params = cat == Category.STAT || effect != null ? parseParams(m) : null;
        Stack stack = optEnum(m, "stack", Stack.class);
        boolean reqUnlock = bool(m, "requires_unlock");
        int unlockCost = num(m, "unlock_cost", 0);
        boolean unique = bool(m, "unique");
        int upgradeMax = num(m, "upgrade_max", 0);
        Trigger expiryTrigger = null;
        int expiryCharges = 0;
        Object expRaw = m.get("expiry");
        if (expRaw instanceof Map<?, ?> em) {
            expiryTrigger = optEnum(em, "trigger", Trigger.class);
            expiryCharges = num(em, "charges", 0);
        }
        return new RewardOption(
                id, str(m, "display"), str(m, "description"), cat,
                str(m, "item"), num(m, "amount", 1),
                trigger, effect, params, stack,
                expiryTrigger, expiryCharges,
                reqUnlock, unlockCost, unique,
                upgradeMax, grants);
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<RewardOption> parseGrants(Map<?, ?> parent, String parentId) {
        Object raw = parent.get("grants");
        if (!(raw instanceof java.util.List<?> list)) {
            return java.util.List.of();
        }
        java.util.List<RewardOption> out = new java.util.ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Object entry = list.get(i);
            if (!(entry instanceof Map<?, ?> gm)) continue;
            java.util.Map<Object, Object> copy = new java.util.HashMap<>(gm);
            if (!copy.containsKey("id") || String.valueOf(copy.get("id")).isBlank()) {
                copy.put("id", parentId + "_g" + i);
            }
            RewardOption grant = fromMap(copy);
            out.add(grant);
        }
        return java.util.List.copyOf(out);
    }

    /** STAT 或 bundle 内各 grant 是否可应用。 */
    public boolean isStatEffect() {
        if (isBundle()) {
            return grants.stream().allMatch(RewardOption::isSingleRewardValid);
        }
        return isSingleRewardValid();
    }

    boolean isSingleRewardValid() {
        if (category != Category.STAT) return true;
        if (effect == null) return false;
        if (effect == Effect.AURA) {
            return params != null && params.get(EffectKeys.GRANT_EFFECT) != null
                    && params.getOrDefault(EffectKeys.RADIUS, 0.0) > 0;
        }
        return true;
    }

    /** Bundle 或 STAT 是否可应用（含 WEAPON/SUPPLY 子项）。 */
    public boolean isApplicable() {
        if (isBundle()) {
            return !grants.isEmpty() && grants.stream().allMatch(RewardOption::isSingleRewardValid);
        }
        return isSingleRewardValid();
    }

    @SuppressWarnings("unchecked")
    private static EffectContext parseParams(Map<?, ?> m) {
        Object raw = m.get("params");
        if (!(raw instanceof Map<?, ?> pm)) return new EffectContext();
        EffectContext ctx = new EffectContext();
        for (var e : pm.entrySet()) {
            String k = String.valueOf(e.getKey());
            Object v = e.getValue();
            switch (k) {
                case "radius" -> ctx.put(EffectKeys.RADIUS, v instanceof Number n ? n.doubleValue() : 8.0);
                case "targets" -> ctx.put(EffectKeys.TARGETS, String.valueOf(v).toLowerCase());
                case "enemy_scope" -> ctx.put(EffectKeys.ENEMY_SCOPE, String.valueOf(v).toLowerCase());
                case "include_self" -> ctx.put(EffectKeys.INCLUDE_SELF, Boolean.parseBoolean(String.valueOf(v)));
                case "ray_length" -> ctx.put(EffectKeys.RAY_LENGTH, v instanceof Number n ? n.doubleValue() : 32.0);
                case "beam_radius" -> ctx.put(EffectKeys.BEAM_RADIUS, v instanceof Number n ? n.doubleValue() : 1.25);
                case "heal_amount" -> ctx.put(EffectKeys.HEAL_AMOUNT, v instanceof Number n ? n.doubleValue() : 0.0);
                case "grant_pulse_sec" -> ctx.put(EffectKeys.GRANT_PULSE_SEC,
                        v instanceof Number n ? n.intValue() : 1);
                case "grant" -> parseGrantBlock(ctx, v);
                case "carrier_fx" -> ctx.put(EffectKeys.CARRIER_FX, parseFxContext(v));
                case "mark_fx" -> ctx.put(EffectKeys.MARK_FX, parseFxContext(v));
                case "fx" -> ctx.put(EffectKeys.FX, parseFxContext(v));
                case "mark_on" -> ctx.put(EffectKeys.MARK_ON, String.valueOf(v).toLowerCase());
                case "potions" -> ctx.put(EffectKeys.POTIONS, io.mczju.maggoteers.effect.BuffPotionParser.parseList(v));
                case "clear_potions" -> {
                    var pr = io.mczju.maggoteers.effect.ClearPotionsParser.parse(v, "params.clear_potions");
                    if (pr.ok()) {
                        ctx.put(EffectKeys.CLEAR_POTIONS, pr.types());
                    }
                }
                case "immunity" -> ctx.put(EffectKeys.IMMUNITY, Boolean.parseBoolean(String.valueOf(v)));
                case "mark_head" -> ctx.put(EffectKeys.MARK_HEAD, Boolean.parseBoolean(String.valueOf(v)));
                case "item" -> ctx.put(EffectKeys.ITEM_ID, String.valueOf(v));
                case "entity" -> ctx.put(EffectKeys.ENTITY, String.valueOf(v));
                case "anchor" -> ctx.put(EffectKeys.ANCHOR, String.valueOf(v).toLowerCase());
                case "cleanup" -> ctx.put(EffectKeys.CLEANUP, String.valueOf(v).toLowerCase());
                case "duration_sec" -> ctx.put(EffectKeys.DURATION_SEC, v instanceof Number n ? n.intValue() : 0);
                case "offset_y" -> ctx.put(EffectKeys.OFFSET_Y, v instanceof Number n ? n.doubleValue() : 0.0);
                case "tamed" -> ctx.put(EffectKeys.TAMED, Boolean.parseBoolean(String.valueOf(v)));
                case "friendly_fire" -> ctx.put(EffectKeys.FRIENDLY_FIRE, Boolean.parseBoolean(String.valueOf(v)));
                case "attributes" -> ctx.put(EffectKeys.ATTRIBUTES, parseAttributesMap(v));
                case "projectile" -> ctx.put(EffectKeys.PROJECTILE, parseProjectileContext(v));
                case "homing_target" -> ctx.put(EffectKeys.HOMING_TARGET, String.valueOf(v));
                case "attr" -> putAttr(ctx, v);
                case "source_id" -> ctx.put(EffectKeys.SOURCE_ID, String.valueOf(v));
                case "source" -> ctx.put(EffectKeys.SOURCE, String.valueOf(v).toLowerCase());
                case "per_unit" -> ctx.put(EffectKeys.PER_UNIT, v instanceof Number n ? n.doubleValue() : 0.0);
                case "threshold" -> ctx.put(EffectKeys.THRESHOLD, v instanceof Number n ? n.intValue() : 0);
                case "op" -> ctx.put(EffectKeys.OP, String.valueOf(v).toUpperCase());
                case "value", "amount", "damage" -> {
                    EffectKey<Double> key = switch (k) {
                        case "amount" -> EffectKeys.AMOUNT;
                        case "damage" -> EffectKeys.DAMAGE;
                        default -> EffectKeys.VALUE;
                    };
                    ctx.put(key, v instanceof Number n ? n.doubleValue() : 0.0);
                }
                case "amp", "count", "duration_ticks", "min_revives" -> {
                    int iv = v instanceof Number n ? n.intValue() : 0;
                    if (k.equals("amp")) ctx.put(EffectKeys.AMP, iv);
                    else if (k.equals("count")) ctx.put(EffectKeys.COUNT, iv);
                    else if (k.equals("min_revives")) ctx.put(EffectKeys.MIN_REVIVES, iv);
                    else ctx.put(EffectKeys.DURATION_TICKS, iv);
                }
                case "potion" -> putPotion(ctx, v);
                case "counter" -> io.mczju.maggoteers.effect.CounterSpecParser.parse(v).ifPresent(spec -> {
                    ctx.put(EffectKeys.COUNTER_SPEC, spec);
                    ctx.put(EffectKeys.COUNTER_ID, spec.id());
                });
                case "slot" -> ctx.put(EffectKeys.SLOT, String.valueOf(v).toUpperCase());
                case "items_by_level" -> ctx.put(EffectKeys.ITEMS_BY_LEVEL, parseItemsByLevel(v));
                default -> { /* 忽略未知键 */ }
            }
        }
        return ctx;
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<Integer, String> parseItemsByLevel(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) return java.util.Map.of();
        java.util.Map<Integer, String> out = new java.util.HashMap<>();
        for (var e : m.entrySet()) {
            try {
                int level = Integer.parseInt(String.valueOf(e.getKey()));
                String id = e.getValue() == null ? null : String.valueOf(e.getValue());
                if (id != null && !id.isBlank()) {
                    out.put(level, id);
                }
            } catch (NumberFormatException ignored) { }
        }
        return java.util.Map.copyOf(out);
    }

    @SuppressWarnings("unchecked")
    private static void parseGrantBlock(EffectContext auraCtx, Object raw) {
        Map<?, ?> gm;
        if (raw instanceof Map<?, ?> map) {
            gm = map;
        } else if (raw instanceof org.bukkit.configuration.ConfigurationSection sec) {
            gm = sec.getValues(false);
        } else {
            return;
        }
        Effect grantEffect = safeEnum(Effect.class, gm.get("effect"));
        if (grantEffect == null) return;
        auraCtx.put(EffectKeys.GRANT_EFFECT, grantEffect);
        EffectContext grant = new EffectContext();
        for (var e : gm.entrySet()) {
            String k = String.valueOf(e.getKey());
            if ("effect".equals(k)) continue;
            Object v = e.getValue();
            switch (k) {
                case "attr" -> putAttr(grant, v);
                case "op" -> grant.put(EffectKeys.OP, String.valueOf(v).toUpperCase());
                case "value" -> grant.put(EffectKeys.VALUE, v instanceof Number n ? n.doubleValue() : 0.0);
                case "amount" -> grant.put(EffectKeys.AMOUNT, v instanceof Number n ? n.doubleValue() : 0.0);
                case "amp" -> grant.put(EffectKeys.AMP, v instanceof Number n ? n.intValue() : 0);
                case "potion" -> putPotion(grant, v);
                case "potions" -> grant.put(EffectKeys.POTIONS,
                        io.mczju.maggoteers.effect.BuffPotionParser.parseList(v, false));
                default -> { }
            }
        }
        auraCtx.put(EffectKeys.GRANT_PARAMS, grant);
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, Double> parseAttributesMap(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) return java.util.Map.of();
        java.util.Map<String, Double> out = new java.util.HashMap<>();
        for (var e : m.entrySet()) {
            Object val = e.getValue();
            double d = val instanceof Number n ? n.doubleValue() : 0.0;
            out.put(String.valueOf(e.getKey()), d);
        }
        return java.util.Map.copyOf(out);
    }

    @SuppressWarnings("unchecked")
    private static EffectContext parseProjectileContext(Object raw) {
        EffectContext proj = new EffectContext();
        if (!(raw instanceof Map<?, ?> m)) return proj;
        for (var e : m.entrySet()) {
            String k = String.valueOf(e.getKey());
            Object v = e.getValue();
            switch (k) {
                case "speed" -> proj.put(EffectKeys.PROJECTILE_SPEED, v instanceof Number n ? n.doubleValue() : 1.0);
                default -> { }
            }
        }
        return proj;
    }

    @SuppressWarnings("unchecked")
    private static EffectContext parseFxContext(Object raw) {
        EffectContext fx = new EffectContext();
        if (!(raw instanceof Map<?, ?> m)) return fx;
        for (var e : m.entrySet()) {
            io.mczju.maggoteers.item.fx.MagicFxBuilder.putFxEntry(
                    fx, String.valueOf(e.getKey()), e.getValue());
        }
        return fx;
    }

    private static <T extends Enum<T>> T optEnum(Map<?, ?> m, String key, Class<T> type) {
        Object v = m.get(key);
        if (v == null) return null;
        return safeEnum(type, v);
    }
    private static <T extends Enum<T>> T safeEnum(Class<T> type, Object v) {
        try { return Enum.valueOf(type, String.valueOf(v).toUpperCase()); }
        catch (Exception e) { return null; }
    }
    private static void putPotion(EffectContext ctx, Object v) {
        String name = String.valueOf(v);
        ctx.put(EffectKeys.POTION_NAME, name);
        PotionEffectType p = io.mczju.maggoteers.util.GameRegistries.potionEffect(name);
        if (p != null) ctx.put(EffectKeys.POTION, p);
    }

    private static void putAttr(EffectContext ctx, Object v) {
        String name = String.valueOf(v);
        ctx.put(EffectKeys.ATTR_NAME, name);
        if (io.mczju.maggoteers.util.GameRegistries.isVirtualAttribute(name)) return;
        Attribute a = safeAttribute(v);
        if (a != null) ctx.put(EffectKeys.ATTR, a);
    }

    private static Attribute safeAttribute(Object v) {
        return io.mczju.maggoteers.util.GameRegistries.attribute(String.valueOf(v));
    }
    private static boolean bool(Map<?, ?> m, String key) {
        Object v = m.get(key); return v != null && Boolean.parseBoolean(String.valueOf(v));
    }
    private static int num(Map<?, ?> m, String key, int def) {
        Object v = m.get(key); return v instanceof Number n ? n.intValue() : def;
    }
    private static String str(Map<?, ?> m, String key) { return str(m, key, ""); }
    private static String str(Map<?, ?> m, String key, String def) {
        Object v = m.get(key); return v == null ? def : String.valueOf(v);
    }

    public String displayPlain() {
        return PlainTextComponentSerializer.plainText().serialize(
                net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(display));
    }
}
