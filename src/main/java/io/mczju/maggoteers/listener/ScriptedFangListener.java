package io.mczju.maggoteers.listener;

import org.bukkit.entity.EvokerFangs;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

/**
 * 脚本尖牙的伤害守卫（2026-10-26，门扉缔造者）。
 *
 * <p>{@code FANG_PATTERN} 会生成**真·唤魔尖牙**做视觉，但用户口径是「法阵不应用尖牙的伤害」——
 * 伤害由插件按格子结算（20 点魔法伤害）。所以：凡带 {@code maggoteers_scripted_fang} 标记的尖牙
 * 造成的伤害一律取消。普通唤魔者的尖牙不带标记，不受影响。
 */
public class ScriptedFangListener implements Listener {

    public static final String MARKER = "maggoteers_scripted_fang";

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFangDamage(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof EvokerFangs fang)) return;
        if (fang.hasMetadata(MARKER)) e.setCancelled(true);
    }
}
