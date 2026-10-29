package io.mczju.maggoteers.codex;

import io.mczju.maggoteers.MaggoteersPlugin;
import io.mczju.maggoteers.config.CoeffCfg;
import io.mczju.maggoteers.config.SpawnStrategyCfg;
import io.mczju.maggoteers.config.StepCfg;
import io.mczju.maggoteers.config.WaveDefinitions;
import io.mczju.maggoteers.config.WavesConfig;
import io.mczju.maggoteers.item.ItemService;
import io.mczju.maggoteers.reward.CollectibleRegistry;
import io.mczju.maggoteers.reward.CollectibleService;
import io.mczju.maggoteers.reward.RewardOption;
import io.mczju.maggoteers.reward.RewardPool;
import io.mczju.maggoteers.reward.RewardService;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

/**
 * 图鉴的"数据层"：把 {@code rewards.yml} / {@code waves.yml} / {@code codex.yml} 折叠成
 * 菜单直接能画的条目列表，并把分页、三态判定抽成纯函数（可单测，不碰世界）。
 *
 * <h2>三态（武器/藏品）</h2>
 * <ol>
 *   <li>{@link State#LOCKED} —— {@code requires_unlock: true} 且玩家还没在局外商店解锁。</li>
 *   <li>{@link State#UNSEEN} —— 已解锁（或无需解锁），但账号没在局内拿到过。</li>
 *   <li>{@link State#OBTAINED} —— 拿到过，直接显示原样。</li>
 * </ol>
 */
public final class CodexEntries {
    private static final Logger LOG = Logger.getLogger("Maggoteers");

    /** 一屏件数 = 武器/藏品菜单的条目槽数（6×9 去掉页脚 = 45）。 */
    public static final int PER_SCREEN = CodexLayout.PAGE_ENTRY_SLOTS.length;

    /** 武器/藏品两本图鉴。 */
    public enum Kind { WEAPON, COLLECTIBLE }

    /** 单件条目在玩家眼里的三种状态。 */
    public enum State { LOCKED, UNSEEN, OBTAINED }

    /**
     * 一条图鉴条目。
     *
     * @param itemId 物品 id（武器 = ItemCreator id；藏品 = 护符 id），画图标用
     */
    public record Entry(String optionId, String display, String itemId, boolean requiresUnlock) {}

    /** 一页 = 一个 reward pool（可能因为超过 {@link #PER_SCREEN} 件而分成多屏）。 */
    public record Section(String poolId, String title, Kind kind, List<Entry> entries) {
        public Section {
            entries = entries == null ? List.of() : List.copyOf(entries);
        }
    }

    /** 一屏：某页里的第 {@code indexInSection}+1 / {@code pagesInSection} 屏，条目区间 [from, to)。 */
    public record Screen(Section section, int indexInSection, int pagesInSection, int from, int to) {
        public List<Entry> entries() {
            return section.entries().subList(from, to);
        }

        /** 标题里显示的页码（1 基）。 */
        public int pageNo() {
            return indexInSection + 1;
        }
    }

    private CodexEntries() {}

    // ================================================================ 三态

    /** 纯函数：给定"需不需要解锁 / 解锁没 / 拿到过没" → 显示哪一态。 */
    public static State stateOf(boolean requiresUnlock, boolean unlocked, boolean obtained) {
        if (requiresUnlock && !unlocked) return State.LOCKED;
        return obtained ? State.OBTAINED : State.UNSEEN;
    }

    // ================================================================ 武器/藏品

    /** 按 {@code codex.yml pages} 的顺序构造全部页；空页（该池没有武器/藏品）自动丢弃。 */
    public static List<Section> sections(Kind kind) {
        return sections(kind, CodexConfig.get());
    }

    static List<Section> sections(Kind kind, CodexConfig cfg) {
        if (kind == null || cfg == null) return List.of();
        List<Section> out = new ArrayList<>();
        for (CodexConfig.Page page : cfg.pages()) {
            RewardPool pool = RewardService.pool(page.poolId());
            if (pool == null) {
                LOG.fine("codex: 池 " + page.poolId() + " 不存在（调过 rewards.yml？），该页跳过");
                continue;
            }
            List<Entry> entries = new ArrayList<>();
            for (RewardOption o : pool.options()) {
                if (!matches(kind, o)) continue;
                String itemId = kind == Kind.WEAPON
                        ? o.item()
                        : CollectibleRegistry.resolveItemId(o.id(), 1).orElse(null);
                entries.add(new Entry(o.id(), o.displayPlain(), itemId, o.requiresUnlock()));
            }
            if (!entries.isEmpty()) {
                out.add(new Section(page.poolId(), page.title(), kind, entries));
            }
        }
        return out;
    }

    /** 某条奖励属于哪本图鉴（武器 = WEAPON；藏品 = 有护符映射的 STAT）。 */
    static boolean matches(Kind kind, RewardOption o) {
        if (o == null || o.id() == null || o.id().isBlank()) return false;
        return switch (kind) {
            case WEAPON -> o.category() == RewardOption.Category.WEAPON;
            case COLLECTIBLE -> o.category() == RewardOption.Category.STAT
                    && CollectibleRegistry.hasMapping(o.id());
        };
    }

    /** 纯函数：把页按每屏 {@code perScreen} 件切成屏（页与页不混屏）。 */
    public static List<Screen> screens(List<Section> sections, int perScreen) {
        if (sections == null || sections.isEmpty()) return List.of();
        int per = Math.max(1, perScreen);
        List<Screen> out = new ArrayList<>();
        for (Section s : sections) {
            int n = s.entries().size();
            if (n <= 0) continue;
            int pages = (n + per - 1) / per;
            for (int p = 0; p < pages; p++) {
                int from = p * per;
                int to = Math.min(n, from + per);
                out.add(new Screen(s, p, pages, from, to));
            }
        }
        return out;
    }

    /** 纯函数：把越界的屏下标夹到 [0, size-1]；空表恒 0。 */
    public static int clampIndex(int index, int size) {
        if (size <= 0) return 0;
        return Math.max(0, Math.min(index, size - 1));
    }

    public static int entryCount(List<Section> sections) {
        int n = 0;
        if (sections == null) return 0;
        for (Section s : sections) n += s.entries().size();
        return n;
    }

    /** 已收集件数（{@code obtained} 里命中的条目数）。 */
    public static int obtainedCount(List<Section> sections, Set<String> obtained) {
        if (sections == null || obtained == null || obtained.isEmpty()) return 0;
        int n = 0;
        for (Section s : sections) {
            for (Entry e : s.entries()) {
                if (obtained.contains(e.optionId())) n++;
            }
        }
        return n;
    }

    /** 条目图标（未附魔处理在菜单里做，这里只负责"拿到那个 ItemStack"）。 */
    public static Optional<ItemStack> previewIcon(Kind kind, Entry entry) {
        if (entry == null) return Optional.empty();
        if (kind == Kind.COLLECTIBLE) return CollectibleService.preview(entry.optionId(), 1);
        if (entry.itemId() == null || entry.itemId().isBlank()) return Optional.empty();
        return ItemService.createItem(entry.itemId(), 1);
    }

    // ================================================================ Boss

    /**
     * Boss 图鉴的一行（一层）。
     *
     * @param actNo 1 基层号（第 1 层 = 1）
     */
    public record BossRow(String actId, int actNo, List<Boss> bosses) {
        public BossRow {
            bosses = bosses == null ? List.of() : List.copyOf(bosses);
        }
    }

    /**
     * 图鉴里的一条 Boss（= 一个刷怪蛋）。
     *
     * @param iconKey 刷怪蛋的**材质名**（如 {@code IRON_GOLEM_SPAWN_EGG}）；解析成 Material 在菜单层做，
     *                这样数据层不碰 Bukkit 注册表，单测能跑
     * @param hp      这一只的最终血量（单人 · 普通难度）；无血条怪时是全关合计
     */
    public record Boss(String strategyId, String name, String iconKey, int hp,
                       String gameplay, String flavor) {}

    /** 实体基础血量取值口（可注入，便于单测不碰 Bukkit 注册表）。 */
    @FunctionalInterface
    public interface BaseHealth {
        double of(EntityType type);
    }

    /** 真实实现：读实体默认属性；史莱姆按 MobFactory 的复位值（4）。 */
    public static final BaseHealth REGISTRY_HEALTH = type -> {
        if (type == null) return 20.0;
        if (type == EntityType.SLIME) return 4.0;
        try {
            if (!type.hasDefaultAttributes()) return 20.0;
            AttributeInstance inst = type.getDefaultAttributes().getAttribute(Attribute.MAX_HEALTH);
            return inst == null ? 20.0 : inst.getBaseValue();
        } catch (RuntimeException | LinkageError ex) {
            return 20.0;
        }
    };

    /** 生产入口：层列表 / 隐藏层 / waves.yml / codex.yml 全部取当前插件状态。 */
    public static List<BossRow> bossRows(Set<String> visitedActs) {
        MaggoteersPlugin plugin = MaggoteersPlugin.getInstance();
        if (plugin == null) return List.of();
        List<String> acts;
        WaveDefinitions defs;
        try {
            acts = plugin.actIds();
            defs = WavesConfig.getInstance().definitions();
        } catch (RuntimeException ex) {
            return List.of();
        }
        String hiddenAct = io.mczju.maggoteers.wave.HiddenActGate.gate().hiddenAct();
        return bossRows(acts, visitedActs, hiddenAct, defs, CodexConfig.get(), REGISTRY_HEALTH);
    }

    /**
     * 纯逻辑：按层生成 Boss 行。
     *
     * <p>规则（用户口径）：
     * <ul>
     *   <li>最多 4 行（1–4 层）；一层一行，行内从左到右紧挨着摆该层的 Boss。</li>
     *   <li>隐藏层（{@code hiddenAct}）在玩家**进过之前**整行不显示（不是显示"？？？"，是留空）。</li>
     * </ul>
     */
    public static List<BossRow> bossRows(List<String> acts, Set<String> visitedActs, String hiddenAct,
                                         WaveDefinitions defs, CodexConfig cfg, BaseHealth health) {
        if (acts == null || acts.isEmpty() || defs == null) return List.of();
        List<BossRow> out = new ArrayList<>();
        for (int i = 0; i < acts.size() && out.size() < 4; i++) {
            String actId = acts.get(i);
            if (actId == null || actId.isBlank()) continue;
            if (hiddenAct != null && hiddenAct.equals(actId)
                    && (visitedActs == null || !visitedActs.contains(actId))) {
                continue;   // 隐藏层没进过 → 整行不显示
            }
            List<Boss> bosses = new ArrayList<>();
            for (WavesConfig.PoolEntry pe : defs.pool(actId, "boss")) {
                bosses.addAll(bossesOf(pe.strategy(), defs, cfg, health));
            }
            out.add(new BossRow(actId, i + 1, bosses));
        }
        return out;
    }

    /**
     * 一条 boss strategy 展开成图鉴里的若干个蛋。
     *
     * <p>规则（用户口径「同一关卡的两个boss紧挨着」）：
     * <ul>
     *   <li>这一关有 N 只**带血条**的怪 → 出 N 个蛋，每只显示**自己**的最终血量
     *       （例：寒渊惜别 = 雪牝 + 霜牡；仁·义·武 = 桓君 + 文衡君 + 昭烈君）。</li>
     *   <li>血条可能挂在**乘客**身上——「卫士不语功」的 Boss 是骑在疣猪兽上的猪灵将军，
     *       血条在 passenger 上而不在 step 上；所以这里会把 passengers 递归翻一遍。</li>
     *   <li>一只血条怪都没有（纯多波杂兵的关底）→ 整关合成 1 个蛋，血量 = 全关合计。</li>
     * </ul>
     * <p>文案优先级（2026-10-30 起）：**这一只 Boss 自己的 `units:` 文案** → 关卡级文案 → 空。
     * 一关多 Boss 的关（寒渊惜别 / 仁·义·武 / 迈入永恒）必须给每只写各自的玩法与风味。
     */
    public static List<Boss> bossesOf(String strategyId, WaveDefinitions defs, CodexConfig cfg,
                                     BaseHealth health) {
        if (strategyId == null || defs == null) return List.of();
        SpawnStrategyCfg strat = defs.strategy(strategyId);
        if (strat == null) return List.of();
        CodexConfig.BossText text = cfg == null ? null : cfg.boss(strategyId);
        BaseHealth h = health == null ? REGISTRY_HEALTH : health;
        String stageName = text != null && text.name() != null ? text.name() : strat.displayNameOrId();
        String stageGameplay = text == null ? "" : text.gameplay();
        String stageFlavor = text == null ? "" : text.flavor();

        List<Unit> bars = new ArrayList<>();
        for (StepCfg step : strat.steps()) {
            if (step.bossBar()) {
                bars.add(new Unit(step.type(), step.name(), step.count(), step.repeat(), step.coeff()));
            }
            collectBarPassengers(step.passengers(), bars);
        }
        if (bars.isEmpty()) {
            return List.of(new Boss(strategyId, stageName, spawnEggKey(bossIconType(strat)),
                    estimateHp(strat, h), stageGameplay, stageFlavor));
        }
        List<Boss> out = new ArrayList<>(bars.size());
        for (Unit unit : bars) {
            String name = unit.name() != null && !unit.name().isBlank() ? unit.name() : stageName;
            CodexConfig.BossText own = cfg == null ? null : cfg.bossUnit(strategyId, unit.name());
            String gameplay = own != null && !own.gameplay().isBlank() ? own.gameplay() : stageGameplay;
            String flavor = own != null && !own.flavor().isBlank() ? own.flavor() : stageFlavor;
            out.add(new Boss(strategyId, name, spawnEggKey(unit.type()), unitHp(unit, h),
                    gameplay, flavor));
        }
        return out;
    }

    /** 图鉴里一只 Boss 的原始数据（来自 step 或 passenger）。 */
    private record Unit(EntityType type, String name, int count, int repeat, CoeffCfg coeff) {}

    /** 乘客里带血条的就是 Boss（递归，乘客还能骑乘客）。 */
    private static void collectBarPassengers(List<io.mczju.maggoteers.config.PassengerCfg> list,
                                             List<Unit> out) {
        if (list == null) return;
        for (var p : list) {
            if (p.bossBar()) out.add(new Unit(p.type(), p.name(), p.count(), 1, p.coeff()));
            collectBarPassengers(p.passengers(), out);
        }
    }

    private static int unitHp(Unit unit, BaseHealth health) {
        double each = health.of(unit.type()) * (unit.coeff() == null ? 1.0 : unit.coeff().hp())
                * Math.max(1, unit.count()) * Math.max(1, unit.repeat());
        return (int) Math.round(each);
    }

    /** 该关的"脸面"：第一条带血条的怪；没有血条则用第一条怪。 */
    static EntityType bossIconType(SpawnStrategyCfg strat) {
        EntityType first = null;
        for (StepCfg step : strat.steps()) {
            if (step.type() == null) continue;
            if (first == null) first = step.type();
            if (step.bossBar()) return step.type();
        }
        return first;
    }

    /** 单步血量：基础血量 × coeff.hp × count × repeat（单人 · 普通难度）。 */
    static int stepHp(StepCfg step, BaseHealth health) {
        double each = health.of(step.type()) * hpCoeff(step)
                * Math.max(1, step.count()) * Math.max(1, step.repeat());
        return (int) Math.round(each);
    }

    /** 血量估算（无血条怪时用）：全关所有步相加。 */
    static int estimateHp(SpawnStrategyCfg strat, BaseHealth health) {
        double sum = 0;
        for (StepCfg step : strat.steps()) sum += stepHp(step, health);
        return (int) Math.round(sum);
    }

    private static double hpCoeff(StepCfg step) {
        CoeffCfg c = step.coeff();
        return c == null ? 1.0 : c.hp();
    }

    /** 实体 → 刷怪蛋的材质名；没有对应蛋的（如发射物）返回 {@code null}（菜单回落 PAPER）。 */
    static String spawnEggKey(EntityType type) {
        if (type == null) return null;
        try {
            return type.getKey().getKey().toUpperCase(Locale.ROOT) + "_SPAWN_EGG";
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }

    // ================================================================ 词条

    /** 词条图鉴的一条（白名单来自 {@code codex.yml affixes}）。{@code iconKey} = 材质名，菜单层解析。 */
    public record Affix(String skillId, String name, String iconKey, String gameplay, String flavor) {}

    /** 白名单顺序 = 摆放顺序；图标缺省为 {@code null}（菜单回落 PAPER）。 */
    public static List<Affix> affixes() {
        return affixes(CodexConfig.get());
    }

    static List<Affix> affixes(CodexConfig cfg) {
        if (cfg == null) return List.of();
        List<Affix> out = new ArrayList<>();
        for (CodexConfig.AffixText t : cfg.affixes()) {
            out.add(new Affix(t.skillId(), t.name(), t.icon(), t.gameplay(), t.flavor()));
        }
        return out;
    }
}
