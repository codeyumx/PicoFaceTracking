package io.github.codeyumx.picofacetracking;

/**
 * The 52 face expressions PICO's tracking service reports, in its order (the first 52 of PxrFTInfo's 72 weights).
 * Names follow PicoBlendshapes in github.com/thoricelli/PicoFacialDataModule.
 */
final class Expressions {
    static final String[] NAMES = {
            "EyeLookDownL", "NoseSneerL", "EyeLookInL", "BrowInnerUp", "BrowDownR", "MouthClose", "MouthLowerDownR",
            "JawOpen", "MouthUpperUpR", "MouthShrugUpper", "MouthFunnel", "EyeLookInR", "EyeLookDownR", "NoseSneerR",
            "MouthRollUpper", "JawRight", "BrowDownL", "MouthShrugLower", "MouthRollLower", "MouthSmileL", "MouthPressL",
            "MouthSmileR", "MouthPressR", "MouthDimpleR", "MouthLeft", "JawForward", "EyeSquintL", "MouthFrownL",
            "EyeBlinkL", "CheekSquintL", "BrowOuterUpL", "EyeLookUpL", "JawLeft", "MouthStretchL", "MouthPucker",
            "EyeLookUpR", "BrowOuterUpR", "CheekSquintR", "EyeBlinkR", "MouthUpperUpL", "MouthFrownR", "EyeSquintR",
            "MouthStretchR", "CheekPuff", "EyeLookOutL", "EyeLookOutR", "EyeWideR", "EyeWideL", "MouthRight",
            "MouthDimpleL", "MouthLowerDownL", "TongueOut",
    };

    static final int COUNT = NAMES.length;

    static final int EYE_BLINK_L = 28;
    static final int EYE_BLINK_R = 38;
    static final int EYE_WIDE_R = 46;
    static final int EYE_WIDE_L = 47;
    static final int TONGUE_OUT = 51;

    /** Display groups, in screen order. */
    static final String[] GROUPS = {"Eye", "Brow", "Cheek", "Nose", "Jaw", "Mouth", "Tongue"};

    private Expressions() {
    }

    static String group(int index) {
        for (String group : GROUPS) {
            if (NAMES[index].startsWith(group))
                return group;
        }
        throw new IllegalArgumentException(NAMES[index]);
    }

    /** Eye and brow expressions belong to the eyes switch; everything else to the mouth switch. See FaceParts. */
    static boolean isEyePart(int index) {
        String group = group(index);
        return group.equals("Eye") || group.equals("Brow");
    }

    /** "EyeWideL" becomes "Eye wide left", "MouthShrugUpper" becomes "Mouth shrug upper". */
    static String label(int index) {
        String name = NAMES[index];
        String side = "";
        if (name.endsWith("L") || name.endsWith("R")) {
            side = name.endsWith("L") ? " left" : " right";
            name = name.substring(0, name.length() - 1);
        }
        StringBuilder label = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (i > 0 && Character.isUpperCase(c))
                label.append(' ').append(Character.toLowerCase(c));
            else
                label.append(c);
        }
        return label.append(side).toString();
    }
}
