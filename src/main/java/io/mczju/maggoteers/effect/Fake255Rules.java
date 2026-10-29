package io.mczju.maggoteers.effect;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Detects deprecated amp-255 fake clear/immunity patterns in rewards/items YAML. */
public final class Fake255Rules {

    private Fake255Rules() {}

    /** When immunityTrue, amp/duration are ignored (not fake-clear). */
    public static boolean isFakeClear(int amp, int durationTicks, boolean immunityTrue) {
        if (immunityTrue) {
            return false;
        }
        return amp == 255 && (durationTicks == 0 || durationTicks == 1);
    }

    /** @return location suffix if a fake-255 potion entry is found */
    public static Optional<String> findFakeClearInPotionList(Object yamlList, String listField) {
        if (!(yamlList instanceof List<?> list)) {
            return Optional.empty();
        }
        int i = 0;
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> m)) {
                i++;
                continue;
            }
            int amp = num(m.get("amp"), 0);
            int dur = num(m.get("duration_ticks"), num(m.get("dur"), 0));
            if (isFakeClear(amp, dur, false)) {
                return Optional.of(listField + "[" + i + "] amp=255 duration_ticks=" + dur);
            }
            i++;
        }
        return Optional.empty();
    }

    private static int num(Object v, int fallback) {
        return v instanceof Number n ? n.intValue() : fallback;
    }
}