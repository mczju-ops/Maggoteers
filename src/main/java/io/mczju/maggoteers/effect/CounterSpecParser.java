package io.mczju.maggoteers.effect;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** 解析 {@code params.counter: {...}} 嵌套块（rewards.yml / items/*.yml）。 */
public final class CounterSpecParser {

    private CounterSpecParser() {}

    public static Optional<CounterSpec> parse(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) {
            return Optional.empty();
        }
        String id = m.get("id") == null ? "" : String.valueOf(m.get("id")).trim();
        if (id.isBlank()) {
            return Optional.empty();
        }
        CounterCountTrigger trigger = enumOf(m.get("count"), CounterCountTrigger.class);
        if (trigger == null) {
            return Optional.empty();
        }
        double amount = m.get("amount") instanceof Number n ? n.doubleValue() : 1.0;
        Trigger reset = enumOf(m.get("reset"), Trigger.class);
        CounterCondition condition = parseCondition(m.get("condition"));
        return Optional.of(new CounterSpec(id, trigger, amount, reset, condition));
    }

    private static CounterCondition parseCondition(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) {
            return null;
        }
        if (m.containsKey("and")) {
            return new CounterCondition.And(parseChildren(m.get("and")));
        }
        if (m.containsKey("or")) {
            return new CounterCondition.Or(parseChildren(m.get("or")));
        }
        if (m.containsKey("not")) {
            CounterCondition child = parseCondition(m.get("not"));
            return child == null ? null : new CounterCondition.Not(child);
        }
        if (m.containsKey("hold_item_pdc")) {
            return new CounterCondition.HoldItemPdc(String.valueOf(m.get("hold_item_pdc")));
        }
        if (m.containsKey("player_in_radius")) {
            double r = m.get("player_in_radius") instanceof Number n ? n.doubleValue() : 0.0;
            return new CounterCondition.PlayerInRadius(r);
        }
        return null;
    }

    private static List<CounterCondition> parseChildren(Object raw) {
        List<CounterCondition> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            return out;
        }
        for (Object o : list) {
            CounterCondition c = parseCondition(o);
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
