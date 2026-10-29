package io.mczju.maggoteers.effect;

import java.util.List;
import java.util.function.Supplier;

/** Spec 4.0: dispatch fireTrigger==T, then sweepExpiry. */
public final class TriggerFireOrder {
    private TriggerFireOrder() {}

    public static List<String> run(Runnable dispatch, Supplier<List<String>> sweep) {
        if (dispatch != null) {
            dispatch.run();
        }
        if (sweep == null) {
            return List.of();
        }
        List<String> removed = sweep.get();
        return removed == null ? List.of() : removed;
    }
}
