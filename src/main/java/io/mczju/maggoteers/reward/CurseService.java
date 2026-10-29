package io.mczju.maggoteers.reward;

import io.mczju.maggoteers.config.MessageService;
import io.mczju.maggoteers.game.MaggoteersGame;
import io.mczju.maggoteers.state.PlayerStateManager;
import io.mczju.maggoteers.util.GameDifficulty;
import io.mczju.maggoteers.world.WorldService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * 难度负面奖励（诅咒）编排（1.3.3）：进层时按当局难度从对应负面池抽 1 条，发给全体在局玩家。
 * <p>池 id 约定（rewards.yml {@code reward_pools}，纯配置驱动）：
 * {@code curse_hard}（默认仅 Act2）、{@code curse_expert_<actId>}（默认每层各一）。
 * <b>哪一层发哪一池</b>由 config.yml {@code act_rules.curse_hard / curse_expert} 的层 id 列表决定
 * （{@link io.mczju.maggoteers.config.ActRules#cursePool}），加第四层无需改 Java。
 * 负面效果全部用现有 Effect 表达（减攻/减移速/扣生命上限/每进层扣血），无新 Effect 类型。
 */
public final class CurseService {
    private static final Logger LOG = Logger.getLogger("Maggoteers");
    private static final Set<String> WARNED = new HashSet<>();

    private CurseService() {}

    /** 进层时按难度抽 1 条负面奖励，发给全体在局玩家（同一诅咒）。池缺失/空池 warn 一次跳过。 */
    public static void onActEnter(MaggoteersGame game, String actId, int actIndex) {
        if (game == null) return;
        var plugin = io.mczju.maggoteers.MaggoteersPlugin.getInstance();
        var rules = plugin == null ? io.mczju.maggoteers.config.ActRules.defaults() : plugin.actRules();
        String poolId = rules.cursePool(game.getDifficulty(), actId);
        if (poolId == null) return;
        RewardPool pool = RewardService.pool(poolId);
        if (pool == null || pool.options().isEmpty()) {
            if (WARNED.add(poolId)) {
                LOG.warning("负面奖励池 " + poolId + " 缺失或为空，跳过发放（本难度本层不发诅咒）");
            }
            return;
        }
        // 确定性 RNG：seed 与层号混合，同 seed 同层可复现（非战斗内容，开销极小）。
        Random rng = new Random(WorldService.getSeed(game) ^ (actIndex * 0x9E3779B97F4A7C15L));
        List<RewardOption> drawn = RewardService.draw(poolId, 1, rng);   // 无 viewer → 不过可见性过滤
        if (drawn.isEmpty()) return;
        RewardOption curse = drawn.get(0);
        for (UUID uuid : PlayerStateManager.uuidsInGame(game)) {          // 全体在局玩家（含倒下，状态照常记录）
            Player pl = Bukkit.getPlayer(uuid);
            if (pl == null) continue;
            RewardService.apply(pl, curse, game, poolId);                // STAT→applyStat→EffectService.apply，护符自动 grant
        }
        game.sender().warn(MessageService.raw("game.curse_arrive", Map.of("display", curse.displayPlain())));
    }
}
