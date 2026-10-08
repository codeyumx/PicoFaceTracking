package io.github.codeyumx.picofacetracking;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Turns tracking back on after the headset restarts, if it was on and "Start when the headset turns on" is ticked. */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()))
            return;

        Prefs prefs = new Prefs(context);
        if (prefs.enabled() && prefs.startAtBoot())
            TrackingService.start(context);
    }
}
