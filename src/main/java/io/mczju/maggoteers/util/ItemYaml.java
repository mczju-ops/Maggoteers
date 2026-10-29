package io.mczju.maggoteers.util;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.function.Consumer;

/** items/*.yml 目录扫描 + 物品 id 规范化共享（消除各 registry 的重复实现）。 */
public final class ItemYaml {
    private ItemYaml() {}

    /** 无命名空间前缀的 key 补上 {@code maggoteers:}（含冒号则原样返回）。 */
    public static String normalizeItemId(String key) {
        if (key.contains(":")) {
            return key;
        }
        return "maggoteers:" + key.replaceFirst("^maggoteers:", "");
    }

    /** 扫描目录下全部 {@code *.yml} 逐个加载交给 action；dir 无效或无可读文件时静默。 */
    public static void forEachYaml(File dir, Consumer<YamlConfiguration> action) {
        if (dir == null || !dir.isDirectory()) {
            return;
        }
        File[] files = dir.listFiles((d, n) -> n.endsWith(".yml"));
        if (files == null) {
            return;
        }
        for (File f : files) {
            action.accept(YamlConfiguration.loadConfiguration(f));
        }
    }
}
