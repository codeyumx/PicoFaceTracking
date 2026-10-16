package io.github.codeyumx.picofacetracking;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** The Test tab's reading of a frame against the module's layout (PicoFTInfo, then PxrEyePoseDataV2). */
public class TrackingFrameTest {
    // PicoFTInfo: ulong timestamp, 72 weights, 10 VideoInputValid (eye, face, ...), LaughingProb.
    private static final int JAW_OPEN = 7;
    private static final int EYE_BLINK_L = 28;
    private static final int EYE_BLINK_R = 38;
    private static final int VIDEO_INPUT_EYE = 8 + 72 * 4;
    private static final int VIDEO_INPUT_FACE = VIDEO_INPUT_EYE + 4;
    private static final int LAUGHING = VIDEO_INPUT_EYE + 40;
    // PxrEyePoseDataV2, from byte 384.
    private static final int LEFT_STATUS = 384;
    private static final int RIGHT_STATUS = 384 + 4;
    private static final int LEFT_GAZE_VECTOR = 384 + 48;
    private static final int RIGHT_GAZE_VECTOR = 384 + 60;
    private static final int COMBINED_GAZE_VECTOR = 384 + 72;
    private static final int LEFT_OPENNESS = 384 + 84;
    private static final int RIGHT_OPENNESS = 384 + 88;
    private static final int RIGHT_PUPIL = 384 + 96;
    private static final int EYE_GAZE_VECTOR_VALID = 1 << 8;

    @Test
    public void readsTheValuesTheModuleReads() {
        ByteBuffer payload = payload();
        payload.putFloat(8 + 4 * JAW_OPEN, 0.6f);
        payload.putFloat(VIDEO_INPUT_EYE, 1f);
        payload.putFloat(LAUGHING, 0.25f);
        payload.putInt(LEFT_STATUS, EYE_GAZE_VECTOR_VALID);
        payload.putFloat(LEFT_GAZE_VECTOR, 0.2f);
        payload.putFloat(LEFT_GAZE_VECTOR + 4, -0.1f);
        payload.putFloat(LEFT_OPENNESS, 0.7f);
        payload.putFloat(RIGHT_PUPIL, 42f);

        TrackingFrame frame = new TrackingFrame();
        frame.read(payload);

        assertEquals(0.6f, frame.weights[JAW_OPEN], 0f);
        assertTrue(frame.eyesSeen);
        assertFalse(frame.faceSeen);
        assertEquals(0.25f, frame.laughing, 0f);
        assertEquals(0.2f, frame.shownLeftGazeX(), 0f);
        assertEquals(-0.1f, frame.shownLeftGazeY(), 0f);
        assertEquals(0.7f, frame.shownLeftOpenness(), 0f);
        assertEquals(4.2f, frame.rightPupilMm, 1e-6f);
    }

    /** As in the module's EyeTrackingParser: no valid gaze vector means combined gaze and 1 - blink. */
    @Test
    public void eyeWithoutValidGazeFallsBack() {
        ByteBuffer payload = payload();
        payload.putFloat(VIDEO_INPUT_FACE, 1f);
        payload.putInt(LEFT_STATUS, EYE_GAZE_VECTOR_VALID);
        payload.putInt(RIGHT_STATUS, 0);
        payload.putFloat(RIGHT_GAZE_VECTOR, 0.9f);
        payload.putFloat(RIGHT_OPENNESS, 0.9f);
        payload.putFloat(COMBINED_GAZE_VECTOR, -0.3f);
        payload.putFloat(COMBINED_GAZE_VECTOR + 4, 0.15f);
        payload.putFloat(8 + 4 * EYE_BLINK_R, 0.8f);
        payload.putFloat(8 + 4 * EYE_BLINK_L, 0.5f);

        TrackingFrame frame = new TrackingFrame();
        frame.read(payload);

        assertTrue(frame.faceSeen);
        assertEquals(-0.3f, frame.shownRightGazeX(), 0f);
        assertEquals(0.15f, frame.shownRightGazeY(), 0f);
        assertEquals(0.2f, frame.shownRightOpenness(), 1e-6f);
        // The left eye's own openness wins over its blink.
        assertEquals(0f, frame.shownLeftOpenness(), 0f);
    }

    private static ByteBuffer payload() {
        return ByteBuffer.allocate(384 + 152).order(ByteOrder.LITTLE_ENDIAN);
    }
}
