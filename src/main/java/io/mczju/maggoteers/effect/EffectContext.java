package io.mczju.maggoteers.effect;

import java.util.HashMap;
import java.util.Map;

/** Effect 的类型化参数容器。put/get 链式。 */
public final class EffectContext {
    private final Map<EffectKey<?>, Object> data = new HashMap<>();

    public <T> EffectContext put(EffectKey<T> key, T val) { data.put(key, val); return this; }

    @SuppressWarnings("unchecked")
    public <T> T get(EffectKey<T> key) { return (T) data.get(key); }

    @SuppressWarnings("unchecked")
    public <T> T getOrDefault(EffectKey<T> key, T def) {
        Object v = data.get(key);
        return v == null ? def : (T) v;
    }

    public boolean has(EffectKey<?> key) { return data.containsKey(key); }

    /** 深拷贝 AURA 嵌套段（grant、carrier_fx、mark_fx、fx），供写入 PlayerState。 */
    public EffectContext copyDeep() {
        EffectContext c = copy();
        copyNested(c, EffectKeys.GRANT_PARAMS);
        copyNested(c, EffectKeys.CARRIER_FX);
        copyNested(c, EffectKeys.MARK_FX);
        copyNested(c, EffectKeys.FX);
        java.util.List<BuffPotionSpec> pots = c.get(EffectKeys.POTIONS);
        if (pots != null) {
            c.put(EffectKeys.POTIONS, java.util.List.copyOf(pots));
        }
        java.util.Map<Integer, String> itemsByLevel = c.get(EffectKeys.ITEMS_BY_LEVEL);
        if (itemsByLevel != null) {
            c.put(EffectKeys.ITEMS_BY_LEVEL, java.util.Map.copyOf(itemsByLevel));
        }
        return c;
    }

    private static void copyNested(EffectContext parent, EffectKey<EffectContext> key) {
        EffectContext nested = parent.get(key);
        if (nested != null) parent.put(key, nested.copy());
    }

    /** 返回浅拷贝（内部 map 独立），避免修改污染共享实例。 */
    public EffectContext copy() {
        EffectContext c = new EffectContext();
        c.data.putAll(this.data);
        return c;
    }
}
