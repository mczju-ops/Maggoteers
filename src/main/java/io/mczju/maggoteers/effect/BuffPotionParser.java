package io.mczju.maggoteers.effect;

import io.mczju.maggoteers.util.GameRegistries;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Parses BUFF_AREA potions[] from YAML. */
public final class BuffPotionParser {

    private BuffPotionParser() {}

    /** BUFF_AREA 等：必须 {@code duration_ticks > 0}。 */
    public static List<BuffPotionSpec> parseList(Object yamlList) {
        return parseList(yamlList, true);
    }

    /**
     * @param requirePositiveDuration false 时允许省略 duration（AURA grant 由 refresh 续时）
     */
    @SuppressWarnings("unchecked")
    public static List<BuffPotionSpec> parseList(Object yamlList, boolean requirePositiveDuration) {
        List<BuffPotionSpec> out = new ArrayList<>();
        if (!(yamlList instanceof List<?> list)) {
            return out;
        }
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> m)) continue;
            String name = str(m.get("potion"));
            if (name == null) name = str(m.get("effect"));
            if (name == null || name.isBlank()) continue;
            PotionEffectType type = GameRegistries.potionEffect(name);
            if (type == null) continue;
            int amp = num(m.get("amp"), 0);
            if (amp < 0) amp = 0;
            int dur = num(m.get("duration_ticks"), num(m.get("dur"), 0));
            if (requirePositiveDuration) {
                if (dur <= 0) continue;
            } else if (dur < 0) {
                dur = 0;
            }
            out.add(new BuffPotionSpec(type, amp, dur));
        }
        return List.copyOf(out);
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static int num(Object v, int fallback) {
        return v instanceof Number n ? n.intValue() : fallback;
    }
}
