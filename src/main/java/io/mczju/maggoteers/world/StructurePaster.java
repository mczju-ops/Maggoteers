package io.mczju.maggoteers.world;

import io.mczju.maggoteers.world.MapRepository.MapEntry;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.structure.Structure;
import org.bukkit.util.BlockVector;

import java.io.File;
import java.util.Random;
import java.util.logging.Logger;

/**
 * 把某层单个 structure.nbt 粘贴进虚空世界（StructureManager.loadStructure + place）。
 * <p>粘贴点 = 层原点；对齐结构导出原点角，结构往 +X/+Y/+Z 展开。
 * <p>缺 structure.nbt 且 fallback=true → 铺 64×64 玻璃平台 + 中央下方实心柱。
 */
public final class StructurePaster {
    private static final Logger LOG = Logger.getLogger("Maggoteers");
    private static final int PLATFORM_HALF = 32;
    private static final String STRUCTURE_FILE = "structure.nbt";

    /**
     * @param world    目标世界
     * @param originX  层原点 X
     * @param baseY    层原点 Y（粘贴基准高度）
     * @param originZ  层原点 Z
     * @param nbtDir   maps/actN/<mapId>/ 目录（含 structure.nbt）
     * @param map      地图条目（hasStructure 表示目录内有 structure.nbt）
     * @param fallback true=未粘贴成功时铺玻璃平台
     */
    public static void pasteAct(World world, int originX, int baseY, int originZ,
                                File nbtDir, MapEntry map, boolean fallback) {
        boolean pasted = false;
        if (map.hasStructure()) {
            File nbt = new File(nbtDir, STRUCTURE_FILE);
            if (nbt.isFile()) {
                try {
                    long t0 = System.nanoTime();
                    Structure s = StructureCache.get(nbt);   // 命中异步预解析缓存；未命中则此处解析
                    if (s != null) {
                        BlockVector origin = new BlockVector(originX, baseY, originZ);
                        s.place(world, origin, true, StructureRotation.NONE, Mirror.NONE,
                                0, 1.0f, new Random());
                        pasted = true;
                        long ms = (System.nanoTime() - t0) / 1_000_000L;
                        LOG.info("已粘贴 " + STRUCTURE_FILE + " @ " + origin + "（" + ms + "ms"
                                + (ms > 25 ? "，偏慢：结构较大" : "") + "）");
                    }
                } catch (Exception e) {
                    LOG.warning("粘贴 " + STRUCTURE_FILE + " 失败：" + e.getMessage());
                }
            }
        }
        if (!pasted && fallback) {
            buildPlatform(world, originX, baseY, originZ);
            LOG.warning("未粘贴 " + STRUCTURE_FILE + "，已铺设兜底玻璃平台 @ ("
                    + originX + "," + baseY + "," + originZ + ")");
        }
    }

    /** 64×64 玻璃平台（顶面 = baseY）+ 中央下方实心柱。 */
    private static void buildPlatform(World w, int ox, int baseY, int oz) {
        for (int dx = -PLATFORM_HALF; dx < PLATFORM_HALF; dx++) {
            for (int dz = -PLATFORM_HALF; dz < PLATFORM_HALF; dz++) {
                w.getBlockAt(ox + dx, baseY, oz + dz).setType(Material.GLASS);
            }
        }
        for (int dy = 1; dy <= 40; dy++) {
            w.getBlockAt(ox, baseY - dy, oz).setType(Material.GLASS);
        }
    }

    private StructurePaster() {}
}
