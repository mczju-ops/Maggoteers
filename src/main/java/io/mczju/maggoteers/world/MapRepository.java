package io.mczju.maggoteers.world;

import io.mczju.maggoteers.MaggoteersPlugin;
import io.mczju.maggoteers.config.ConfigParse;
import io.mczju.maggoteers.config.MapPoints;
import io.mczju.maggoteers.config.WavesConfig;
import io.mczju.maggoteers.util.PluginFiles;
import io.mczju.maggoteers.wave.Vec3;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.*;

/** 读 plugins/Maggoteers/maps/actN/<mapId>/ 的 points.yml + structure.nbt 是否存在。 */
public final class MapRepository {

    public record MapEntry(String mapId, MapPoints points, boolean hasStructure, File dir,
                           Map<String, List<WavesConfig.PoolEntry>> specialWaves) {}

    private static final Map<String, List<MapEntry>> BY_ACT = new HashMap<>();

    /** jar 内 bundled 地图：首次启动释放 points.yml / special_waves.yml / structure.nbt（若存在）。 */
    private static final Map<String, List<String>> BUNDLED_MAP_IDS = Map.of(
            "act1", List.of("jungle", "desert"),
            "act2", List.of("graveyard", "badlands"),
            "act3", List.of("sky5", "giantcity6"),
            // Act4 隐藏结局层：实图 guardcity6（2026-10-28；此前的 hidden_altar 玻璃平台占位已移除）
            "act4", List.of("guardcity6"));

    public static void load(JavaPlugin plugin) {
        BY_ACT.clear();
        File root = new File(plugin.getDataFolder(), "maps");
        // 层列表唯一真相源：config.yml acts:（不再有 Java 里的 act1/act2/act3 白名单）
        List<String> acts = plugin instanceof MaggoteersPlugin mg
                ? mg.actIds()
                : io.mczju.maggoteers.config.ActsConfig.fromConfig(plugin.getConfig());
        for (String act : acts) {
            for (String mapId : BUNDLED_MAP_IDS.getOrDefault(act, List.of())) {
                ensureResource(plugin, "maps/" + act + "/" + mapId + "/points.yml");
                ensureResource(plugin, "maps/" + act + "/" + mapId + "/special_waves.yml");
                ensureResource(plugin, "maps/" + act + "/" + mapId + "/structure.nbt");
            }
            File actDir = new File(root, act);
            if (!actDir.isDirectory()) continue;
            List<MapEntry> entries = new ArrayList<>();
            File[] mapDirs = actDir.listFiles(File::isDirectory);
            if (mapDirs == null) continue;
            for (File mapDir : mapDirs) {
                File pf = new File(mapDir, "points.yml");
                if (!pf.isFile()) continue;
                var cfg = YamlConfiguration.loadConfiguration(pf);
                MapPoints points = parsePoints(cfg);
                boolean hasStructure = new File(mapDir, "structure.nbt").isFile();
                entries.add(new MapEntry(mapDir.getName(), points, hasStructure, mapDir,
                        parseSpecialWaves(mapDir)));
            }
            BY_ACT.put(act, entries);
        }
        warnUnconfiguredActDirs(plugin, root, acts);
        plugin.getLogger().info("MapRepository 已加载：" + BY_ACT);
    }

    /** maps/ 下存在但未写进 config.yml acts: 的层目录 —— 明确告警（否则"放了地图却没生效"极难排查）。 */
    private static void warnUnconfiguredActDirs(JavaPlugin plugin, File root, List<String> acts) {
        File[] dirs = root.listFiles(File::isDirectory);
        if (dirs == null) return;
        for (File d : dirs) {
            if (!d.getName().startsWith("act")) continue;
            if (!acts.contains(d.getName())) {
                plugin.getLogger().warning("maps/" + d.getName()
                        + " 存在但未在 config.yml acts: 中启用，已忽略（要启用请把 " + d.getName() + " 加进 acts）");
            }
        }
    }

    private static Map<String, List<WavesConfig.PoolEntry>> parseSpecialWaves(File mapDir) {
        File f = new File(mapDir, "special_waves.yml");
        if (!f.isFile()) return Map.of();
        var cfg = YamlConfiguration.loadConfiguration(f);
        Map<String, List<WavesConfig.PoolEntry>> out = new HashMap<>();
        for (String tier : List.of("weak", "strong", "boss")) {
            var list = cfg.getMapList(tier);
            if (list.isEmpty()) continue;
            List<WavesConfig.PoolEntry> entries = new ArrayList<>();
            for (var m : list) {
                entries.add(new WavesConfig.PoolEntry((String) m.get("strategy"),
                        ConfigParse.num(m.get("weight"), 1).doubleValue()));
            }
            out.put(tier, entries);
        }
        return out;
    }

    private static MapPoints parsePoints(YamlConfiguration cfg) {
        var ps = cfg.getConfigurationSection("playerSpawn");
        Vec3 spawn = ps == null ? new Vec3(0.5, 65, 0.5)
                : new Vec3(ps.getDouble("x"), ps.getDouble("y"), ps.getDouble("z"));
        Map<String, Vec3> pts = new HashMap<>();
        var sp = cfg.getConfigurationSection("spawnPoints");
        if (sp != null) {
            for (String id : sp.getKeys(false)) {
                var s = sp.getConfigurationSection(id);
                if (s != null) pts.put(id, new Vec3(s.getDouble("x"), s.getDouble("y"), s.getDouble("z")));
            }
        }
        return new MapPoints(spawn, pts);
    }

    public static List<MapEntry> getMaps(String act) { return BY_ACT.getOrDefault(act, List.of()); }

    public static MapLibrary library() {
        return new MapLibrary(BY_ACT);
    }

    private static void ensureResource(JavaPlugin plugin, String path) {
        if (plugin.getResource(path) == null) return;
        PluginFiles.saveResourceIfMissing(plugin, path);
    }

    private MapRepository() {}
}
