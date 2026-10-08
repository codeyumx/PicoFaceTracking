package io.github.codeyumx.picofacetracking;

import android.content.Context;
import android.content.SharedPreferences;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

/** Settings shared by the settings screen, the tracking service and the boot receiver. */
final class Prefs {
    private static final String FILE = "settings";
    private static final String ENABLED = "enabled";
    private static final String PC_ADDRESS = "pc_address";
    private static final String START_AT_BOOT = "start_at_boot";
    private static final String PAUSE_FOR_OTHER_APPS = "pause_for_other_apps";

    private final SharedPreferences preferences;

    Prefs(Context context) {
        preferences = open(context);
    }

    /** The settings file, also used by the paid tracking adjustments. */
    static SharedPreferences open(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** Option 1: whether tracking is switched on. */
    boolean enabled() {
        return preferences.getBoolean(ENABLED, false);
    }

    void setEnabled(boolean enabled) {
        preferences.edit().putBoolean(ENABLED, enabled).apply();
    }

    /** The only PC allowed to receive the tracking data. */
    String pcAddress() {
        return preferences.getString(PC_ADDRESS, "");
    }

    void setPcAddress(String address) {
        preferences.edit().putString(PC_ADDRESS, address).apply();
    }

    boolean startAtBoot() {
        return preferences.getBoolean(START_AT_BOOT, true);
    }

    void setStartAtBoot(boolean startAtBoot) {
        preferences.edit().putBoolean(START_AT_BOOT, startAtBoot).apply();
    }

    /** Option 2: do not send tracking while another app, such as PICO Connect, already uses it. */
    boolean pauseForOtherApps() {
        return preferences.getBoolean(PAUSE_FOR_OTHER_APPS, true);
    }

    void setPauseForOtherApps(boolean pause) {
        preferences.edit().putBoolean(PAUSE_FOR_OTHER_APPS, pause).apply();
    }

    /** Whether eye and brow tracking is sent (see FaceParts). The key predates the free switch, so it is kept. */
    boolean eyesEnabled() {
        return preferences.getBoolean("eyes_enabled", true);
    }

    void setEyesEnabled(boolean enabled) {
        preferences.edit().putBoolean("eyes_enabled", enabled).apply();
    }

    /** Whether mouth, jaw, cheek, nose and tongue tracking is sent (see FaceParts). */
    boolean mouthEnabled() {
        return preferences.getBoolean("mouth_enabled", true);
    }

    void setMouthEnabled(boolean enabled) {
        preferences.edit().putBoolean("mouth_enabled", enabled).apply();
    }

    /** Base64 pairing key shared with the PC (protocol version 2), or empty when paired by address. */
    String pairingKey() {
        return preferences.getString("pairing_key", "");
    }

    void setPairingKey(String key) {
        preferences.edit().putString("pairing_key", key).apply();
    }

    /** Text of the supporter licence file (see Licence), or empty. */
    String licence() {
        return preferences.getString("licence", "");
    }

    void setLicence(String licence) {
        preferences.edit().putString("licence", licence).apply();
    }

    /** Parses a dotted IPv4 address without a DNS lookup. Returns null for anything else, including 0.0.0.0. */
    static Inet4Address parseIpv4(String text) {
        String[] parts = text.trim().split("\\.", -1);
        if (parts.length != 4)
            return null;

        byte[] address = new byte[4];
        for (int i = 0; i < 4; i++) {
            String part = parts[i];
            if (part.isEmpty() || part.length() > 3)
                return null;
            for (int j = 0; j < part.length(); j++) {
                if (part.charAt(j) < '0' || part.charAt(j) > '9')
                    return null;
            }
            int value = Integer.parseInt(part);
            if (value > 255)
                return null;
            address[i] = (byte) value;
        }

        try {
            InetAddress parsed = InetAddress.getByAddress(address);
            return parsed.isAnyLocalAddress() ? null : (Inet4Address) parsed;
        } catch (UnknownHostException e) {
            return null;
        }
    }
}
