package io.mczju.maggoteers.world;

import com.github.mczjuops.mczjugamecore.game.AbstractGame;
import io.mczju.maggoteers.MaggoteersPlugin;
import io.mczju.maggoteers.effect.ParticleLoopService;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameRules;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

/** 每局专属虚空世界的完整生命周期。借鉴前代 VampireSurvivor/WorldManager。 */
public final class WorldService {

    private static final Map<AbstractGame, World> worlds = new IdentityHashMap<>();
    private static final Map<AbstractGame, Long> SEEDS = new IdentityHashMap<>();
    private static final Logger LOG = Logger.getLogger("Maggoteers");

    /** 主线程调用：为本对局创建虚空世界。返回 null 表示失败。 */
    public static World create(AbstractGame game) {
        long seed = ThreadLocalRandom.current().nextLong();
        String name = WorldNames.forSeed(seed);
        World w = new WorldCreator(name)
                .environment(World.Environment.NORMAL)
                .generator(new VoidGenerator())
                .createWorld();
        if (w == null) {
            LOG.severe("无法创建世界 " + name);
            return null;
        }
        w.setSpawnLocation(0, 64, 0);
        w.setDifficulty(Difficulty.HARD);
        w.setGameRule(GameRules.ADVANCE_TIME, false);
        w.setGameRule(GameRules.ADVANCE_WEATHER, false);
        w.setGameRule(GameRules.NATURAL_HEALTH_REGENERATION, false);
        w.setGameRule(GameRules.MOB_GRIEFING, false);
        w.setGameRule(GameRules.SPAWN_MOBS, false);
        // 局内关闭玩家间 PVP：友伤的最后一处漏网是"玩家近战/投射物直接打队友"（武器 AOE 已按 targets 排除玩家、
        // 召唤物已由 SummonFriendlyFireListener 拦截）；世界级规则天然仅对本局生效，不影响大厅/其他世界。
        w.setGameRule(GameRules.PVP, false);
        // 局内禁用火焰蔓延：1.21.11 起 doFireTick 合并为 fire_spread_radius_around_player（默认 128），
        // 设 0 = 完全禁蔓延（火矢/岩浆怪点燃的火焰不再烧掉结构地图；火焰本身仍伤害实体）。
        w.setGameRule(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, 0);
        // 2026-10-29：寒冷要"满冻结"才显示白霜/发抖（FreezeService 每 tick 顶到 140），
        // 而满冻结会附带 vanilla 冻结掉血 —— 关掉局内冻结伤害，让「寒冷」保持纯控制/演出。
        w.setGameRule(GameRules.FREEZE_DAMAGE, false);
        applyTimeLock(w);
        w.setStorm(false);
        w.setThundering(false);
        w.setClearWeatherDuration(Integer.MAX_VALUE);
        SEEDS.put(game, seed);
        worlds.put(game, w);
        LOG.info("已为对局创建世界 " + name);
        return w;
    }

    public static World get(AbstractGame game) {
        World w = worlds.get(game);
        if (w != null) applyTimeLock(w);
        return w;
    }

    /** 按 config {@code world.time_lock} 锁定昼夜（默认 night，避免怪物日晒）。 */
    public static void applyTimeLock(World w) {
        if (w == null) return;
        String mode = MaggoteersPlugin.getInstance().getConfig().getString("world.time_lock", "night");
        if ("none".equalsIgnoreCase(mode)) return;
        long tick = switch (mode.toLowerCase()) {
            case "day", "noon" -> 6000L;
            case "midnight" -> 18000L;
            default -> 13000L; // night（略深，仍可见）
        };
        w.setTime(tick);
    }

    public static long getSeed(AbstractGame game) {
        Long s = SEEDS.get(game);
        return s == null ? 0L : s;
    }

    /**
     * 卸载并异步删目录。约定：仅在世界内已无玩家时调用（由 MGC leave / 大厅流程保证）。
     */
    public static void cleanup(AbstractGame game) {
        io.mczju.maggoteers.world.ActSpawnHelper.clearGame(game);
        World w = worlds.get(game);
        if (w == null) {
            SEEDS.remove(game);
            worlds.remove(game);
            return;
        }
        if (!w.getPlayers().isEmpty()) {
            LOG.warning("cleanup 时世界仍有玩家，跳过卸载与删目录: " + w.getName()
                    + " players=" + w.getPlayers().size());
            return;
        }
        File folder = w.getWorldFolder();
        String name = w.getName();
        // 兜底：对局结束卸载世界前取消全部粒子周期任务（实体已随世界消失）
        ParticleLoopService.clearAll();
        if (!Bukkit.unloadWorld(w, false)) {
            LOG.warning("unloadWorld 失败，跳过删目录: " + name);
            return;
        }
        worlds.remove(game);
        SEEDS.remove(game);
        new BukkitRunnable() {
            @Override public void run() { deleteDir(folder); }
        }.runTaskAsynchronously(MaggoteersPlugin.getInstance());
        LOG.info("已卸载并删除世界 " + name);
    }

    /** onEnable 调用：清理上次崩服残留的 maggoteers_* 世界。 */
    public static void cleanupOrphansOnEnable() {
        // 1) 已被 Bukkit 自动加载的
        for (World w : new ArrayList<>(Bukkit.getWorlds())) {
            if (w.getName().startsWith("maggoteers_")) {
                Bukkit.unloadWorld(w, false);
                deleteDir(w.getWorldFolder());
                LOG.warning("清理孤立世界(已加载) " + w.getName());
            }
        }
        // 2) 磁盘残留：含 Paper 26.2 嵌套路径 world/dimensions/minecraft/maggoteers_*
        File root = Bukkit.getWorldContainer();
        for (File f : OrphanWorldDirs.findUnder(root)) {
            deleteDir(f);
            LOG.warning("清理孤立世界(目录) " + f.getAbsolutePath());
        }
    }

    private static void deleteDir(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File c : kids) {
                if (c.isDirectory()) deleteDir(c);
                else c.delete();
            }
        }
        f.delete();
    }

    private WorldService() {}
}
