package io.mczju.maggoteers.world;

import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

import java.util.Random;

/** 虚空区块生成器：不生成任何地形/结构/生物。 */
public final class VoidGenerator extends ChunkGenerator {
    @Override public boolean shouldGenerateNoise()       { return false; }
    @Override public boolean shouldGenerateSurface()     { return false; }
    @Override public boolean shouldGenerateDecorations() { return false; }
    @Override public boolean shouldGenerateMobs()        { return false; }
    @Override public boolean shouldGenerateStructures()  { return false; }
    @Override public boolean shouldGenerateBedrock()     { return false; }
    @Override public boolean shouldGenerateCaves()       { return false; }

    @Override
    public void generateSurface(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, ChunkData chunkData) {
        // 完全空白
    }
}
