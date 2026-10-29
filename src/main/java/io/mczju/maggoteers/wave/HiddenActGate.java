package io.mczju.maggoteers.wave;

import io.mczju.maggoteers.MaggoteersPlugin;
import io.mczju.maggoteers.config.MessageService;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * 隐藏结局层（Act4）的门控：钥匙物品 → 进层。
 *
 * <h2>链路（2026-10-26 阶段 5「链路验证」）</h2>
 * <pre>
 * 打掉 act3 Boss → 终局休整（此时 act3_boss 商店有「深渊之钥」，1 枚 Boss 货币）
 *   ├─ 有人买钥匙并在休整期右键 → 进隐藏层 Act4（单波 Boss）→ 清掉 → 通关/停表
 *   └─ 没人用钥匙（或没买）→ 休整结束/全员跳过 → advance 跳过隐藏层 → 正常通关
 * </pre>
 *
 * <p><b>为什么"不自动推进"</b>：`acts` 里确实有 act4，但只有 {@link #gate()} 判定允许且玩家
 * 主动用钥匙才会 {@code enterHiddenAct}；否则 {@code advance} 会**跳过**它。这样"隐藏层"不会变成
 * "人人都得打第四层"，旧配置（{@code hidden_act: ""}）行为与 1.3.3 完全一致。
 *
 * <p>本类的 {@link Gate} 是纯逻辑（不碰 Bukkit），单测覆盖"跳过/进入/终局下标"三种分支。
 */
public final class HiddenActGate {

    /**
     * 门控配置（来自 {@code config.yml}）。
     *
     * @param hiddenAct  隐藏层 id；{@code null} = 没有隐藏层
     * @param gateAfter  打完这层的 Boss 后进入终局休整、允许用钥匙
     */
    public record Gate(String hiddenAct, String gateAfter) {

        public static final Gate NONE = new Gate(null, null);

        /** 解析配置；任何不一致（id 不在 acts、gate 层在隐藏层之后等）都降级为"无隐藏层"并抛给调用方判定。 */
        public static Gate parse(ConfigurationSection cfg, List<String> acts) {
            if (cfg == null) return NONE;
            String hidden = trimToNull(cfg.getString("hidden_act"));
            if (hidden == null || acts == null || !acts.contains(hidden)) return NONE;
            String gate = trimToNull(cfg.getString("hidden_gate_after"));
            int hiddenIdx = acts.indexOf(hidden);
            int gateIdx = gate == null ? hiddenIdx - 1 : acts.indexOf(gate);
            if (gateIdx < 0 || gateIdx >= hiddenIdx) return NONE;   // 门控层必须在隐藏层之前
            return new Gate(hidden, acts.get(gateIdx));
        }

        public boolean active() {
            return hiddenAct != null && gateAfter != null;
        }

        public int hiddenIndex(List<String> acts) {
            return active() && acts != null ? acts.indexOf(hiddenAct) : -1;
        }

        public int gateIndex(List<String> acts) {
            return active() && acts != null ? acts.indexOf(gateAfter) : -1;
        }

        /**
         * 清完 {@code currentIndex} 层的 Boss 后，下一个要进的层下标。
         *
         * <p>没开门（{@code entered=false}）且下一层正好是隐藏层 ⇒ **跳过它**（直接走到通关）。
         *
         * @return 下一层下标；{@code >= acts.size()} 表示没有下一层（该通关了）
         */
        public int nextIndexAfterClear(List<String> acts, int currentIndex, boolean entered) {
            if (acts == null) return currentIndex + 1;
            int next = currentIndex + 1;
            int hiddenIdx = hiddenIndex(acts);
            if (!entered && hiddenIdx >= 0 && next == hiddenIdx) next++;
            return next;
        }

        /**
         * 本局"有效终局层"下标：进了隐藏层 = 隐藏层；没进 = 隐藏层的前一层
         * （用于"最终 Boss"氛围音效 / 终局休整播报）。
         */
        public int effectiveFinalIndex(List<String> acts, boolean entered) {
            if (acts == null || acts.isEmpty()) return 0;
            int hiddenIdx = hiddenIndex(acts);
            if (hiddenIdx < 0) return acts.size() - 1;
            return entered ? hiddenIdx : Math.max(0, hiddenIdx - 1);
        }

        private static String trimToNull(String s) {
            if (s == null) return null;
            String t = s.trim();
            return t.isEmpty() ? null : t;
        }
    }

    private HiddenActGate() {
    }

    /** 当前生效的门控（读 config.yml；插件未就绪 / 旧配置时为 {@link Gate#NONE}）。 */
    public static Gate gate() {
        MaggoteersPlugin plugin = MaggoteersPlugin.getInstance();
        if (plugin == null) return Gate.NONE;
        try {
            return Gate.parse(plugin.getConfig(), plugin.actIds());
        } catch (RuntimeException ex) {
            return Gate.NONE;
        }
    }

    /**
     * 钥匙右键入口（{@code ItemInteractRouter} 的 {@code HIDDEN_KEY} 处理器调用）。
     *
     * @return true = 已进入隐藏层并消耗钥匙
     */
    public static boolean tryEnter(Player player, com.github.mczjuops.mczjugamecore.game.AbstractGame game,
                                  ItemStack stack) {
        if (player == null || game == null) return false;
        String error = WaveScheduler.enterHiddenAct(game);
        if (error != null) {
            player.sendMessage(MessageService.component("wave.hidden_key_denied", java.util.Map.of()));
            player.sendMessage(net.kyori.adventure.text.Component.text(error,
                    net.kyori.adventure.text.format.NamedTextColor.GRAY));
            return false;
        }
        if (stack != null && stack.getAmount() > 0) {
            stack.setAmount(stack.getAmount() - 1);
        }
        player.getWorld().playSound(player.getLocation(),
                org.bukkit.Sound.BLOCK_END_PORTAL_SPAWN, 1.5f, 0.6f);
        return true;
    }
}
