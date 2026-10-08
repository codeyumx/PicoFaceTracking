package io.github.codeyumx.picofacetracking;

/** Finds the paid features compiled into this build and checks the supporter licence. */
final class Paid {
    private static final String IMPLEMENTATION = "io.github.codeyumx.picofacetracking.TrackingAdjustments";
    private static final PaidFeatures FEATURES = load();

    private Paid() {
    }

    /** Whether this build contains the paid features (the GitHub release does, a build from this repository does not). */
    static boolean inBuild() {
        return FEATURES != null;
    }

    /** The paid features, or null when they are not in this build or there is no valid licence. */
    static PaidFeatures unlocked(Prefs prefs) {
        return FEATURES != null && Licence.valid(prefs) != null ? FEATURES : null;
    }

    private static PaidFeatures load() {
        try {
            return (PaidFeatures) Class.forName(IMPLEMENTATION).getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }
}
