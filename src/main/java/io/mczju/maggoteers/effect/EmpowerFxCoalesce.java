package io.mczju.maggoteers.effect;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/** Coalesce deferred pack ability ids into one empower play per itemId. */
public final class EmpowerFxCoalesce {
    private EmpowerFxCoalesce() {}

    public enum Choice {
        /** Explicit use_ability.empower_fx */
        EMPOWER,
        /** Visible top-level cast fx (preset not NONE) */
        CAST,
        /** Built-in next-hit strike flash for deferred-only packs */
        STRIKE,
        /** Do not play */
        NONE
    }

    public static Set<String> itemIdsTouched(Collection<String> effectIds) {
        Set<String> out = new HashSet<>();
        if (effectIds == null) {
            return out;
        }
        for (String id : effectIds) {
            WeaponAbilityIds.itemIdOf(id).ifPresent(out::add);
        }
        return out;
    }

    /**
     * Empower FX source for a consumed deferred pack.
     *
     * @param hasEmpowerFx explicit empower_fx present
     * @param deferredOnly all ability steps have expiry
     * @param castVisible top-level fx present with a non-{@code NONE} preset
     */
    public static Choice choose(boolean hasEmpowerFx, boolean deferredOnly, boolean castVisible) {
        if (hasEmpowerFx) {
            return Choice.EMPOWER;
        }
        if (deferredOnly) {
            return castVisible ? Choice.CAST : Choice.STRIKE;
        }
        return castVisible ? Choice.CAST : Choice.NONE;
    }
}
