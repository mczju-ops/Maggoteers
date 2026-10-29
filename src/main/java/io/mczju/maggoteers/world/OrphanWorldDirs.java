package io.mczju.maggoteers.world;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Locates leftover maggoteers_* world directories under a server world container. */
public final class OrphanWorldDirs {
    private OrphanWorldDirs() {}

    static final int MAX_DEPTH = 6;

    public static List<File> findUnder(File worldContainer) {
        if (worldContainer == null || !worldContainer.isDirectory()) {
            return List.of();
        }
        Set<File> out = new LinkedHashSet<>();
        walk(worldContainer, 0, out);
        return new ArrayList<>(out);
    }

    private static void walk(File dir, int depth, Set<File> out) {
        if (depth > MAX_DEPTH) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            if (!f.isDirectory()) continue;
            String name = f.getName();
            if (name.startsWith("maggoteers_")) {
                out.add(f);
                continue;
            }
            walk(f, depth + 1, out);
        }
    }
}