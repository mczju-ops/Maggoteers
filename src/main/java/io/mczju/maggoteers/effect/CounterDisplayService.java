package io.mczju.maggoteers.effect;

import io.mczju.maggoteers.config.MessageService;
import io.mczju.maggoteers.item.ItemService;
import io.mczju.maggoteers.reward.CollectibleRegistry;
import io.mczju.maggoteers.state.PlayerState;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 计数器 → 侧边栏（sidebar）显示（2026-10-28 用户需求）。
 *
 * <h2>为什么不是"物品冷却遮罩"/lore</h2>
 * 1. 原版冷却遮罩**无法表示任意比例**：客户端每收到一次 cooldown 包都会新建
 *    {@code CooldownInstance(start=now, end=now+ticks)}，遮罩永远从满圈开始、只能随时间倒着减少
 *    （连发=恒满、不连发=只会自己变空）。详见 dev-log 2026-10-28（八）。
 * 2. lore 只在**手持/悬停**时可见，平时看不到（用户口径）。
 * ⇒ 改为把每位玩家**当前生效的全部计数器**追加到自己的 sidebar 下方（{@code RunScoreboard} 调用本类）。
 *
 * <h2>显示</h2>
 * <pre>
 * 乌萨斯切肉刀      2/3 ✦
 * 紫水晶茄子       45/50
 * </pre>
 * 票券型（切肉刀那种"下一击强化"）一圈 = amount+1 刀（票券到手算完成 amount 步），带 {@code ✦} 表示可释放。
 *
 * <h2>归属 / 名字</h2>
 * 计数器来自某个 {@link PlayerEffect} 的参数，效果 id 决定物品：
 * {@code held:<itemId>:<n>} / {@code ability:<itemId>[:<step>]} → 武器；其它 id（奖励 option id）→ 护符。
 * 名字取该物品的显示名（ItemCreator 生成一次并缓存），拿不到时回落 id。
 */
public final class CounterDisplayService {

    /** 分数板最多追加几行计数器（sidebar 有 15 行上限，基础信息已占 6~10 行）。 */
    static final int MAX_LINES = 6;
    /** 右对齐目标宽度（CJK 记 2，ASCII 记 1）。 */
    static final int TARGET_WIDTH = 20;

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    /** Owner → 显示名（ItemCreator 生成一次后缓存；失败也缓存回落值）。 */
    private static final Map<Owner, String> NAMES = new ConcurrentHashMap<>();

    private CounterDisplayService() {}

    public enum Kind { WEAPON, COLLECTIBLE }

    /** 计数器归属的物品：武器按 ItemCreator id；藏品按奖励 option id（护符 PDC）。 */
    public record Owner(Kind kind, String id) {}

    /**
     * 一条 sidebar 行：{@code done/total}（票券型 done=amount, total=amount+1）、{@code ready}=可释放。
     * <p>2026-10-28：{@code state} 非 null 时**取代** {@code done/total} 显示文字
     * （用于"霜晶树 完好 / 已破损"这类只有状态、没有进度的条目）。
     */
    public record Entry(String name, int done, int total, boolean ready, String state) {
        public Entry(String name, int done, int total, boolean ready) {
            this(name, done, total, ready, null);
        }

        /** 只有状态文字、没有分母的条目（{@code 完好} / {@code 已破损}）。 */
        public static Entry ofState(String name, String state) {
            return new Entry(name, 0, 0, false, state);
        }
    }

    /**
     * 该玩家 sidebar 里要追加的计数器行（legacy 文本，含颜色与右对齐留白）。
     * 没有计数器时返回空表（调用方不追加任何行）。
     */
    public static List<String> sidebarLines(PlayerState ps) {
        return sidebarLines(null, ps);
    }

    /** @param p 可为 null（单元测试/无玩家上下文）；非 null 时额外显示手持武器的"N 发强化"进度。 */
    public static List<String> sidebarLines(Player p, PlayerState ps) {
        List<Entry> entries = entries(p, ps);
        if (entries.isEmpty()) return List.of();
        List<String> out = new ArrayList<>(Math.min(entries.size(), MAX_LINES));
        for (int i = 0; i < entries.size() && i < MAX_LINES; i++) {
            out.add(line(entries.get(i)));
        }
        return out;
    }

    /** 该玩家当前所有可显示的进度（按名字排序，便于稳定显示）。 */
    public static List<Entry> entries(PlayerState ps) {
        return entries(null, ps);
    }

    /**
     * 四类条目合并后显示（2026-10-28 扩展）：
     * <ol>
     *   <li><b>计数器</b>（{@code params.counter}）：波次/击杀/攻击计满驱动的效果；</li>
     *   <li><b>DYNAMIC_ATTRIBUTE 阈值折叠</b>（{@code source: kills + threshold: N}）：芒果 / 袋装水果什锦 /
     *       干扰·懦弱这类"每 N 击杀 +X"，本身没有计数器，但同样该给进度；</li>
     *   <li><b>武器 {@code use_ability.burst_every}</b>：吟游诗人"每第 4 发 300%"，进度存
     *       {@link PlayerState#weaponUses(String)}（只显示**主手**那把，避免背包装备刷屏）；</li>
     *   <li><b>易碎藏品状态</b>（{@code expiry: {trigger: ON_DEATH}}，霜晶树）：显示"完好 / 已破损"。</li>
     * </ol>
     * 同一藏品多条 grant（如袋装水果什锦的物攻/魔攻/装填）会给出完全相同的行，按"名字+数值"去重。
     */
    public static List<Entry> entries(Player p, PlayerState ps) {
        if (ps == null) return List.of();
        List<Entry> out = new ArrayList<>();
        for (CounterState cs : ps.counters().values()) {
            CounterSpec spec = cs.spec();
            if (spec == null) continue;
            int amount = (int) Math.round(spec.amount());
            if (amount < 2) continue;                       // 一步到位的计数器没有"进度"可看
            boolean ticket = ticketCycle(ps, spec.id());
            boolean ready = ticket && ticketPending(ps, spec.id());
            int done = ready ? amount : Math.min(cs.progress(), amount);
            int total = ticket ? amount + 1 : amount;
            Owner owner = ownerOf(ps, spec.id());
            String name = owner == null ? spec.id() : displayName(owner);
            out.add(new Entry(name, done, total, ready));
        }
        addDynamicThresholds(out, ps);
        addBurstProgress(out, p, ps);
        addFragileStates(out, ps);

        // 去重（同名字+同数值只留一行）后按名字排序
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        List<Entry> unique = new ArrayList<>(out.size());
        for (Entry e : out) {
            if (seen.add(e.name() + "|" + e.done() + "/" + e.total() + "|" + e.state())) {
                unique.add(e);
            }
        }
        unique.sort(Comparator.comparing(Entry::name));
        return unique;
    }

    /**
     * 2026-10-28（霜晶树）："死亡即失效"的藏品在 sidebar 显示状态。
     * 判定看 {@code acquiredUnique}（拿过）+ 当前 effects（还在不在）：
     * 有效果 → 完好；拿过但效果已被 `sweepExpiry(ON_DEATH)` 摘掉 → **已破损**。
     */
    private static void addFragileStates(List<Entry> out, PlayerState ps) {
        for (String id : ps.acquiredUnique()) {
            if (!io.mczju.maggoteers.reward.RewardService.isDeathFragile(id)) continue;
            boolean intact = ps.effects().stream().anyMatch(e -> id.equals(e.id()));
            Owner owner = collectibleOwner(id);
            String name = owner == null ? fallbackName(id) : displayName(owner);
            out.add(Entry.ofState(name, intact ? "完好" : "已破损"));
        }
    }

    /** DYNAMIC_ATTRIBUTE 的阈值折叠进度：{@code count % threshold / threshold}。 */
    private static void addDynamicThresholds(List<Entry> out, PlayerState ps) {
        for (PlayerEffect e : ps.effects()) {
            if (!e.isPermanent() || e.effect() != Effect.DYNAMIC_ATTRIBUTE || e.params() == null) continue;
            String source = e.params().get(EffectKeys.SOURCE);
            if (source == null) continue;
            int threshold = e.params().getOrDefault(EffectKeys.THRESHOLD, 0);
            if (threshold < 2) continue;
            int count = switch (source) {
                case "kills" -> ps.killCount();
                case "hits_taken" -> ps.hitsTaken();
                case "deaths" -> ps.deaths();
                case "waves_cleared" -> ps.wavesCleared();
                case "acts_cleared" -> ps.actsCleared();
                default -> -1;                              // coin_count / revive_zero 没有"每 N 次"语义
            };
            if (count < 0) continue;
            Owner owner = collectibleOwner(e.id());
            String name = owner == null ? fallbackName(e.id()) : displayName(owner);
            out.add(new Entry(name, Math.floorMod(count, threshold), threshold, false));
        }
    }

    /** 手持武器的"N 发强化"进度（{@code use_ability.burst_every}）。 */
    private static void addBurstProgress(List<Entry> out, Player p, PlayerState ps) {
        if (p == null) return;
        String weaponId = ItemService.itemIdOf(p.getInventory().getItemInMainHand());
        if (weaponId == null) return;
        ItemAbilityRegistry.Burst burst = ItemAbilityRegistry.burstOf(weaponId);
        if (burst == null || burst.every() < 2) return;
        out.add(burstEntry(weaponId, ps.weaponUses(weaponId), burst.every()));
    }

    /** 可测纯函数：{@code uses % every / every}。 */
    static Entry burstEntry(String weaponId, int used, int every) {
        return new Entry(displayName(new Owner(Kind.WEAPON, weaponId)),
                Math.floorMod(used, every), every, false);
    }

    /** 单行 legacy 文本：{@code <gray>名字<留白><white>x/y}（可释放时补 {@code ✦}；状态条目显示文字）。 */
    static String line(Entry e) {
        String shown = e.state() != null && !e.state().isBlank()
                ? e.state()
                : e.done() + "/" + e.total();
        String value = spacing(e.name()) + shown;
        String legacy = MessageService.legacy("scoreboard.counter_line",
                Map.of("name", e.name(), "value", value));
        if (e.ready()) {
            legacy = legacy + MessageService.legacy("scoreboard.counter_ready", Map.of());
        }
        return legacy;
    }

    /** 右对齐留白（名字越宽留白越少）。 */
    static String spacing(String name) {
        return " ".repeat(Math.max(1, TARGET_WIDTH - displayWidth(name)));
    }

    /** 近似显示宽度：CJK/全角记 2，其余记 1。 */
    static int displayWidth(String s) {
        if (s == null) return 0;
        int w = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            w += cp >= 0x2E80 ? 2 : 1;
            i += Character.charCount(cp);
        }
        return w;
    }

    /** 计数器归属物品的显示名（缓存；拿不到时回落 id 的可读形式）。 */
    static String displayName(Owner owner) {
        return NAMES.computeIfAbsent(owner, o -> {
            String name = null;
            try {
                String itemId = o.kind() == Kind.WEAPON
                        ? o.id()
                        : CollectibleRegistry.resolveItemId(o.id(), 1).orElse(null);
                if (itemId != null) {
                    ItemStack stack = ItemService.createItem(itemId, 1).orElse(null);
                    ItemMeta meta = stack == null ? null : stack.getItemMeta();
                    if (meta != null && meta.displayName() != null) {
                        name = PLAIN.serialize(meta.displayName()).trim();
                    }
                }
            } catch (RuntimeException ignored) {
                // ItemCreator 不可用 → 回落 id
            }
            return name == null || name.isBlank() ? fallbackName(o.id()) : name;
        });
    }

    /** {@code maggoteers:ursus_cleaver} → {@code ursus cleaver}（仅调试/兜底用）。 */
    static String fallbackName(String id) {
        if (id == null || id.isBlank()) return "?";
        String s = id.startsWith("maggoteers:") ? id.substring("maggoteers:".length()) : id;
        return s.replace('_', ' ');
    }

    // ───────────────────────────── 进度语义 ─────────────────────────────

    /**
     * 计数器完成度（0..1）；{@code -1} = 不值得显示（一步到位 / 没有进度）。
     *
     * <p>"票券型"计数器（{@code count: attack} + {@code ON_COUNTER} 的 {@code ADD_ATTRIBUTE}，
     * 即切肉刀那种"累计 N 刀 → **下一刀**一次性强化"）：一圈是 N+1 刀——蓄满 N 刀后还有
     * "释放的那一刀"，所以分母是 N+1、票券到手时算完成 N 步。
     */
    static double fraction(PlayerState ps, CounterState cs) {
        CounterSpec spec = cs.spec();
        if (spec == null) return -1;
        int amount = (int) Math.round(spec.amount());
        if (amount < 2) return -1;
        if (ticketCycle(ps, spec.id())) {
            double done = ticketPending(ps, spec.id()) ? amount : cs.progress();
            return clamp01(done / (amount + 1.0));
        }
        return cs.fraction();
    }

    /** 该计数器是否是"下一刀释放"的票券型（切肉刀类）。 */
    private static boolean ticketCycle(PlayerState ps, String counterId) {
        for (PlayerEffect e : ps.effects()) {
            if (e.fireTrigger() != Trigger.ON_COUNTER || e.effect() != Effect.ADD_ATTRIBUTE) continue;
            if (!counterId.equals(counterIdOf(e))) continue;
            CounterSpec cs = counterSpec(e);
            if (cs != null && cs.countTrigger() == CounterCountTrigger.ATTACK) return true;
        }
        return false;
    }

    /** 票券是否已经到手（{@code <effect>:grant} 且按 ON_DAMAGE_DEALT 到期）。 */
    private static boolean ticketPending(PlayerState ps, String counterId) {
        for (PlayerEffect e : ps.effects()) {
            if (e.fireTrigger() != Trigger.ON_COUNTER || e.effect() != Effect.ADD_ATTRIBUTE) continue;
            if (!counterId.equals(counterIdOf(e))) continue;
            String grantId = TriggeredGrantAttribute.grantId(e.id());
            for (PlayerEffect g : ps.effects()) {
                if (grantId.equals(g.id()) && g.expiryTrigger() == Trigger.ON_DAMAGE_DEALT) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 计数器 → 物品归属。找不到（没有物品效果）返回 null。 */
    static Owner ownerOf(PlayerState ps, String counterId) {
        for (PlayerEffect e : ps.effects()) {
            if (!counterId.equals(counterIdOf(e))) continue;
            Owner owner = collectibleOwner(e.id());
            if (owner != null) return owner;
        }
        return null;
    }

    /** 效果 id → 归属（武器按 PDC id；其它按护符映射，bundle 子项折回父项）。 */
    private static Owner collectibleOwner(String effectId) {
        if (effectId == null || effectId.endsWith(TriggeredGrantAttribute.GRANT_SUFFIX)) return null;
        String weaponId = WeaponHeldService.parseHeldItemId(effectId);
        if (weaponId == null) {
            weaponId = WeaponAbilityIds.itemIdOf(effectId).orElse(null);
        }
        if (weaponId != null) return new Owner(Kind.WEAPON, weaponId);
        return new Owner(Kind.COLLECTIBLE,
                io.mczju.maggoteers.reward.RewardService.collectibleOwnerId(effectId));
    }

    private static String counterIdOf(PlayerEffect e) {
        CounterSpec cs = counterSpec(e);
        return cs == null ? null : cs.id();
    }

    private static CounterSpec counterSpec(PlayerEffect e) {
        if (e == null || e.params() == null) return null;
        return e.params().get(EffectKeys.COUNTER_SPEC);
    }

    private static double clamp01(double v) {
        if (!Double.isFinite(v)) return 0.0;
        return Math.max(0.0, Math.min(1.0, v));
    }

    // ───────────────────────────── 调试 ─────────────────────────────

    /** 调试/`debug counter` 用：计数器 → 显示的一行快照。 */
    public static String describe(PlayerState ps) {
        if (ps == null) return "state=null";
        StringBuilder sb = new StringBuilder("counters=").append(ps.counters().size());
        for (CounterState cs : ps.counters().values()) {
            Owner owner = ownerOf(ps, cs.spec().id());
            sb.append(" | ").append(cs.id())
                    .append(" ").append(cs.progress()).append('/').append(cs.spec().amount())
                    .append(" ticket=").append(ticketPending(ps, cs.spec().id()))
                    .append(" frac=").append(String.format(java.util.Locale.ROOT, "%.2f", fraction(ps, cs)))
                    .append(" owner=").append(owner == null ? "-" : owner.kind() + ":" + owner.id());
        }
        List<String> lines = sidebarLines(ps);
        sb.append(" | lines=").append(lines.size());
        for (String l : lines) sb.append(" [").append(l).append(']');
        return sb.toString();
    }
}
