package io.mczju.maggoteers.world;

import org.bukkit.Bukkit;
import org.bukkit.structure.Structure;

import java.io.File;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * structure.nbt 解析缓存（2026-10-26，解决"生成剧本/开局卡顿"）。
 *
 * <p>问题：{@code StructurePaster} 在**主线程**里 `loadStructure(file)` + `place(...)`。
 * 单个 120KB+ 的结构解析（NBT 反序列化 + 调色板展开）通常几十毫秒到上百毫秒，
 * 世界创建 + Act1 粘贴又都发生在开局那一下 → 明显卡顿。
 *
 * <p>做法：开局前（异步）把候选地图的 structure **解析**成 {@link Structure} 并缓存，
 * 主线程只剩 `place(...)`；同一地图再次进层（Act2/3 若共用文件）直接命中缓存。
 * 解析失败/超时则回落到主线程解析（行为与旧版一致，不会因为缓存而打不开图）。
 *
 * <p>线程安全说明：这里只做"文件 → 内存结构"的纯解析，不接触 World/Entity；
 * `place` 仍然只在主线程调用。解析抛异常时会被吞掉并记日志，随后由主线程重试。
 */
public final class StructureCache {

    private static final Logger LOG = Logger.getLogger("Maggoteers");
    private static final Map<String, Structure> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, CompletableFuture<Structure>> PENDING = new ConcurrentHashMap<>();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Maggoteers-StructurePreload");
        t.setDaemon(true);
        return t;
    });

    private StructureCache() {}

    private static String key(File nbt) {
        return nbt.getAbsolutePath() + "#" + nbt.lastModified();
    }

    /** 异步预解析（重复调用幂等；同一文件只解析一次）。 */
    public static void preload(File nbt) {
        if (nbt == null || !nbt.isFile()) return;
        String k = key(nbt);
        if (CACHE.containsKey(k) || PENDING.containsKey(k)) return;
        PENDING.computeIfAbsent(k, kk -> CompletableFuture
                .supplyAsync(() -> load(nbt), EXECUTOR)
                .whenComplete((s, err) -> {
                    PENDING.remove(kk);
                    if (s != null) {
                        CACHE.put(kk, s);
                    } else if (err != null) {
                        LOG.warning("异步预解析 " + nbt.getName() + " 失败（将回落主线程解析）：" + err.getMessage());
                    }
                }));
    }

    /** 该文件的解析是否已完成（供开局门闩判断"可以进图了"）。 */
    public static boolean isReady(File nbt) {
        return nbt == null || CACHE.containsKey(key(nbt));
    }

    /** 等待预解析完成（主线程不可调用）；超时返回 false，由调用方决定是否继续等。 */
    public static boolean awaitReady(File nbt, long millis) {
        if (nbt == null) return true;
        String k = key(nbt);
        if (CACHE.containsKey(k)) return true;
        CompletableFuture<Structure> f = PENDING.get(k);
        if (f == null) return false;
        try {
            f.get(millis, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            return false;
        }
        return CACHE.containsKey(k);
    }

    /** 取结构：命中缓存直接返回；否则主线程同步解析并缓存（回落路径）。 */
    public static Structure get(File nbt) {
        if (nbt == null || !nbt.isFile()) return null;
        String k = key(nbt);
        Structure cached = CACHE.get(k);
        if (cached != null) return cached;
        try {
            Structure s = load(nbt);
            if (s != null) CACHE.put(k, s);
            return s;
        } catch (RuntimeException ex) {
            LOG.warning("解析 " + nbt.getName() + " 失败：" + ex.getMessage());
            return null;
        }
    }

    private static Structure load(File nbt) {
        try {
            return Bukkit.getStructureManager().loadStructure(nbt);
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    /** 关服/重载时清空（避免旧文件缓存占内存）。 */
    public static void clear() {
        CACHE.clear();
        PENDING.clear();
    }
}
