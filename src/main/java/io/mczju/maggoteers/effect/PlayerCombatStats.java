package io.mczju.maggoteers.effect;

import io.mczju.maggoteers.game.MaggoteersGame;
import io.mczju.maggoteers.item.ItemKind;
import io.mczju.maggoteers.item.ItemService;
import io.mczju.maggoteers.state.PlayerState;
import io.mczju.maggoteers.state.PlayerStateManager;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

public final class PlayerCombatStats {

    private PlayerCombatStats() {}

    public static double magicDamageMultiplier(MaggoteersGame game, UUID uuid) {
        PlayerState st = PlayerStateManager.get(game, uuid);
        if (st == null) return 1.0;
        return magicDamageMultiplierFromEffects(st.effects(), resolvePlayer(game, uuid));
    }

    /**
     * 法伤倍率 = (1 + Σ PERCENT) × Π(1 + MULTIPLY)。
     * <ul>
     *   <li>{@code op: PERCENT}：加法累加（多个百分比相加，语义同真实属性 ADD_SCALAR）。</li>
     *   <li>{@code op: MULTIPLY}：独立最终乘算（每个效果独立 ×(1+value)，语义同真实属性
     *       MULTIPLY_SCALAR_1 = NMS ADD_MULTIPLIED_TOTAL，如诅咒之刃 ×4）。</li>
     * </ul>
     */
    static double magicDamageMultiplierFromEffects(List<PlayerEffect> effects) {
        return magicDamageMultiplierFromEffects(effects, null);
    }

    /** 带玩家上下文：{@link Effect#DYNAMIC_ATTRIBUTE} 按当前金币/复活次数现算；p 为空时动态层贡献 0。 */
    static double magicDamageMultiplierFromEffects(List<PlayerEffect> effects, Player p) {
        double sumPercent = 0.0;
        double productFinal = 1.0;
        for (PlayerEffect e : effects) {
            double[] c = magicDamageContribution(effects, e, p);
            sumPercent += c[0];
            productFinal *= c[1];
        }
        return (1.0 + sumPercent) * productFinal;
    }

    /** 返回 {PERCENT 累加值, MULTIPLY 独立乘数}；非 MAGIC_DAMAGE 常驻 / 非法 op → {0, 1}。 */
    static double[] magicDamageContribution(PlayerEffect e) {
        return magicDamageContribution(e, null);
    }

    static double[] magicDamageContribution(PlayerEffect e, Player p) {
        return magicDamageContribution(List.of(e), e, p);
    }

    /**
     * 带 effects 上下文：认知失调（SWAP_ATTACK_MAGIC）互换物攻/魔攻，判断归属需知道是否持诅咒。
     * 返回 {PERCENT 累加值, MULTIPLY 独立乘数}；非 MAGIC_DAMAGE 常驻 / 非法 op → {0, 1}。
     */
    private static double[] magicDamageContribution(List<PlayerEffect> effects, PlayerEffect e, Player p) {
        if (e.fireTrigger() != null) return new double[]{0.0, 1.0};
        String attrName = effectiveAttrName(effects, e.params().get(EffectKeys.ATTR_NAME));
        if (attrName == null) return new double[]{0.0, 1.0};
        String op = e.params().getOrDefault(EffectKeys.OP, "FLAT");
        double value;
        if (e.effect() == Effect.DYNAMIC_ATTRIBUTE) {
            if (!VirtualStats.isMagicDamage(attrName)) return new double[]{0.0, 1.0};
            value = p != null ? dynamicAttributeValue(p, e) : 0.0;
        } else if (e.effect() == Effect.ADD_ATTRIBUTE) {
            if (!VirtualStats.isMagicDamage(attrName)) return new double[]{0.0, 1.0};
            value = e.params().getOrDefault(EffectKeys.VALUE, 0.0);
        } else {
            return new double[]{0.0, 1.0};
        }
        if ("PERCENT".equalsIgnoreCase(op)) return new double[]{value, 1.0};
        if ("MULTIPLY".equalsIgnoreCase(op)) return new double[]{0.0, 1.0 + value};
        return new double[]{0.0, 1.0};
    }

    /** 认知失调：effects 携带 SWAP_ATTACK_MAGIC 诅咒时为 true。 */
    public static boolean isSwapCursed(List<PlayerEffect> effects) {
        if (effects == null) return false;
        return effects.stream().anyMatch(e -> e != null && e.effect() == Effect.SWAP_ATTACK_MAGIC);
    }

    /** 认知失调：互换后有效 ATTR_NAME（物攻↔魔攻；其余属性不变）。诅咒由 effects 列表判定（读时互换，不改效果 params）。 */
    public static String effectiveAttrName(List<PlayerEffect> effects, String attrName) {
        if (attrName == null) return null;
        if (!isSwapCursed(effects)) return attrName;
        return switch (attrName) {
            case "ATTACK_DAMAGE" -> "MAGIC_DAMAGE";
            case "MAGIC_DAMAGE" -> "ATTACK_DAMAGE";
            default -> attrName;
        };
    }

    /** 装填速度百分点（基础 100）：最终冷却 = 基础 / (1 + ΣΔ/100)。 */
    public static double reloadSpeedPointsFromEffects(List<PlayerEffect> effects) {
        return coefficientPoints(effects, null, VirtualStats.RELOAD_SPEED);
    }

    public static double reloadSpeedPointsFromEffects(List<PlayerEffect> effects, Player p) {
        return coefficientPoints(effects, p, VirtualStats.RELOAD_SPEED);
    }

    /** 装填速度倍率（1 + ΣΔ/100，下限 0.1；诅咒可低于 1）。 */
    public static double reloadMultiplierFromEffects(List<PlayerEffect> effects) {
        return reloadMultiplierFromEffects(effects, null);
    }

    public static double reloadMultiplierFromEffects(List<PlayerEffect> effects, Player p) {
        return Math.max(0.1, 1.0 + coefficientPoints(effects, p, VirtualStats.RELOAD_SPEED) / 100.0);
    }

    /** 玩家装填速度倍率（无对局状态 → 1.0）。 */
    public static double reloadMultiplier(MaggoteersGame game, UUID uuid) {
        PlayerState st = PlayerStateManager.get(game, uuid);
        if (st == null) return 1.0;
        return reloadMultiplierFromEffects(st.effects(), resolvePlayer(game, uuid));
    }

    /** 实际冷却（秒）= 基础冷却 / 装填速度倍率（下限 0.05s）。 */
    public static double effectiveCooldownSec(MaggoteersGame game, UUID uuid, double baseSec) {
        if (baseSec <= 0) return 0;
        double reload = Math.max(0.1, reloadMultiplier(game, uuid));
        return Math.max(0.05, baseSec / reload);
    }

    // ───────────────────────── 2026-10-27：系数类虚拟属性（HEAL / RANGE）与 UI 倍率 ─────────────────────────

    /** 系数类虚拟属性（基础 100）的百分点总和；只认常驻 ADD_ATTRIBUTE / DYNAMIC_ATTRIBUTE + 同名 attr。 */
    static double coefficientPoints(java.util.List<PlayerEffect> effects, Player p, VirtualStats kind) {
        if (effects == null || effects.isEmpty() || kind == null) return 0.0;
        double sum = 0.0;
        for (PlayerEffect e : effects) {
            sum += pointsOf(e, p, kind);
        }
        return sum;
    }

    /** 单条效果对某虚拟属性的百分点贡献；不符合（触发型 / 其它 attr / MULTIPLY）→ 0。 */
    static double pointsOf(PlayerEffect e, Player p, VirtualStats kind) {
        if (e == null || e.fireTrigger() != null || kind == null) return 0.0;
        if (e.effect() != Effect.ADD_ATTRIBUTE && e.effect() != Effect.DYNAMIC_ATTRIBUTE) return 0.0;
        String attr = attrNameOf(e);
        if (attr == null || !kind.name().equalsIgnoreCase(attr.trim())) return 0.0;
        String op = e.params().get(EffectKeys.OP);
        if (op != null && !"ADD".equalsIgnoreCase(op) && !"PERCENT".equalsIgnoreCase(op)
                && !"FLAT".equalsIgnoreCase(op)) return 0.0;
        double value = e.effect() == Effect.DYNAMIC_ATTRIBUTE
                ? (p != null ? dynamicAttributeValue(p, e) : 0.0)
                : e.params().getOrDefault(EffectKeys.VALUE, 0.0);
        return value * Math.max(1, e.level());
    }

    /** 参数里的属性名：优先 ATTR_NAME（虚拟属性只写这个），其次真实 ATTR 的注册键。 */
    static String attrNameOf(PlayerEffect e) {
        String name = e.params().get(EffectKeys.ATTR_NAME);
        if (name != null) return name;
        var attr = e.params().get(EffectKeys.ATTR);
        return attr == null ? null : attr.getKey().getKey().toUpperCase(java.util.Locale.ROOT);
    }

    /** 治疗强度系数（基础 100）：技能治疗量 ×(100+ΣΔ)/100；下限 0。 */
    public static double healMultiplierFromEffects(java.util.List<PlayerEffect> effects) {
        return healMultiplierFromEffects(effects, null);
    }

    public static double healMultiplierFromEffects(java.util.List<PlayerEffect> effects, Player p) {
        return Math.max(0.0, 1.0 + coefficientPoints(effects, p, VirtualStats.HEAL) / 100.0);
    }

    /** 技能范围系数（基础 100）：半径 / 光束射程 ×(100+ΣΔ)/100；下限 0.1。 */
    public static double rangeMultiplierFromEffects(java.util.List<PlayerEffect> effects) {
        return rangeMultiplierFromEffects(effects, null);
    }

    public static double rangeMultiplierFromEffects(java.util.List<PlayerEffect> effects, Player p) {
        return Math.max(0.1, 1.0 + coefficientPoints(effects, p, VirtualStats.RANGE) / 100.0);
    }

    /** 玩家治疗强度系数（无对局状态 → 1.0）。 */
    public static double healMultiplier(MaggoteersGame game, UUID uuid) {
        PlayerState st = PlayerStateManager.get(game, uuid);
        if (st == null) return 1.0;
        return healMultiplierFromEffects(st.effects(), resolvePlayer(game, uuid));
    }

    /** 玩家技能范围系数（无对局状态 → 1.0）。 */
    public static double rangeMultiplier(MaggoteersGame game, UUID uuid) {
        PlayerState st = PlayerStateManager.get(game, uuid);
        if (st == null) return 1.0;
        return rangeMultiplierFromEffects(st.effects(), resolvePlayer(game, uuid));
    }

    /** 按 UUID 取治疗强度（AURA 的施术者不是接收者时用）。 */
    public static double healMultiplierByUuid(UUID uuid) {
        if (uuid == null) return 1.0;
        PlayerState st = PlayerStateManager.getByUuid(uuid);
        if (st == null) return 1.0;
        return healMultiplierFromEffects(st.effects(), resolvePlayer(PlayerStateManager.gameForPlayer(uuid), uuid));
    }

    /**
     * 物攻倍率（UI 展示）：{@code (1 + Σ PERCENT) × Π(1 + MULTIPLY)}，只统计玩家身上的
     * {@code ATTACK_DAMAGE} 属性效果（**不含**武器自带基础伤害），认知失调诅咒按有效属性名归属。
     */
    public static double attackMultiplier(MaggoteersGame game, UUID uuid) {
        PlayerState st = PlayerStateManager.get(game, uuid);
        if (st == null) return 1.0;
        Player p = resolvePlayer(game, uuid);
        double sum = 0.0;
        double prod = 1.0;
        for (PlayerEffect e : st.effects()) {
            if (e.fireTrigger() != null) continue;
            if (e.effect() != Effect.ADD_ATTRIBUTE && e.effect() != Effect.DYNAMIC_ATTRIBUTE) continue;
            String attr = effectiveAttrName(st.effects(), attrNameOf(e));
            if (attr == null || !"ATTACK_DAMAGE".equalsIgnoreCase(attr.trim())) continue;
            double value = e.effect() == Effect.DYNAMIC_ATTRIBUTE
                    ? (p != null ? dynamicAttributeValue(p, e) : 0.0)
                    : e.params().getOrDefault(EffectKeys.VALUE, 0.0);
            String op = e.params().getOrDefault(EffectKeys.OP, "FLAT");
            if ("PERCENT".equalsIgnoreCase(op)) sum += value;
            else if ("MULTIPLY".equalsIgnoreCase(op)) prod *= 1.0 + value;
        }
        return (1.0 + sum) * prod;
    }

    /** 展示用「冷却倍率」= 1 / 装填倍率（UI 用）。 */
    public static double effectiveCooldownMultiplier(MaggoteersGame game, UUID uuid) {
        return Math.max(0.05, 1.0 / Math.max(0.1, reloadMultiplier(game, uuid)));
    }

    /**
     * {@link Effect#DYNAMIC_ATTRIBUTE} 当前动态值；p 为空或非对局玩家 → 0。
     * <p>求值规则见 {@link #dynamicAttributeValue(EffectContext, int, RunCounters)}（纯函数，可单测）。
     */
    public static double dynamicAttributeValue(Player p, PlayerEffect e) {
        if (p == null || e == null || e.params() == null) return 0.0;
        return dynamicAttributeValue(e.params(), Math.max(1, e.level()), runCounters(p));
    }

    /** 累计类来源的默认折叠阈值：每累计 10 次算一份。 */
    public static final int DEFAULT_THRESHOLD = 10;

    /**
     * 本局计数快照（DYNAMIC_ATTRIBUTE 折叠求值用；纯数据，便于 headless 单测）。
     *
     * @param coins        背包内普通货币枚数
     * @param kills        本局击杀数
     * @param hitsTaken    本局受敌人攻击次数
     * @param deaths       本局死亡次数（含自动复活）
     * @param wavesCleared 本局清波数
     * @param actsCleared  本局已过层数
     * @param revives      剩余自动复活次数；**-1 = 不在对局中（无 PlayerState）**，此时 revive_zero 不贡献
     */
    public record RunCounters(int coins, int kills, int hitsTaken, int deaths,
                              int wavesCleared, int actsCleared, int revives) {
        /** 空快照：无对局（revives = -1，故 revive_zero 不会误判为"复活次数已耗尽"）。 */
        public static final RunCounters EMPTY = new RunCounters(0, 0, 0, 0, 0, 0, -1);
    }

    /**
     * 纯函数：按 {@code params.source} 求当前动态值（唯一求值处）。
     * <ul>
     *   <li><b>累计折叠来源</b>（{@code kills} / {@code hits_taken} / {@code deaths} /
     *       {@code waves_cleared} / {@code acts_cleared}）：
     *       {@code floor(count / threshold) × per_unit × level}（{@code threshold} 默认
     *       {@value #DEFAULT_THRESHOLD}）——即"每累计 N 次加一层"，层数向下取整。</li>
     *   <li>{@code coin_count}（1.3.2 原语义）：{@code per_unit × 金币数 × level}。</li>
     *   <li>{@code revive_zero}（1.3.2 原语义）：剩余复活次数 == 0 时 {@code value × level}，否则 0。</li>
     * </ul>
     * 未知 source → 0（加载期由 {@code RewardLoadValidator} 拦下）。
     */
    public static double dynamicAttributeValue(EffectContext params, int level, RunCounters counters) {
        if (params == null) return 0.0;
        String source = params.get(EffectKeys.SOURCE);
        if (source == null) return 0.0;
        RunCounters c = counters == null ? RunCounters.EMPTY : counters;
        int lv = Math.max(1, level);
        int threshold = Math.max(1, params.getOrDefault(EffectKeys.THRESHOLD, DEFAULT_THRESHOLD));
        double perUnit = params.getOrDefault(EffectKeys.PER_UNIT, 0.0);
        return switch (source) {
            case "coin_count" -> perUnit * c.coins() * lv;
            // revives < 0 = 不在对局（无 PlayerState）→ 不能当成"复活次数已耗尽"
            case "revive_zero" -> c.revives() == 0
                    ? params.getOrDefault(EffectKeys.VALUE, 0.0) * lv
                    : 0.0;
            case "kills" -> folded(c.kills(), threshold, perUnit, lv);
            case "hits_taken" -> folded(c.hitsTaken(), threshold, perUnit, lv);
            case "deaths" -> folded(c.deaths(), threshold, perUnit, lv);
            case "waves_cleared" -> folded(c.wavesCleared(), threshold, perUnit, lv);
            case "acts_cleared" -> folded(c.actsCleared(), threshold, perUnit, lv);
            default -> 0.0;
        };
    }

    /** 阈值折叠：每 {@code threshold} 次累计一份 {@code per_unit}（向下取整，不含余数）。 */
    static double folded(int count, int threshold, double perUnit, int level) {
        int step = threshold < 1 ? 1 : threshold;
        int units = Math.floorDiv(Math.max(0, count), step);
        return units * perUnit * Math.max(1, level);
    }

    /** 采集玩家本局计数快照（金币走背包物品，其余走 PlayerState；无对局状态时仅金币可用）。 */
    static RunCounters runCounters(Player p) {
        if (p == null) return RunCounters.EMPTY;
        int coins = ItemService.countKind(p, ItemKind.CURRENCY_NORMAL);
        MaggoteersGame game = PlayerStateManager.gameOf(p);
        PlayerState st = game == null ? null : PlayerStateManager.get(game, p.getUniqueId());
        if (st == null) return new RunCounters(coins, 0, 0, 0, 0, 0, -1);   // -1 = 不在对局
        return new RunCounters(coins, st.killCount(), st.hitsTaken(), st.deaths(),
                st.wavesCleared(), st.actsCleared(), st.getReviveCount());
    }

    private static Player resolvePlayer(MaggoteersGame game, UUID uuid) {
        if (game == null || uuid == null) return null;
        for (var pe : game.getPlayers()) {
            Player player = pe.player();
            if (player != null && player.getUniqueId().equals(uuid)) return player;
        }
        return null;
    }
}
