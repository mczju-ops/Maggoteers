package io.mczju.maggoteers.listener;

import io.mczju.maggoteers.MaggoteersPlugin;
import io.mczju.maggoteers.wave.WaveEngine;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Shulker;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTeleportEvent;

/**
 * 拦下**本局刷出的潜影贝**的传送（2026-10-29，用户设计案「巨人摇篮」）。
 *
 * <h2>为什么要拦</h2>
 * 潜影贝受伤时会瞬移到附近方块上。设计里的「屏障」怪必须**钉在刷怪点上**充当固定炮台：
 * 一旦它被玩家打一下就跑掉，六格免疫环的实际位置与玩家看到的粒子环就会对不上，
 * 整关变成"追着一只会跑的免疫怪"。
 *
 * <h2>做法（对齐 InfernalGuess 的 {@code BattleListener#onEntityTeleport}）</h2>
 * 直接取消 {@link EntityTeleportEvent}。Paper 26.2 的 {@code EntityTeleportEvent} **没有 cause**
 * （InfernalGuess 侧 {@code javap} 实测），所以这里取消的是该实体**所有** Bukkit 可见的传送——
 * 对本插件的潜影贝没有副作用（潜影贝不挂任何 `TELEPORT` 技能）。
 *
 * <h2>作用范围（scope proof）</h2>
 * 只处理 {@link WaveEngine#isTracked} 认得的实体，也就是**本局刷怪漏斗**刷出来的怪
 * （波次 / 乘客 / 亡语 / 召唤）。世界里的野生潜影贝、大厅里别的插件的潜影贝一律不碰。
 *
 * <p>配置开关：{@code mob_rules.block_shulker_teleport}（缺键按 {@code true}，现场配置只增不改）。
 */
public class MobTeleportGuardListener implements Listener {

    /** config 键：{@code true} = 拦下本局刷出的潜影贝传送。 */
    public static final String CONFIG_KEY = "mob_rules.block_shulker_teleport";

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityTeleport(EntityTeleportEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof Shulker)) return;                 // 只认潜影贝（末影人暂无需求）
        if (!WaveEngine.isTracked(entity.getUniqueId())) return;   // 不是本局的兵 → 一律不碰
        if (!enabled()) return;
        event.setCancelled(true);
    }

    /** 配置开关（缺键 / 无插件上下文 → 视为开启）。 */
    static boolean enabled() {
        MaggoteersPlugin plugin = MaggoteersPlugin.getInstance();
        if (plugin == null) return true;
        try {
            return plugin.getConfig().getBoolean(CONFIG_KEY, true);
        } catch (RuntimeException e) {
            return true;
        }
    }
}
