package io.mczju.maggoteers.effect;

import io.mczju.maggoteers.MaggoteersPlugin;
import io.mczju.maggoteers.util.GameRegistries;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.List;

/**
 * AURA 的"脉冲型"授予（2026-10-26 扩展）。
 *
 * <p>历史：AURA 的 {@code grant} 只支持 {@code ADD_ATTRIBUTE} / {@code ADD_POTION} / {@code HEAL}。
 * 现在扩展到任意"对单个接收者立刻生效"的 effect：伤害、冻结、定身、范围治疗效果、上药/清药、关 AI。
 * 由 {@code AuraService} 每 {@code grant_pulse_sec} 秒对半径内每个接收者调用一次。
 *
 * <p>不支持的仍然是不适合"逐目标脉冲"的：{@code SUMMON} / {@code GRANT_ITEM} / {@code AURA}（嵌套）/
 * {@code BUFF_AREA}（区域语义，用 pulse+HEAL_AREA/BUFF 单目标代替）/ {@code DYNAMIC_ATTRIBUTE} 等。
 */
public final class AuraPulse {

    private AuraPulse() {}

    /** 对单个接收者执行一次脉冲授予；不支持的 effect 静默忽略。 */
    public static void apply(LivingEntity receiver, Effect grant, EffectContext p) {
        apply(receiver, grant, p, null);
    }

    /**
     * 带光环携带者（施术者）的版本：治疗量按携带者的 {@code HEAL} 系数缩放。
     *
     * @param ownerUuid 光环来源玩家 UUID（null = 不缩放）
     */
    public static void apply(LivingEntity receiver, Effect grant, EffectContext p, java.util.UUID ownerUuid) {
        if (receiver == null || receiver.isDead() || grant == null || p == null) return;
        switch (grant) {
            case DAMAGE, DAMAGE_AREA, DAMAGE_BEAM -> {
                double dmg = p.getOrDefault(EffectKeys.DAMAGE, 0.0);
                if (dmg > 0) MagicDamage.applyIndependent(receiver, dmg, null);
            }
            case HEAL, HEAL_AREA -> {
                double amount = p.getOrDefault(EffectKeys.AMOUNT, 0.0);
                if (amount <= 0) return;
                if (ownerUuid != null && receiver instanceof Player) {
                    amount *= PlayerCombatStats.healMultiplierByUuid(ownerUuid);
                }
                if (receiver instanceof Player player) {
                    EffectService.heal(player, amount);
                } else {
                    var max = receiver.getAttribute(Attribute.MAX_HEALTH);
                    double cap = max == null ? receiver.getHealth() : max.getValue();
                    receiver.setHealth(Math.min(cap, receiver.getHealth() + amount));
                }
            }
            case FREEZE -> {
                int ticks = Math.max(1, p.getOrDefault(EffectKeys.DURATION_TICKS, 200));
                FreezeService.apply(receiver, ticks);      // 2026-10-29：持续续冻，单次赋值会被迅速衰减
            }
            case IGNITE -> {
                int ticks = Math.max(20, p.getOrDefault(EffectKeys.IGNITE_TICKS, 60));
                if (receiver.getFireTicks() < ticks) receiver.setFireTicks(ticks);
            }
            case STUN -> stun(receiver, p.getOrDefault(EffectKeys.DURATION_TICKS, 20));
            case ADD_POTION, BUFF_AREA -> applyPotion(receiver, p);
            case CLEAR_POTIONS -> {
                List<PotionEffectType> clears = p.get(EffectKeys.CLEAR_POTIONS);
                if (clears != null) clears.forEach(receiver::removePotionEffect);
            }
            case DISABLE_AI -> disableAi(receiver, p.getOrDefault(EffectKeys.DURATION_TICKS, 40));
            default -> { }   // SUMMON / GRANT_ITEM / AURA / …：逐目标脉冲语义不适用
        }
    }

    private static void applyPotion(LivingEntity receiver, EffectContext p) {
        PotionEffectType type = p.get(EffectKeys.POTION);
        if (type == null && p.get(EffectKeys.POTION_NAME) != null) {
            type = GameRegistries.potionEffect(p.get(EffectKeys.POTION_NAME));
        }
        if (type == null) return;
        int amp = p.getOrDefault(EffectKeys.AMP, 0);
        int dur = Math.max(20, p.getOrDefault(EffectKeys.DURATION_TICKS, 100));
        receiver.addPotionEffect(new PotionEffect(type, dur, amp, false, true));
    }

    private static void stun(LivingEntity receiver, int ticks) {
        int dur = Math.max(1, ticks);
        receiver.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, dur, 250, false, true));
        receiver.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, dur, 128, false, false));
    }

    /** 与 DISABLE_AI 语义一致：关 AI 一段时间后恢复（AURA 每脉冲刷新时长，取更晚的恢复点）。 */
    private static void disableAi(LivingEntity receiver, int ticks) {
        if (receiver instanceof Player) return;   // 玩家不关 AI（会卡住操作）
        receiver.setAI(false);
        Bukkit.getScheduler().runTaskLater(MaggoteersPlugin.getInstance(),
                () -> { if (receiver.isValid()) receiver.setAI(true); }, Math.max(1, ticks));
    }
}
