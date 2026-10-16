package io.github.codeyumx.picofacetracking;

import java.nio.ByteBuffer;

/**
 * One frame of tracking as the headset reports it and the PC receives it: 384 bytes of face data (PxrFTInfo) followed
 * by 152 bytes of eye data (PxrEyePoseDataV2), little-endian. Pico Facial Data Module 0.3 accepts exactly this size.
 * The offsets follow the module's PicoFTInfo and PxrEyePoseDataV2.
 */
final class TrackingFrame {
    static final int FACE_LENGTH = 384;
    static final int EYE_LENGTH = 152;
    static final int LENGTH = FACE_LENGTH + EYE_LENGTH;

    // Face data: a timestamp, then 72 expression weights (the first 52 are named, see Expressions).
    static final int WEIGHTS = 8;
    private static final int EYE_INPUT_VALID = WEIGHTS + 72 * 4;
    private static final int FACE_INPUT_VALID = EYE_INPUT_VALID + 4;
    private static final int LAUGHING = EYE_INPUT_VALID + 10 * 4;

    // Eye data: three status words, three gaze points, then the gaze vectors (x, y, z) used below.
    private static final int LEFT_STATUS = FACE_LENGTH;
    private static final int RIGHT_STATUS = FACE_LENGTH + 4;
    static final int LEFT_GAZE = FACE_LENGTH + 48;
    static final int RIGHT_GAZE = FACE_LENGTH + 60;
    static final int COMBINED_GAZE = FACE_LENGTH + 72;
    static final int LEFT_OPENNESS = FACE_LENGTH + 84;
    static final int RIGHT_OPENNESS = FACE_LENGTH + 88;
    private static final int LEFT_PUPIL = FACE_LENGTH + 92;
    private static final int RIGHT_PUPIL = FACE_LENGTH + 96;

    /** EyePoseStatus.EYE_GAZE_VECTOR_VALID: the eye's own gaze and openness can be used. */
    private static final int GAZE_VECTOR_VALID = 1 << 8;

    final float[] weights = new float[Expressions.COUNT];
    /** Whether the headset sees the eyes and the face (VideoInputValid); the module ignores a part that is not seen. */
    boolean eyesSeen;
    boolean faceSeen;
    float laughing;
    boolean leftValid;
    boolean rightValid;
    float leftGazeX;
    float leftGazeY;
    float rightGazeX;
    float rightGazeY;
    float combinedGazeX;
    float combinedGazeY;
    float leftOpenness;
    float rightOpenness;
    /** Pupil diameter in millimetres, 0 when not measured. */
    float leftPupilMm;
    float rightPupilMm;

    void read(ByteBuffer payload) {
        for (int i = 0; i < weights.length; i++)
            weights[i] = payload.getFloat(WEIGHTS + 4 * i);
        eyesSeen = payload.getFloat(EYE_INPUT_VALID) == 1f;
        faceSeen = payload.getFloat(FACE_INPUT_VALID) == 1f;
        laughing = payload.getFloat(LAUGHING);
        leftValid = (payload.getInt(LEFT_STATUS) & GAZE_VECTOR_VALID) != 0;
        rightValid = (payload.getInt(RIGHT_STATUS) & GAZE_VECTOR_VALID) != 0;
        leftGazeX = payload.getFloat(LEFT_GAZE);
        leftGazeY = payload.getFloat(LEFT_GAZE + 4);
        rightGazeX = payload.getFloat(RIGHT_GAZE);
        rightGazeY = payload.getFloat(RIGHT_GAZE + 4);
        combinedGazeX = payload.getFloat(COMBINED_GAZE);
        combinedGazeY = payload.getFloat(COMBINED_GAZE + 4);
        leftOpenness = payload.getFloat(LEFT_OPENNESS);
        rightOpenness = payload.getFloat(RIGHT_OPENNESS);
        // The module reads the pupil in tenths of a millimetre.
        leftPupilMm = payload.getFloat(LEFT_PUPIL) / 10f;
        rightPupilMm = payload.getFloat(RIGHT_PUPIL) / 10f;
    }

    void copyFrom(TrackingFrame other) {
        System.arraycopy(other.weights, 0, weights, 0, weights.length);
        eyesSeen = other.eyesSeen;
        faceSeen = other.faceSeen;
        laughing = other.laughing;
        leftValid = other.leftValid;
        rightValid = other.rightValid;
        leftGazeX = other.leftGazeX;
        leftGazeY = other.leftGazeY;
        rightGazeX = other.rightGazeX;
        rightGazeY = other.rightGazeY;
        combinedGazeX = other.combinedGazeX;
        combinedGazeY = other.combinedGazeY;
        leftOpenness = other.leftOpenness;
        rightOpenness = other.rightOpenness;
        leftPupilMm = other.leftPupilMm;
        rightPupilMm = other.rightPupilMm;
    }

    // What VRCFaceTracking receives from the module: an eye without a valid gaze vector falls back to the combined
    // gaze and to the blink expression (EyeTrackingParser).

    float shownLeftGazeX() {
        return leftValid ? leftGazeX : combinedGazeX;
    }

    float shownLeftGazeY() {
        return leftValid ? leftGazeY : combinedGazeY;
    }

    float shownRightGazeX() {
        return rightValid ? rightGazeX : combinedGazeX;
    }

    float shownRightGazeY() {
        return rightValid ? rightGazeY : combinedGazeY;
    }

    float shownLeftOpenness() {
        return leftValid ? leftOpenness : 1f - weights[Expressions.EYE_BLINK_L];
    }

    float shownRightOpenness() {
        return rightValid ? rightOpenness : 1f - weights[Expressions.EYE_BLINK_R];
    }
}
