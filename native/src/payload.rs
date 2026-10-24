//! One tracking frame from the headset (protocol version 1 payload, also the plaintext of a version 2 DATA message)
//! and its mapping to Unified Expressions. Port of the .NET module's `FaceTrackingParser`, `EyeTrackingParser` and
//! `PicoFacialDataPayload`, so both modules produce the same values.

use fb_module_sdk::{Expression as U, TrackingFrame};

/// 384 bytes of face data (`PicoFTInfo`, 380 used) followed by 152 bytes of eye data (`PxrEyePoseDataV2`).
pub const FRAME_LEN: usize = 536;
const EYE_START: usize = 384;

/// `PicoFTInfo`: u64 timestamp, 72 blendshape weights, 10 video-input-valid flags (as floats), ...
const BLENDSHAPES: usize = 8;
const VIDEO_INPUT_VALID: usize = BLENDSHAPES + 72 * 4;
const VIDEO_INPUT_EYE: usize = 0;
const VIDEO_INPUT_FACE: usize = 1;

/// `PxrEyePoseDataV2` field offsets from the start of the eye data.
mod eye {
    pub const LEFT_STATUS: usize = 0;
    pub const RIGHT_STATUS: usize = 4;
    pub const LEFT_GAZE_VECTOR: usize = 48;
    pub const RIGHT_GAZE_VECTOR: usize = 60;
    pub const COMBINED_GAZE_VECTOR: usize = 72;
    pub const LEFT_OPENNESS: usize = 84;
    pub const RIGHT_OPENNESS: usize = 88;
    pub const LEFT_PUPIL_DILATION: usize = 92;
    pub const RIGHT_PUPIL_DILATION: usize = 96;
    /// `EyePoseStatus.EYE_GAZE_VECTOR_VALID`.
    pub const GAZE_VECTOR_VALID: u32 = 1 << 8;
}

/// PICO blendshape indices (`PicoBlendshapes`).
#[allow(dead_code)]
mod pico {
    pub const NOSE_SNEER_L: usize = 1;
    pub const BROW_INNER_UP: usize = 3;
    pub const BROW_DOWN_R: usize = 4;
    pub const MOUTH_CLOSE: usize = 5;
    pub const MOUTH_LOWER_DOWN_R: usize = 6;
    pub const JAW_OPEN: usize = 7;
    pub const MOUTH_UPPER_UP_R: usize = 8;
    pub const MOUTH_SHRUG_UPPER: usize = 9;
    pub const MOUTH_FUNNEL: usize = 10;
    pub const NOSE_SNEER_R: usize = 13;
    pub const MOUTH_ROLL_UPPER: usize = 14;
    pub const JAW_RIGHT: usize = 15;
    pub const BROW_DOWN_L: usize = 16;
    pub const MOUTH_SHRUG_LOWER: usize = 17;
    pub const MOUTH_ROLL_LOWER: usize = 18;
    pub const MOUTH_SMILE_L: usize = 19;
    pub const MOUTH_PRESS_L: usize = 20;
    pub const MOUTH_SMILE_R: usize = 21;
    pub const MOUTH_PRESS_R: usize = 22;
    pub const MOUTH_DIMPLE_R: usize = 23;
    pub const MOUTH_LEFT: usize = 24;
    pub const JAW_FORWARD: usize = 25;
    pub const EYE_SQUINT_L: usize = 26;
    pub const MOUTH_FROWN_L: usize = 27;
    pub const EYE_BLINK_L: usize = 28;
    pub const CHEEK_SQUINT_L: usize = 29;
    pub const BROW_OUTER_UP_L: usize = 30;
    pub const JAW_LEFT: usize = 32;
    pub const MOUTH_STRETCH_L: usize = 33;
    pub const MOUTH_PUCKER: usize = 34;
    pub const BROW_OUTER_UP_R: usize = 36;
    pub const CHEEK_SQUINT_R: usize = 37;
    pub const EYE_BLINK_R: usize = 38;
    pub const MOUTH_UPPER_UP_L: usize = 39;
    pub const MOUTH_FROWN_R: usize = 40;
    pub const EYE_SQUINT_R: usize = 41;
    pub const MOUTH_STRETCH_R: usize = 42;
    pub const CHEEK_PUFF: usize = 43;
    pub const EYE_WIDE_R: usize = 46;
    pub const EYE_WIDE_L: usize = 47;
    pub const MOUTH_RIGHT: usize = 48;
    pub const MOUTH_DIMPLE_L: usize = 49;
    pub const MOUTH_LOWER_DOWN_L: usize = 50;
    pub const TONGUE_OUT: usize = 51;
}

/// Face shapes from PICO blendshapes (taken from ALVR via the .NET module).
const FACE_MAP: &[(U, usize)] = {
    use pico::*;
    &[
        (U::CheekSquintRight, CHEEK_SQUINT_R),
        (U::CheekSquintLeft, CHEEK_SQUINT_L),
        (U::CheekPuffRight, CHEEK_PUFF),
        (U::CheekPuffLeft, CHEEK_PUFF),
        (U::JawOpen, JAW_OPEN),
        (U::MouthClosed, MOUTH_CLOSE),
        (U::JawRight, JAW_RIGHT),
        (U::JawLeft, JAW_LEFT),
        (U::JawForward, JAW_FORWARD),
        (U::LipSuckUpperRight, MOUTH_ROLL_UPPER),
        (U::LipSuckUpperLeft, MOUTH_ROLL_UPPER),
        (U::LipSuckLowerRight, MOUTH_ROLL_LOWER),
        (U::LipSuckLowerLeft, MOUTH_ROLL_LOWER),
        (U::LipFunnelUpperRight, MOUTH_FUNNEL),
        (U::LipFunnelUpperLeft, MOUTH_FUNNEL),
        (U::LipFunnelLowerRight, MOUTH_FUNNEL),
        (U::LipFunnelLowerLeft, MOUTH_FUNNEL),
        (U::LipPuckerUpperRight, MOUTH_PUCKER),
        (U::LipPuckerUpperLeft, MOUTH_PUCKER),
        (U::LipPuckerLowerRight, MOUTH_PUCKER),
        (U::LipPuckerLowerLeft, MOUTH_PUCKER),
        (U::MouthUpperUpRight, MOUTH_UPPER_UP_R),
        (U::MouthUpperUpLeft, MOUTH_UPPER_UP_L),
        (U::MouthUpperDeepenRight, MOUTH_UPPER_UP_R),
        (U::MouthUpperDeepenLeft, MOUTH_UPPER_UP_L),
        (U::NoseSneerRight, NOSE_SNEER_R),
        (U::NoseSneerLeft, NOSE_SNEER_L),
        (U::MouthLowerDownRight, MOUTH_LOWER_DOWN_R),
        (U::MouthLowerDownLeft, MOUTH_LOWER_DOWN_L),
        (U::MouthUpperRight, MOUTH_RIGHT),
        (U::MouthUpperLeft, MOUTH_LEFT),
        (U::MouthLowerRight, MOUTH_RIGHT),
        (U::MouthLowerLeft, MOUTH_LEFT),
        (U::MouthCornerPullRight, MOUTH_SMILE_R),
        (U::MouthCornerPullLeft, MOUTH_SMILE_L),
        (U::MouthCornerSlantRight, MOUTH_SMILE_R),
        (U::MouthCornerSlantLeft, MOUTH_SMILE_L),
        (U::MouthFrownRight, MOUTH_FROWN_R),
        (U::MouthFrownLeft, MOUTH_FROWN_L),
        (U::MouthStretchRight, MOUTH_STRETCH_R),
        (U::MouthStretchLeft, MOUTH_STRETCH_L),
        (U::MouthDimpleRight, MOUTH_DIMPLE_R),
        (U::MouthDimpleLeft, MOUTH_DIMPLE_L),
        (U::MouthRaiserUpper, MOUTH_SHRUG_UPPER),
        (U::MouthRaiserLower, MOUTH_SHRUG_LOWER),
        (U::MouthPressRight, MOUTH_PRESS_R),
        (U::MouthPressLeft, MOUTH_PRESS_L),
        (U::EyeWideRight, EYE_WIDE_R),
        (U::EyeWideLeft, EYE_WIDE_L),
        (U::EyeSquintRight, EYE_SQUINT_R),
        (U::EyeSquintLeft, EYE_SQUINT_L),
        (U::TongueOut, TONGUE_OUT),
    ]
};

/// Brow shapes, written with the eye data.
const BROW_MAP: &[(U, usize)] = {
    use pico::*;
    &[
        (U::BrowPinchRight, BROW_DOWN_R),
        (U::BrowPinchLeft, BROW_DOWN_L),
        (U::BrowLowererRight, BROW_DOWN_R),
        (U::BrowLowererLeft, BROW_DOWN_L),
        (U::BrowInnerUpRight, BROW_INNER_UP),
        (U::BrowInnerUpLeft, BROW_INNER_UP),
        (U::BrowOuterUpRight, BROW_OUTER_UP_R),
        (U::BrowOuterUpLeft, BROW_OUTER_UP_L),
    ]
};

fn f32_at(buf: &[u8], offset: usize) -> f32 {
    f32::from_le_bytes(buf[offset..offset + 4].try_into().expect("4 bytes"))
}

fn u32_at(buf: &[u8], offset: usize) -> u32 {
    u32::from_le_bytes(buf[offset..offset + 4].try_into().expect("4 bytes"))
}

fn blend(buf: &[u8], index: usize) -> f32 {
    f32_at(buf, BLENDSHAPES + 4 * index)
}

/// Writes one `FRAME_LEN`-byte frame into `frame`. Face shapes only when the headset marks the face input valid;
/// eyes and brows only when it marks the eye input valid. A pupil size is only taken while the eye is at least
/// `pupil_openness_threshold` open (the last value stays otherwise).
pub fn apply(buf: &[u8; FRAME_LEN], frame: &mut TrackingFrame, pupil_openness_threshold: f32) {
    if f32_at(buf, VIDEO_INPUT_VALID + 4 * VIDEO_INPUT_FACE) == 1.0 {
        for &(shape, index) in FACE_MAP {
            frame.set_shape(shape, blend(buf, index));
        }
    }
    if f32_at(buf, VIDEO_INPUT_VALID + 4 * VIDEO_INPUT_EYE) != 1.0 {
        return;
    }
    let e = &buf[EYE_START..];
    let combined = (f32_at(e, eye::COMBINED_GAZE_VECTOR), f32_at(e, eye::COMBINED_GAZE_VECTOR + 4));
    let sides = [
        (&mut frame.eye.left, eye::LEFT_STATUS, eye::LEFT_GAZE_VECTOR, eye::LEFT_OPENNESS, eye::LEFT_PUPIL_DILATION, pico::EYE_BLINK_L),
        (&mut frame.eye.right, eye::RIGHT_STATUS, eye::RIGHT_GAZE_VECTOR, eye::RIGHT_OPENNESS, eye::RIGHT_PUPIL_DILATION, pico::EYE_BLINK_R),
    ];
    for (side, status, gaze, openness, pupil, blink) in sides {
        let raw_openness = f32_at(e, openness);
        if u32_at(e, status) & eye::GAZE_VECTOR_VALID != 0 {
            side.gaze_x = f32_at(e, gaze);
            side.gaze_y = f32_at(e, gaze + 4);
            side.openness = raw_openness;
        } else {
            (side.gaze_x, side.gaze_y) = combined;
            side.openness = 1.0 - blend(buf, blink);
        }
        let dilation = match f32_at(e, pupil) {
            0.0 => 35.0,
            d => d,
        };
        if raw_openness > pupil_openness_threshold {
            side.pupil_diameter_mm = dilation / 10.0;
        }
    }
    for &(shape, index) in BROW_MAP {
        frame.set_shape(shape, blend(buf, index));
    }
}

/// Relaxed face and open eyes looking ahead, instead of the last frame frozen while no headset sends. Only the
/// claimed parts: eyes are gaze, openness and the shapes up to `BrowOuterUpLeft`, the face is every shape after it.
pub fn reset_to_neutral(frame: &mut TrackingFrame, eye: bool, expression: bool) {
    let split = U::BrowOuterUpLeft.index() + 1;
    if eye {
        for side in [&mut frame.eye.left, &mut frame.eye.right] {
            side.gaze_x = 0.0;
            side.gaze_y = 0.0;
            side.openness = 1.0;
        }
        frame.shapes[..split].fill(0.0);
    }
    if expression {
        frame.shapes[split..].fill(0.0);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn put(buf: &mut [u8], offset: usize, v: f32) {
        buf[offset..offset + 4].copy_from_slice(&v.to_le_bytes());
    }

    fn frame_bytes() -> [u8; FRAME_LEN] {
        let mut b = [0u8; FRAME_LEN];
        put(&mut b, VIDEO_INPUT_VALID + 4 * VIDEO_INPUT_EYE, 1.0);
        put(&mut b, VIDEO_INPUT_VALID + 4 * VIDEO_INPUT_FACE, 1.0);
        put(&mut b, BLENDSHAPES + 4 * pico::JAW_OPEN, 0.7);
        put(&mut b, BLENDSHAPES + 4 * pico::CHEEK_PUFF, 0.3);
        put(&mut b, BLENDSHAPES + 4 * pico::BROW_INNER_UP, 0.4);
        put(&mut b, BLENDSHAPES + 4 * pico::EYE_BLINK_R, 0.25);
        let e = EYE_START;
        b[e + eye::LEFT_STATUS..e + eye::LEFT_STATUS + 4].copy_from_slice(&eye::GAZE_VECTOR_VALID.to_le_bytes());
        put(&mut b, e + eye::LEFT_GAZE_VECTOR, 0.1);
        put(&mut b, e + eye::LEFT_GAZE_VECTOR + 4, -0.2);
        put(&mut b, e + eye::COMBINED_GAZE_VECTOR, 0.05);
        put(&mut b, e + eye::LEFT_OPENNESS, 0.9);
        put(&mut b, e + eye::RIGHT_OPENNESS, 0.5);
        put(&mut b, e + eye::LEFT_PUPIL_DILATION, 42.0);
        b
    }

    #[test]
    fn face_eyes_and_brows_follow_the_dotnet_module() {
        let mut f = TrackingFrame::default();
        f.eye.right.pupil_diameter_mm = 9.0;
        apply(&frame_bytes(), &mut f, 0.8);
        assert_eq!(f.shape(U::JawOpen), 0.7);
        assert_eq!((f.shape(U::CheekPuffLeft), f.shape(U::CheekPuffRight)), (0.3, 0.3));
        assert_eq!((f.shape(U::BrowInnerUpLeft), f.shape(U::BrowInnerUpRight)), (0.4, 0.4));
        // Left: gaze vector valid, own openness, pupil 42 / 10 since 0.9 > 0.8.
        assert_eq!((f.eye.left.gaze_x, f.eye.left.gaze_y, f.eye.left.openness), (0.1, -0.2, 0.9));
        assert_eq!(f.eye.left.pupil_diameter_mm, 4.2);
        // Right: no valid gaze, so combined gaze and 1 - blink; 0.5 open is below the threshold, pupil unchanged.
        assert_eq!((f.eye.right.gaze_x, f.eye.right.openness), (0.05, 0.75));
        assert_eq!(f.eye.right.pupil_diameter_mm, 9.0);
    }

    #[test]
    fn invalid_inputs_leave_their_part_untouched() {
        let mut b = frame_bytes();
        put(&mut b, VIDEO_INPUT_VALID + 4 * VIDEO_INPUT_FACE, 0.0);
        put(&mut b, VIDEO_INPUT_VALID + 4 * VIDEO_INPUT_EYE, 0.0);
        let mut f = TrackingFrame::default();
        f.set_shape(U::JawOpen, 0.5);
        apply(&b, &mut f, 0.8);
        assert_eq!(f.shape(U::JawOpen), 0.5);
        assert_eq!(f.eye.left.openness, 0.0);
    }

    #[test]
    fn neutral_resets_only_the_claimed_parts() {
        let mut f = TrackingFrame::default();
        apply(&frame_bytes(), &mut f, 0.8);
        reset_to_neutral(&mut f, true, false);
        assert_eq!((f.eye.left.gaze_x, f.eye.left.openness), (0.0, 1.0));
        assert_eq!(f.shape(U::BrowInnerUpLeft), 0.0);
        assert_eq!(f.shape(U::JawOpen), 0.7, "face was not claimed");
        reset_to_neutral(&mut f, false, true);
        assert_eq!(f.shape(U::JawOpen), 0.0);
    }
}
