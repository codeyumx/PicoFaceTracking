package io.github.codeyumx.picofacetracking;

import android.content.Context;
import android.widget.LinearLayout;

import java.nio.ByteBuffer;

/**
 * Hook for the paid tracking adjustments (tongue out, eye widen maximum, blink strength, smoothing and the
 * 52-expression editor). They are not in this repository: the release build adds them from a private source
 * tree, and they only run with a valid supporter licence. See Paid and Licence.
 */
interface PaidFeatures {
    /** Changes one frame in place: 384 bytes of face data, then 152 bytes of eye data, little-endian. */
    interface FrameFilter {
        void apply(ByteBuffer payload);
    }

    /** Loads the saved settings; called before a tracking session starts. */
    void reload(Context context);

    /** A filter for one tracking session; it keeps per-session state such as smoothing. */
    FrameFilter newSession();

    /** Adds the adjustment controls to the settings screen. */
    void addSettings(Context context, LinearLayout content);
}
