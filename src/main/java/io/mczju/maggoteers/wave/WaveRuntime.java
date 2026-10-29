package io.mczju.maggoteers.wave;

import com.github.mczjuops.mczjugamecore.game.AbstractGame;
import net.kyori.adventure.bossbar.BossBar;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 单对局波次运行时状态（借鉴前代 VampireSurvivor WaveContext）。
 * <p>由 {@link WaveEngine} 以 {@code IdentityHashMap<game, runtime>} 持有；心跳扫描由 WaveScheduler 统一驱动。
 */
public final class WaveRuntime {
    final AbstractGame game;
    /** 本波仍存活怪物 UUID。 */
    final Set<UUID> livingMobs = new HashSet<>();
    /** UUID → 运行时档案（死亡召唤）。 */
    final Map<UUID, MobSpawnProfile> mobProfiles = new HashMap<>();
    /** Boss 血条（Adventure；按实体 UUID）。 */
    final Map<UUID, BossBar> bossBars = new HashMap<>();
    /** 当前波的 Boss 阶段表（2026-10-26；beginWave 时写入，空 = 单阶段）。 */
    java.util.List<io.mczju.maggoteers.config.BossPhaseCfg> phases = java.util.List.of();

    /**
     * 本波要记进图鉴的 boss strategy（2026-10-30）。
     *
     * <p>只在**那只带血条的 Boss 真的刷出来**时才结算（{@link WaveEngine#trackSpawned} 里），
     * 而不是"波次一开始就记"——boss 波前面还有好几批杂兵，玩家可能几十秒后才见到本体。
     */
    String bossCodexStrategy;

    WaveRuntime(AbstractGame game) { this.game = game; }
}
