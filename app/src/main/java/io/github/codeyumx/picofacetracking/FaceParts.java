package io.github.codeyumx.picofacetracking;

import java.nio.ByteBuffer;

/**
 * The eyes and mouth switches (free). A part that is switched off is sent as neutral, so VRCFaceTracking keeps the
 * other part: eyes open and looking straight ahead, mouth expressions at zero. A snapshot is immutable; the settings
 * screen swaps in a new one, which a running tracker picks up on its next frame.
 */
final class FaceParts {
    // Offsets in the payload: 384 bytes of face data, then 152 bytes of eye data (little-endian).
    private static final int WEIGHTS = 8;
    private static final int EYE = 384;
    private static final int[] GAZE_VECTORS = {EYE + 48, EYE + 60, EYE + 72};
    private static final int LEFT_OPENNESS = EYE + 84;
    private static final int RIGHT_OPENNESS = EYE + 88;

    private static volatile FaceParts current = new FaceParts(true, true);

    private final boolean eyes;
    private final boolean mouth;

    FaceParts(boolean eyes, boolean mouth) {
        this.eyes = eyes;
        this.mouth = mouth;
    }

    static FaceParts current() {
        return current;
    }

    static void reload(Prefs prefs) {
        current = new FaceParts(prefs.eyesEnabled(), prefs.mouthEnabled());
    }

    /** Changes one frame in place. */
    void apply(ByteBuffer payload) {
        if (eyes && mouth)
            return;
        for (int i = 0; i < Expressions.COUNT; i++) {
            if (!(Expressions.isEyePart(i) ? eyes : mouth))
                payload.putFloat(WEIGHTS + 4 * i, 0f);
        }
        if (!eyes) {
            // Open rather than frozen mid-blink. The module only reads the x and y of each gaze vector.
            payload.putFloat(LEFT_OPENNESS, 1f);
            payload.putFloat(RIGHT_OPENNESS, 1f);
            for (int vector : GAZE_VECTORS) {
                payload.putFloat(vector, 0f);
                payload.putFloat(vector + 4, 0f);
            }
        }
    }
}
