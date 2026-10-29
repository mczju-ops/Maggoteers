package io.mczju.maggoteers.util;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

/** Bundled plugin data files: release from jar only when missing (avoids Bukkit WARN spam). */
public final class PluginFiles {

    private PluginFiles() {}

    public static void saveResourceIfMissing(JavaPlugin plugin, String resourcePath) {
        File dest = new File(plugin.getDataFolder(), resourcePath);
        if (!dest.exists()) {
            plugin.saveResource(resourcePath, false);
        }
    }
}
