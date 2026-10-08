package io.github.codeyumx.picofacetracking;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Base64;

import java.net.Inet4Address;
import java.nio.charset.StandardCharsets;

/**
 * Lets the install script set the PC address (empty: pair automatically) and the supporter licence, and switch
 * tracking on or off over adb. (The pairing key only comes from pairing by code, see PairingSession.)
 * <pre>
 * adb shell am broadcast -n io.github.codeyumx.picofacetracking/.AdbControlReceiver --es pc_address 192.168.1.20 --ez tracking true
 * </pre>
 * The manifest protects it with android.permission.DUMP, which the adb shell holds and normal apps cannot get,
 * so other apps on the headset cannot start tracking or redirect it to another address.
 */
public final class AdbControlReceiver extends BroadcastReceiver {
    private static final String EXTRA_PC_ADDRESS = "pc_address";
    private static final String EXTRA_TRACKING = "tracking";
    /** Base64 of a licence file (see Licence); empty removes it. */
    private static final String EXTRA_LICENCE = "licence";

    @Override
    public void onReceive(Context context, Intent intent) {
        Prefs prefs = new Prefs(context);

        String address = intent.getStringExtra(EXTRA_PC_ADDRESS);
        if (address != null) {
            // An empty address forgets the paired PC, so the next PC that asks is paired.
            Inet4Address pc = address.isEmpty() ? null : Prefs.parseIpv4(address);
            if (pc == null && !address.isEmpty()) {
                fail("invalid pc_address " + address);
                return;
            }
            String normalized = pc != null ? pc.getHostAddress() : "";
            if (!normalized.equals(prefs.pcAddress())) {
                prefs.setPcAddress(normalized);
                // A running tracker keeps the address it started with.
                if (prefs.enabled()) {
                    TrackingService.stop(context);
                    TrackingService.start(context);
                }
            }
        }

        String licence = intent.getStringExtra(EXTRA_LICENCE);
        if (licence != null) {
            String text;
            try {
                text = new String(Base64.decode(licence, Base64.DEFAULT), StandardCharsets.UTF_8);
                if (!text.isEmpty())
                    Licence.parse(text);
            } catch (IllegalArgumentException e) {
                fail("licence: " + e.getMessage());
                return;
            }
            if (!text.equals(prefs.licence())) {
                prefs.setLicence(text);
                // A running tracker keeps the adjustments it started with.
                if (prefs.enabled()) {
                    TrackingService.stop(context);
                    TrackingService.start(context);
                }
            }
        }

        if (intent.hasExtra(EXTRA_TRACKING)) {
            if (intent.getBooleanExtra(EXTRA_TRACKING, false)) {
                prefs.setEnabled(true);
                TrackingService.start(context);
            } else {
                prefs.setEnabled(false);
                TrackingService.stop(context);
            }
        }

        setResult(Activity.RESULT_OK, "tracking=" + (prefs.enabled() ? "on" : "off") + " pc_address=" + (prefs.pcAddress().isEmpty() ? "auto" : prefs.pcAddress())
                + " eyes=" + (prefs.eyesEnabled() ? "on" : "off") + " mouth=" + (prefs.mouthEnabled() ? "on" : "off")
                + " pairing=" + (prefs.pairingKey().isEmpty() ? "address" : "key") + " licence=" + Licence.state(prefs)
                + " adjustments=" + (Paid.inBuild() ? "in-build" : "not-in-build"), null);
    }

    private void fail(String message) {
        setResult(Activity.RESULT_CANCELED, "error: " + message, null);
    }
}
