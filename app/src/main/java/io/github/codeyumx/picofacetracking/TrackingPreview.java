package io.github.codeyumx.picofacetracking;

import android.os.Binder;
import android.os.Parcel;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Reads the headset's tracking for the Test tab, with or without a PC: the raw frames, before the eyes and mouth
 * switches and the tracking adjustments. Shares the running algorithms with the tracker (see EyeTrackingService.acquire).
 */
final class TrackingPreview implements Runnable {
    private static final long POLL_MS = 10;
    private static final long STALE_MS = 2000;
    private static final Text NO_DATA = new Text(R.string.test_no_data);

    private final PowerManager power;
    private final Object lock = new Object();
    private final TrackingFrame latest = new TrackingFrame();
    private long sequence;
    private volatile Text status = new Text(R.string.test_starting);
    private volatile boolean running = true;

    private TrackingPreview(PowerManager power) {
        this.power = power;
    }

    static TrackingPreview start(PowerManager power) {
        TrackingPreview preview = new TrackingPreview(power);
        new Thread(preview, "TrackingPreview").start();
        return preview;
    }

    /** The thread releases the camera on its own; this returns at once. */
    void stop() {
        running = false;
    }

    /** Null while frames arrive, otherwise why they do not. */
    Text status() {
        return status;
    }

    /**
     * Copies the newest frame into destination unless it is the one numbered seen.
     *
     * @return the newest frame's number, 0 before the first frame.
     */
    long copyLatest(TrackingFrame destination, long seen) {
        synchronized (lock) {
            if (sequence != seen)
                destination.copyFrom(latest);
            return sequence;
        }
    }

    @Override
    public void run() {
        // The service keeps a reference to this binder for as long as this process lives (as in Tracker).
        Binder serviceListener = new Binder() {
            @Override
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                return true;
            }
        };
        ByteBuffer payload = ByteBuffer.allocateDirect(TrackingFrame.LENGTH).order(ByteOrder.LITTLE_ENDIAN);
        TrackingFrame scratch = new TrackingFrame();
        EyeTrackingService service = null;
        TrackingSource source = null;
        long lastFrame = 0;
        try {
            service = EyeTrackingService.connect();
            service.addServiceListener(serviceListener);
            source = new TrackingSource(service, power, "Test tab");
            while (running) {
                long now = SystemClock.elapsedRealtime();
                // The service refuses to start while the headset is not worn, so keep trying.
                if (source.startDue(now)) {
                    try {
                        source.start(now);
                        lastFrame = now;
                    } catch (EyeTrackingService.ServiceException e) {
                        status = new Text(R.string.test_waiting_headset, e.getMessage());
                    }
                }

                if (source.started()) {
                    if (source.read(payload, now) != 0) {
                        scratch.read(payload);
                        synchronized (lock) {
                            latest.copyFrom(scratch);
                            sequence++;
                        }
                        lastFrame = now;
                    }
                    status = now - lastFrame < STALE_MS ? null : NO_DATA;
                }
                SystemClock.sleep(POLL_MS);
            }
        } catch (EyeTrackingService.ServiceException | RuntimeException e) {
            Log.e(Tracker.TAG, "Test tab tracking stopped", e);
            status = new Text(R.string.status_stopped, Text.literal(e.getMessage() != null ? e.getMessage() : e.toString()));
        } finally {
            if (source != null)
                source.stop();
            if (service != null) {
                try {
                    service.removeServiceListener(serviceListener);
                } catch (EyeTrackingService.ServiceException e) {
                    Log.w(Tracker.TAG, "Cannot remove the service listener: " + e.getMessage());
                }
            }
        }
    }
}
