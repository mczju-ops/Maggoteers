package io.mczju.maggoteers.reward;

import io.mczju.maggoteers.MaggoteersPlugin;
import io.mczju.maggoteers.item.ItemService;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

public final class CollectibleRegistry {
    private static final Logger LOG = Logger.getLogger("Maggoteers");
    private static final Map<String, CollectibleMapping> BY_REWARD_ID = new HashMap<>();

    private CollectibleRegistry() {}

    public static void load(MaggoteersPlugin plugin) {
        BY_REWARD_ID.clear();
        File f = new File(plugin.getDataFolder(), "collectibles.yml");
        io.mczju.maggoteers.util.PluginFiles.saveResourceIfMissing(plugin, "collectibles.yml");
        mergeYaml(YamlConfiguration.loadConfiguration(f));
        for (var e : BY_REWARD_ID.entrySet()) {
            validateMapping(e.getKey(), e.getValue());
        }
        LOG.info("CollectibleRegistry: loaded " + BY_REWARD_ID.size() + " mappings");
    }

    static void parseYamlForTest(String yaml) {
        BY_REWARD_ID.clear();
        mergeYaml(YamlConfiguration.loadConfiguration(new java.io.StringReader(yaml)));
    }

    private static void mergeYaml(YamlConfiguration yaml) {
        ConfigurationSection root = yaml.getConfigurationSection("collectibles");
        if (root == null) return;
        for (String rewardId : root.getKeys(false)) {
            ConfigurationSection sec = root.getConfigurationSection(rewardId);
            if (sec == null) continue;
            String single = sec.getString("item");
            Map<Integer, String> byLevel = new HashMap<>();
            ConfigurationSection levels = sec.getConfigurationSection("items_by_level");
            if (levels != null) {
                for (String k : levels.getKeys(false)) {
                    try {
                        byLevel.put(Integer.parseInt(k), levels.getString(k));
                    } catch (NumberFormatException ex) {
                        LOG.warning("collectibles " + rewardId + " invalid level key: " + k);
                    }
                }
            }
            BY_REWARD_ID.put(rewardId, new CollectibleMapping(rewardId, single, Map.copyOf(byLevel)));
        }
    }

    private static void validateMapping(String rewardId, CollectibleMapping m) {
        if (m.isLeveled()) {
            for (var e : m.itemsByLevel().entrySet()) {
                if (!ItemService.hasItem(e.getValue())) {
                    LOG.warning("collectibles " + rewardId + " level " + e.getKey()
                            + " unknown item: " + e.getValue());
                }
            }
            return;
        }
        m.itemIdForLevel(1).ifPresent(id -> {
            if (!ItemService.hasItem(id)) {
                LOG.warning("collectibles " + rewardId + " unknown item: " + id);
            }
        });
    }

    public static boolean hasMapping(String rewardId) {
        return rewardId != null && BY_REWARD_ID.containsKey(rewardId);
    }

    public static Optional<CollectibleMapping> get(String rewardId) {
        if (rewardId == null) return Optional.empty();
        return Optional.ofNullable(BY_REWARD_ID.get(rewardId));
    }

    public static Optional<String> resolveItemId(String rewardId, int level) {
        return get(rewardId).flatMap(m -> m.itemIdForLevel(level));
    }
}
