package io.github.codeyumx.picofacetracking;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** The eyes and mouth switches against the module's payload layout (PicoFTInfo, then PxrEyePoseDataV2). */
public class FacePartsTest {
    // PicoBlendshapes indices in the module.
    private static final int BROW_INNER_UP = 3;
    private static final int JAW_OPEN = 7;
    private static final int EYE_BLINK_L = 28;
    private static final int CHEEK_PUFF = 43;
    private static final int TONGUE_OUT = 51;
    // PxrEyePoseDataV2 offsets inside the 152 bytes of eye data.
    private static final int LEFT_GAZE_VECTOR = 384 + 48;
    private static final int RIGHT_GAZE_VECTOR = 384 + 60;
    private static final int COMBINED_GAZE_VECTOR = 384 + 72;
    private static final int LEFT_OPENNESS = 384 + 84;
    private static final int RIGHT_OPENNESS = 384 + 88;
    private static final int LEFT_PUPIL = 384 + 92;

    @Test
    public void mouthOffKeepsTheEyes() {
        ByteBuffer frame = trackedFrame();
        new FaceParts(true, false).apply(frame);

        assertEquals(0f, weight(frame, JAW_OPEN), 0f);
        assertEquals(0f, weight(frame, CHEEK_PUFF), 0f);
        assertEquals(0f, weight(frame, TONGUE_OUT), 0f);
        assertEquals(0.5f, weight(frame, EYE_BLINK_L), 0f);
        assertEquals(0.5f, weight(frame, BROW_INNER_UP), 0f);
        assertEquals(0.3f, frame.getFloat(LEFT_OPENNESS), 0f);
        assertEquals(0.5f, frame.getFloat(LEFT_GAZE_VECTOR), 0f);
    }

    @Test
    public void eyesOffSendsOpenEyesLookingAheadAndKeepsTheMouth() {
        ByteBuffer frame = trackedFrame();
        new FaceParts(false, true).apply(frame);

        assertEquals(0f, weight(frame, EYE_BLINK_L), 0f);
        assertEquals(0f, weight(frame, BROW_INNER_UP), 0f);
        assertEquals(1f, frame.getFloat(LEFT_OPENNESS), 0f);
        assertEquals(1f, frame.getFloat(RIGHT_OPENNESS), 0f);
        for (int vector : new int[]{LEFT_GAZE_VECTOR, RIGHT_GAZE_VECTOR, COMBINED_GAZE_VECTOR}) {
            assertEquals(0f, frame.getFloat(vector), 0f);
            assertEquals(0f, frame.getFloat(vector + 4), 0f);
        }
        assertEquals(0.5f, frame.getFloat(LEFT_PUPIL), 0f);
        assertEquals(0.5f, weight(frame, JAW_OPEN), 0f);
        assertEquals(0.5f, weight(frame, TONGUE_OUT), 0f);
    }

    @Test
    public void bothOnLeavesTheFrameUnchanged() {
        ByteBuffer frame = trackedFrame();
        new FaceParts(true, true).apply(frame);

        assertEquals(trackedFrame(), frame);
    }

    /** Every float 0.5, both eyes 30% open. */
    private static ByteBuffer trackedFrame() {
        ByteBuffer frame = ByteBuffer.allocate(384 + 152).order(ByteOrder.LITTLE_ENDIAN);
        for (int offset = 0; offset < frame.capacity(); offset += 4)
            frame.putFloat(offset, 0.5f);
        frame.putFloat(LEFT_OPENNESS, 0.3f);
        frame.putFloat(RIGHT_OPENNESS, 0.3f);
        return frame;
    }

    private static float weight(ByteBuffer frame, int index) {
        return frame.getFloat(8 + 4 * index);
    }
}
