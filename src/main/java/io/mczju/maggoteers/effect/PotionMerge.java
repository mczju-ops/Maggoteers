package io.mczju.maggoteers.effect;

import org.bukkit.entity.LivingEntity;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/** Potion merge rules for BUFF_AREA timed buffs. */
public final class PotionMerge {

    public enum Decision { IGNORE, EXTEND, REPLACE }

    private PotionMerge() {}

    /** existingAmp < 0 means no active effect of this type. */
    public static Decision decide(int existingAmp, int existingTicksRemaining,
                                  int newAmp, int newDurationTicks) {
        if (newDurationTicks <= 0) return Decision.IGNORE;
        if (existingAmp < 0) return Decision.REPLACE;
        if (existingAmp > newAmp) return Decision.IGNORE;
        if (existingAmp < newAmp) return Decision.REPLACE;
        return existingTicksRemaining >= newDurationTicks ? Decision.IGNORE : Decision.EXTEND;
    }

    public static boolean applyIfNeeded(LivingEntity target, PotionEffectType type, int amp, int durationTicks) {
        if (target == null || type == null || durationTicks <= 0) return false;
        if (!target.isValid() || target.isDead()) return false;
        if (target instanceof org.bukkit.entity.Player pl && PotionImmunity.isImmune(pl, type)) {
            return false;
        }
        PotionEffect existing = target.getPotionEffect(type);
        int curAmp = existing == null ? -1 : existing.getAmplifier();
        int curTicks = existing == null ? 0 : existing.getDuration();
        Decision d = decide(curAmp, curTicks, amp, durationTicks);
        if (d == Decision.IGNORE) return false;
        target.addPotionEffect(new PotionEffect(type, durationTicks, amp, false, true), true);
        return true;
    }
}
