package io.mczju.maggoteers.effect;

import io.mczju.maggoteers.MaggoteersPlugin;
import io.mczju.maggoteers.util.ItemYaml;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

/** Loads weapon {@code held_effects} blocks from {@code items/*.yml}. */
public final class WeaponHeldRegistry {

    private static final Logger LOG = Logger.getLogger("Maggoteers");
    private static final Map<String, List<WeaponHeldEntry>> BY_ITEM_ID = new HashMap<>();

    private WeaponHeldRegistry() {}

    public static void load(MaggoteersPlugin plugin) {
        BY_ITEM_ID.clear();
        loadItemsDir(new File(plugin.getDataFolder(), "items"));
        if (plugin.getResource("items/maggoteers.yml") != null) {
            try (var in = plugin.getResource("items/maggoteers.yml")) {
                if (in != null) {
                    YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                            new InputStreamReader(in, StandardCharsets.UTF_8));
                    parseYaml(yaml);
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to read bundled items/maggoteers.yml held_effects: " + e.getMessage());
            }
        }
        int total = BY_ITEM_ID.values().stream().mapToInt(List::size).sum();
        LOG.info("WeaponHeldRegistry: loaded " + total + " held_effects entries across "
                + BY_ITEM_ID.size() + " items");
    }

    public static List<WeaponHeldEntry> get(String itemId) {
        if (itemId == null) {
            return List.of();
        }
        return BY_ITEM_ID.getOrDefault(itemId, List.of());
    }

    private static void loadItemsDir(File dir) {
        ItemYaml.forEachYaml(dir, WeaponHeldRegistry::parseYaml);
    }

    private static void parseYaml(YamlConfiguration yaml) {
        for (String key : yaml.getKeys(false)) {
            ConfigurationSection itemSec = yaml.getConfigurationSection(key);
            if (itemSec == null) {
                continue;
            }
            String itemId = ItemYaml.normalizeItemId(key);
            List<?> rawList = itemSec.getList("held_effects");
            if (rawList == null || rawList.isEmpty()) {
                continue;
            }
            List<WeaponHeldEntry> entries = new ArrayList<>();
            for (int i = 0; i < rawList.size(); i++) {
                Object row = rawList.get(i);
                if (!(row instanceof Map<?, ?> map)) {
                    LOG.warning("items: " + itemId + " held_effects[" + i + "] not a map — skipped");
                    continue;
                }
                parseEntry(itemId, i, map, entries);
            }
            if (!entries.isEmpty()) {
                BY_ITEM_ID.put(itemId, List.copyOf(entries));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void parseEntry(String itemId, int index, Map<?, ?> map, List<WeaponHeldEntry> out) {
        Object effectRaw = map.get("effect");
        if (effectRaw == null) {
            LOG.warning("items: " + itemId + " held_effects[" + index + "] missing effect — skipped");
            return;
        }
        Effect effect;
        try {
            effect = Effect.valueOf(String.valueOf(effectRaw).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            LOG.warning("items: " + itemId + " held_effects[" + index + "] unknown effect — skipped");
            return;
        }
        boolean hasExpiry = map.containsKey("expiry");
        Trigger fireTrigger = null;
        Object triggerRaw = map.get("trigger");
        if (triggerRaw != null && !String.valueOf(triggerRaw).isBlank()) {
            try {
                fireTrigger = Trigger.valueOf(String.valueOf(triggerRaw).trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                LOG.warning("items: " + itemId + " held_effects[" + index + "] unknown trigger — skipped");
                return;
            }
        }
        EffectContext params = new EffectContext();
        Object paramsRaw = map.get("params");
        if (paramsRaw instanceof Map<?, ?> pm) {
            YamlConfiguration tmp = new YamlConfiguration();
            for (var e : pm.entrySet()) {
                tmp.set(String.valueOf(e.getKey()), e.getValue());
            }
            params = EffectParamsParser.parse(tmp);
        } else if (paramsRaw instanceof ConfigurationSection sec) {
            params = EffectParamsParser.parse(sec);
        }
        params = MagicEffectParams.withDefaults(effect, params);
        if (paramsRaw instanceof Map<?, ?> pm && pm.get("counter") != null) {
            java.util.Optional<CounterSpec> parsed = CounterSpecParser.parse(pm.get("counter"));
            if (parsed.isPresent()) {
                CounterSpec spec = parsed.get();
                params.put(EffectKeys.COUNTER_SPEC, spec);
                params.put(EffectKeys.COUNTER_ID, spec.id());
            }
        }

        Stack stack = Stack.IGNORE;
        Object stackRaw = map.get("stack");
        if (stackRaw != null && !String.valueOf(stackRaw).isBlank()) {
            try {
                stack = Stack.valueOf(String.valueOf(stackRaw).trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                LOG.warning("items: " + itemId + " held_effects[" + index + "] unknown stack — skipped");
                return;
            }
        } else if (fireTrigger == null && effect == Effect.ADD_ATTRIBUTE) {
            stack = Stack.ADD;
        }

        int upgradeMax = 0;
        Object upgradeRaw = map.get("upgrade_max");
        if (upgradeRaw instanceof Number n) {
            upgradeMax = n.intValue();
        }

        Optional<String> err = WeaponEffectValidator.validateHeld(
                effect, fireTrigger, params, stack, upgradeMax, hasExpiry);
        if (err.isPresent()) {
            LOG.warning("items: " + itemId + " held_effects[" + index + "] " + err.get() + " — skipped");
            return;
        }
        out.add(new WeaponHeldEntry(effect, params, fireTrigger, stack));
    }
}
