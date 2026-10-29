package io.mczju.maggoteers.plan;

import io.mczju.maggoteers.MaggoteersPlugin;
import io.mczju.maggoteers.config.*;
import io.mczju.maggoteers.util.Coords;
import io.mczju.maggoteers.util.Origins;
import io.mczju.maggoteers.wave.*;
import io.mczju.maggoteers.world.MapLibrary;
import io.mczju.maggoteers.world.MapRepository;
import io.mczju.maggoteers.world.MapRepository.MapEntry;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

/**
 * 异步种子化预生成（§6）。同 seed + 同 playerCount → 同 {@code List<ActPlan>}（可重放）。
 *
 * <p>层列表来自 {@code config.yml acts:}（{@link io.mczju.maggoteers.config.ActsConfig}），
 * 不再有 Java 硬编码数组；顺序即推进顺序。
 */
public final class RunPlanner {

    /** 生产入口：onGameStart 异步线程调用。 */
    public static List<ActPlan> plan(long seed, int playerCount, io.mczju.maggoteers.util.GameDifficulty difficulty) {
        JavaPlugin plugin = MaggoteersPlugin.getInstance();
        return plan(seed, playerCount, difficulty,
                WavesConfig.getInstance().definitions(),
                MapRepository.library(),
                ScalingConfig.getInstance(),
                loadRunConfig(plugin));
    }

    /** 纯函数：完全注入，不碰 Bukkit。NORMAL 兼容入口（既有测试用）。 */
    public static List<ActPlan> plan(long seed, int playerCount, WaveDefinitions defs,
                                     MapLibrary maps, ScalingConfig scaling, RunConfig cfg) {
        return plan(seed, playerCount, io.mczju.maggoteers.util.GameDifficulty.NORMAL, defs, maps, scaling, cfg);
    }

    /** 纯函数：完全注入，不碰 Bukkit。难度倍率由 {@link ScalingConfig#scaleFor(int, GameDifficulty)} 乘入。 */
    public static List<ActPlan> plan(long seed, int playerCount, io.mczju.maggoteers.util.GameDifficulty difficulty,
                                     WaveDefinitions defs, MapLibrary maps, ScalingConfig scaling, RunConfig cfg) {
        SeededRng root = new SeededRng(seed);
        ScalingConfig.Scaling snap = scaling.scaleFor(playerCount, difficulty);
        List<String> acts = cfg.acts();
        List<ActPlan> out = new ArrayList<>(acts.size());
        for (int i = 0; i < acts.size(); i++) {
            out.add(planAct(root, i, acts.get(i), defs, maps, snap, cfg));
        }
        return out;
    }

    private static ActPlan planAct(SeededRng root, int actIndex, String act, WaveDefinitions defs, MapLibrary maps,
                                   ScalingConfig.Scaling snap, RunConfig cfg) {
        List<MapEntry> avail = maps.maps(act);
        if (avail.isEmpty()) throw new IllegalStateException(act + " 无可用地图");
        SeededRng actRng = root.derive(1_000L + actIndex);
        MapEntry map = avail.get(actRng.nextInt(avail.size()));
        Vec3 origin = cfg.origin(act);

        List<WaveSpec> waves = new ArrayList<>();
        Set<String> usedWeak = new HashSet<>();
        for (int i = 0; i < cfg.weak(act); i++)
            waves.add(rollWave(root.derive(wavePickSalt(actIndex, "weak", i)), act, "weak",
                    map, origin, defs, snap, usedWeak, cfg.weak(act)));
        Set<String> usedStrong = new HashSet<>();
        for (int i = 0; i < cfg.strong(act); i++)
            waves.add(rollWave(root.derive(wavePickSalt(actIndex, "strong", i)), act, "strong",
                    map, origin, defs, snap, usedStrong, cfg.strong(act)));
        Set<String> usedBoss = new HashSet<>();
        waves.add(rollWave(root.derive(wavePickSalt(actIndex, "boss", 0)), act, "boss",
                map, origin, defs, snap, usedBoss, 1));

        return new ActPlan(act, map.mapId(), Coords.resolve(origin, map.points().playerSpawn()), waves);
    }

    private static WaveSpec rollWave(SeededRng rng, String act, String tier, MapEntry map, Vec3 origin,
                                     WaveDefinitions defs, ScalingConfig.Scaling snap,
                                     Set<String> used, int need) {
        List<WavesConfig.PoolEntry> base = new ArrayList<>(defs.pool(act, tier));
        base.addAll(map.specialWaves().getOrDefault(tier, List.of()));
        if (base.isEmpty()) throw new IllegalStateException(act + "/" + tier + " 池为空");

        List<WavesConfig.PoolEntry> candidates = new ArrayList<>();
        for (WavesConfig.PoolEntry e : base) {
            if (!used.contains(e.strategy())) candidates.add(e);
        }
        if (candidates.isEmpty()) {
            throw new IllegalStateException(
                    act + "/" + tier + " 池在去重后为空（已用=" + used + "，需求=" + need + "）");
        }

        double[] weights = candidates.stream().mapToDouble(WavesConfig.PoolEntry::weight).toArray();
        int picked = rng.weightedIndex(weights);
        String pickedId = candidates.get(picked).strategy();
        used.add(pickedId);
        SpawnStrategyCfg strat = defs.strategy(pickedId);
        if (strat == null) throw new IllegalStateException("strategy 未定义: " + pickedId);
        return expand(strat, map, origin, snap, rng.derive(50_000L));
    }

    /**
     * 把一条 strategy 展开成一波（点位解析 / 缩放 / G9 boss 数量豁免 / 乘客与亡语树）。
     *
     * <p>抽波（{@link #rollWave}）与**调试换波**（{@link #buildWave}）共用这一段——
     * 保证"手动点名的波"和"随机抽到的波"在数值与点位上完全一致，避免两条实现漂移。
     *
     * @param countRng 数量缩放（{@code mob_count} 的"概率补 1"）用的 RNG；调试路径用 strategy id 派生，结果稳定
     */
    private static WaveSpec expand(SpawnStrategyCfg strat, MapEntry map, Vec3 origin,
                                   ScalingConfig.Scaling snap, SeededRng countRng) {
        List<SpawnStep> steps = new ArrayList<>();
        for (StepCfg sc : strat.steps()) {
            Vec3 rel = map.points().resolvePointOrBossFallback(sc.point());
            if (rel == null) throw new IllegalStateException(
                    "刷怪点 " + sc.point() + " 在 " + map.mapId() + "/points.yml 未定义"
                            + (map.points().point("boss") == null ? "且无 boss 可回退" : "且非 boss 点数≥9 不可回退")
                            + "（strategy=" + strat.id() + "，G3）");
            Compose.MobScale ms = Compose.compose(sc.coeff(), snap);
            // G9：带 bossbar 的怪数量固定 = 配置原值，不受 mob_count 缩放影响
            int resolvedCount = sc.bossBar() ? sc.count()
                    : ScalingConfig.rollCount(sc.count(), snap.mobCount(), countRng);
            List<PassengerSpawn> passengerSpawns =
                    buildPassengerTrees(sc.passengers(), snap, countRng, sc.bossBar());
            List<DeathSpawn> onDeath = buildDeathSpawns(sc.onDeath(), snap, countRng, sc.bossBar());
            steps.add(new SpawnStep(
                    Coords.resolve(origin, rel), sc.type(), resolvedCount,
                    ms.hp(), ms.dmg(), ms.speed(),
                    ms.scale(), ms.followRange(),
                    sc.delaySec() * 20,
                    sc.skills(),
                    sc.equipment(),
                    onDeath,
                    passengerSpawns,
                    sc.repeat(),
                    sc.name(),
                    sc.bossBar(),
                    sc.barColor(),
                    sc.barOverlay()));
        }
        List<RewardItem> rewards = strat.clearReward().stream()
                .map(r -> new RewardItem(r.item(), r.amount())).toList();
        return new WaveSpec(strat.id(), strat.displayName(), steps, strat.repeat(), rewards, strat.phases());
    }

    /**
     * 生产入口：把指定 strategy 现展开成 {@link WaveSpec}（调试换波用）。
     *
     * <p>与 {@link #plan} 走**同一套**点位叠原点、{@code coeff × scaling}、G9 boss 数量豁免、
     * 乘客/亡语树构建；区别只有两点：地图按传入的 {@code mapId} 取（不重抽），
     * 数量缩放的 RNG 由 {@code strategyId} 派生（同一 strategy 每次换波结果一致）。
     *
     * @throws IllegalArgumentException strategy 未定义 / 该 mapId 不在该层
     */
    public static WaveSpec buildWave(String act, String strategyId, String mapId, int playerCount,
                                     io.mczju.maggoteers.util.GameDifficulty difficulty) {
        return buildWave(act, strategyId, mapId, playerCount, difficulty,
                WavesConfig.getInstance().definitions(),
                MapRepository.library(),
                ScalingConfig.getInstance(),
                loadRunConfig(MaggoteersPlugin.getInstance()));
    }

    /** 纯函数重载（完全注入，便于单测）。 */
    public static WaveSpec buildWave(String act, String strategyId, String mapId, int playerCount,
                                     io.mczju.maggoteers.util.GameDifficulty difficulty,
                                     WaveDefinitions defs, MapLibrary maps, ScalingConfig scaling, RunConfig cfg) {
        SpawnStrategyCfg strat = defs.strategy(strategyId);
        if (strat == null) throw new IllegalArgumentException("strategy 未定义: " + strategyId);
        MapEntry map = maps.maps(act).stream()
                .filter(m -> m.mapId().equals(mapId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("地图不在该层：" + act + "/" + mapId));
        ScalingConfig.Scaling snap = scaling.scaleFor(playerCount, difficulty);
        return expand(strat, map, cfg.origin(act), snap, new SeededRng(stableCountSeed(strategyId)));
    }

    /** 调试换波的"数量缩放"确定性种子：同 strategy 每次换出来的波完全一致。 */
    private static long stableCountSeed(String strategyId) {
        long h = 1125899906842597L;                 // 固定盐，避免 0 种子退化
        for (int i = 0; i < strategyId.length(); i++) h = 31 * h + strategyId.charAt(i);
        return h;
    }

    /**
     * 某层某池的全部 strategy id（**基础池 ∪ 该图 special_waves**，按出现顺序去重）。
     *
     * <p>调试换波的白名单与 Tab 补全都走这里：这样"能点名换的"永远等于"这一波本可能抽到的"。
     */
    public static List<String> poolStrategyIds(String act, String tier, String mapId) {
        return poolStrategyIds(act, tier, mapId, WavesConfig.getInstance().definitions(), MapRepository.library());
    }

    /** 纯函数重载（完全注入，便于单测）。 */
    public static List<String> poolStrategyIds(String act, String tier, String mapId,
                                               WaveDefinitions defs, MapLibrary maps) {
        List<WavesConfig.PoolEntry> all = new ArrayList<>(defs.pool(act, tier));
        if (mapId != null) {
            for (MapEntry entry : maps.maps(act)) {
                if (entry.mapId().equals(mapId)) {
                    all.addAll(entry.specialWaves().getOrDefault(tier, List.of()));
                    break;
                }
            }
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (WavesConfig.PoolEntry e : all) ids.add(e.strategy());
        return List.copyOf(ids);
    }


    /** 全局剧本 seed 下每次池 roll 的唯一盐（含层序 / tier / 层内序号）。 */
    private static long wavePickSalt(int actIndex, String tier, int waveIndexInTier) {
        int tierCode = switch (tier) {
            case "weak" -> 1;
            case "strong" -> 2;
            case "boss" -> 3;
            default -> 0;
        };
        return (actIndex + 1L) * 1_000_000L + tierCode * 10_000L + waveIndexInTier;
    }

    /**
     * 乘客树。{@code parentBossBar} = 父节点是否为 bossbar 怪：**bossbar 豁免向下继承**
     * （2026-10-26：Boss 的坐骑/乘客/分裂体同属"boss 敌人"，数量一律不随 mob_count 缩放）。
     */
    private static List<PassengerSpawn> buildPassengerTrees(List<PassengerCfg> configs,
                                                            ScalingConfig.Scaling snap,
                                                            SeededRng countRng,
                                                            boolean parentBossBar) {
        if (configs == null || configs.isEmpty()) return List.of();
        List<PassengerSpawn> out = new ArrayList<>();
        for (PassengerCfg pc : configs) {
            Compose.MobScale pms = Compose.compose(pc.coeff(), snap);
            boolean exempt = pc.bossBar() || parentBossBar;
            // G9：bossbar 怪（及其下层）数量固定 = 配置原值，不受 mob_count 缩放影响
            int pcnt = exempt ? pc.count()
                    : ScalingConfig.rollCount(pc.count(), snap.mobCount(), countRng);
            List<PassengerSpawn> nested = buildPassengerTrees(pc.passengers(), snap, countRng, exempt);
            List<DeathSpawn> onDeath = buildDeathSpawns(pc.onDeath(), snap, countRng, exempt);
            for (int i = 0; i < pcnt; i++) {
                out.add(new PassengerSpawn(
                        pc.type(), pms.hp(), pms.dmg(), pms.speed(),
                        pms.scale(), pms.followRange(),
                        pc.skills(),
                        pc.equipment(),
                        onDeath,
                        nested,
                        pc.name(),
                        pc.bossBar(),
                        pc.controller()));
            }
        }
        return out;
    }

    /** 亡语分裂树；{@code parentBossBar} 同样向下继承（Boss 的分裂体不参与 mob_count 缩放）。 */
    private static List<DeathSpawn> buildDeathSpawns(List<DeathSpawnCfg> configs,
                                                     ScalingConfig.Scaling snap,
                                                     SeededRng countRng,
                                                     boolean parentBossBar) {
        if (configs == null || configs.isEmpty()) return List.of();
        List<DeathSpawn> out = new ArrayList<>();
        for (DeathSpawnCfg dc : configs) {
            Compose.MobScale dms = Compose.compose(dc.coeff(), snap);
            boolean exempt = dc.bossBar() || parentBossBar;
            // G9：bossbar 怪（及其下层）数量固定 = 配置原值，不受 mob_count 缩放影响
            int dcnt = exempt ? dc.count()
                    : ScalingConfig.rollCount(dc.count(), snap.mobCount(), countRng);
            List<PassengerSpawn> passengers = buildPassengerTrees(dc.passengers(), snap, countRng, exempt);
            List<DeathSpawn> nested = buildDeathSpawns(dc.onDeath(), snap, countRng, exempt);
            for (int i = 0; i < dcnt; i++) {
                out.add(new DeathSpawn(
                        dc.type(),
                        dms.hp(), dms.dmg(), dms.speed(),
                        dms.scale(), dms.followRange(),
                        dc.skills(),
                        dc.equipment(),
                        passengers,
                        nested,
                        dc.name(),
                        dc.bossBar()));
            }
        }
        return out;
    }

    /**
     * 从 config.yml 读层列表 + 层原点 + 每层波数，构造纯 {@link RunConfig}。
     *
     * <p>⚠️ fail-fast：{@code acts} 里的每个层都必须在 {@code act_origins} 与 {@code waves_per_act}
     * 各有一份配置；缺一项直接抛错（历史行为是静默回落 Act1 原点 / 3+2 波，加层时会粘错地图）。
     */
    public static RunConfig loadRunConfig(JavaPlugin plugin) {
        List<String> acts = plugin instanceof MaggoteersPlugin mg
                ? mg.actIds()
                : io.mczju.maggoteers.config.ActsConfig.fromConfig(plugin.getConfig());
        var cfg = plugin.getConfig();
        Map<String, Vec3> origins = new HashMap<>();
        Map<String, int[]> wpa = new HashMap<>();
        for (String act : acts) {
            if (!cfg.isSet("act_origins." + act)) {
                throw new IllegalStateException("config.yml 缺少 act_origins." + act
                        + "（acts 已启用 " + acts + "；缺原点会让该层与其它层原点重叠）");
            }
            if (!cfg.isSet("waves_per_act." + act + ".weak") || !cfg.isSet("waves_per_act." + act + ".strong")) {
                throw new IllegalStateException("config.yml 缺少 waves_per_act." + act
                        + ".weak/strong（acts 已启用 " + acts + "）");
            }
            origins.put(act, Origins.parse(cfg.getIntegerList("act_origins." + act), 64));
            wpa.put(act, new int[]{
                    cfg.getInt("waves_per_act." + act + ".weak"),
                    cfg.getInt("waves_per_act." + act + ".strong")});
        }
        return new RunConfig(acts, origins, wpa);
    }

    private RunPlanner() {}
}
