package io.mczju.maggoteers.effect;

import io.mczju.maggoteers.util.GameRegistries;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Parses {@code clear_potions} / {@code self_clear_potions} string lists. */
public final class ClearPotionsParser {

    public record ParseResult(List<PotionEffectType> types, List<String> errors) {
        public ParseResult {
            types = List.copyOf(types);
            errors = List.copyOf(errors);
        }

        public boolean ok() {
            return errors.isEmpty();
        }
    }

    private ClearPotionsParser() {}

    /**
     * @param locationPrefix e.g. {@code items maggoteers:emp params.clear_potions}
     */
    public static ParseResult parse(Object yamlList, String locationPrefix) {
        List<PotionEffectType> types = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        if (yamlList == null) {
            return new ParseResult(types, errors);
        }
        if (!(yamlList instanceof List<?> list)) {
            errors.add(locationPrefix + ": clear_potions must be a list");
            return new ParseResult(types, errors);
        }
        int i = 0;
        for (Object entry : list) {
            String loc = locationPrefix + "[" + i + "]";
            i++;
            String name = resolveName(entry);
            if (name == null || name.isBlank()) {
                errors.add(loc + ": empty potion name");
                continue;
            }
            PotionEffectType type = GameRegistries.potionEffect(name);
            if (type == null) {
                errors.add(loc + ": unknown potion '" + name + "'");
                continue;
            }
            types.add(type);
        }
        return new ParseResult(types, errors);
    }

    private static String resolveName(Object entry) {
        if (entry == null) {
            return null;
        }
        if (entry instanceof Map<?, ?> m) {
            Object raw = m.get("potion");
            if (raw == null) {
                raw = m.get("effect");
            }
            return raw == null ? null : String.valueOf(raw);
        }
        return String.valueOf(entry);
    }
}
