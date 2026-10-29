package io.mczju.maggoteers.effect;

/** Beam ray length (Excalibur parity): shorten to targeted entity center when present. */
public final class BeamLogic {

    private BeamLogic() {}

    public static double resolveLength(double maxRange, Double targetCenterDistance) {
        if (targetCenterDistance == null || targetCenterDistance <= 0) {
            return maxRange;
        }
        return Math.min(maxRange, targetCenterDistance);
    }
}
