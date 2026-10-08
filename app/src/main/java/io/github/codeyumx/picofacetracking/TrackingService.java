package io.github.codeyumx.picofacetracking;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.net.Inet4Address;

/** Keeps tracking running in the background while another app, such as Virtual Desktop, is in front. */
public final class TrackingService extends Service implements Tracker.Listener {
    private static final String ACTION_TURN_OFF = "io.github.codeyumx.picofacetracking.TURN_OFF";
    private static final String CHANNEL_ID = "tracking";
    private static final int NOTIFICATION_ID = 1;

    private static volatile String status = "Off";
    /** True from onCreate until the tracker has stopped in onDestroy. */
    private static volatile boolean running;

    private final Handler mainThread = new Handler(Looper.getMainLooper());
    private Tracker tracker;
    private Thread trackerThread;

    static String status() {
        return status;
    }

    static boolean running() {
        return running;
    }

    /** Pairing by code uses the same port; tracking starts again when it ends. */
    static void start(Context context) {
        if (PairingSession.active())
            return;
        context.startForegroundService(new Intent(context, TrackingService.class));
    }

    static void stop(Context context) {
        context.stopService(new Intent(context, TrackingService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // A service started with startForegroundService must call startForeground, even when it stops right away.
        startForeground(NOTIFICATION_ID, notification("Starting"));

        Prefs prefs = new Prefs(this);
        if (intent != null && ACTION_TURN_OFF.equals(intent.getAction()))
            prefs.setEnabled(false);

        // No PC address: the tracker pairs with the first PC on the network that runs VRCFaceTracking.
        Inet4Address pc = Prefs.parseIpv4(prefs.pcAddress());
        if (!prefs.enabled()) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (tracker == null) {
            PaidFeatures paid = Paid.unlocked(prefs);
            if (paid != null)
                paid.reload(this);
            tracker = new Tracker(pc, Pairing.fromBase64(prefs.pairingKey()), prefs.pauseForOtherApps(), paid, getApplicationContext().getSystemService(WifiManager.class), this);
            trackerThread = new Thread(tracker, "tracking");
            trackerThread.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (tracker != null) {
            tracker.stop();
            try {
                trackerThread.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            tracker = null;
            trackerThread = null;
        }
        if (!status.startsWith("Stopped:"))
            status = "Off";
        running = false;
        super.onDestroy();
    }

    @Override
    public void onStatus(String newStatus) {
        if (newStatus.equals(status))
            return;
        status = newStatus;
        Log.i(Tracker.TAG, newStatus);
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(newStatus));
    }

    @Override
    public void onFailure(String message) {
        status = "Stopped: " + message;
        new Prefs(this).setEnabled(false);
        mainThread.post(this::stopSelf);
    }

    @Override
    public void onPaired(Inet4Address pc) {
        new Prefs(this).setPcAddress(pc.getHostAddress());
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification notification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent turnOff = PendingIntent.getService(this, 1,
                new Intent(this, TrackingService.class).setAction(ACTION_TURN_OFF), PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_notification), "Turn off", turnOff).build())
                .build();
    }
}
