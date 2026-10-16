package io.github.codeyumx.picofacetracking;

import android.os.Binder;
import android.os.Parcel;
import android.os.SystemClock;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Reads the headset's tracking for the Test tab, with or without a PC: the raw frames, before the eyes and mouth
 * switches and the tracking adjustments. Shares the running algorithms with the tracker (see EyeTrackingService.acquire).
 */
final class TrackingPreview implements Runnable {
    private static final int START_TIMEOUT_MS = 1000;
    private static final long START_RETRY_MS = 1000;
    private static final long POLL_MS = 10;
    private static final long STALE_MS = 2000;

    private final Object lock = new Object();
    private final TrackingFrame latest = new TrackingFrame();
    private long sequence;
    private volatile String status = "Starting the headset's eye and face tracking";
    private volatile boolean running = true;

    private TrackingPreview() {
    }

    static TrackingPreview start() {
        TrackingPreview preview = new TrackingPreview();
        new Thread(preview, "TrackingPreview").start();
        return preview;
    }

    /** The thread releases the camera on its own; this returns at once. */
    void stop() {
        running = false;
    }

    /** Null while frames arrive, otherwise why they do not. */
    String status() {
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
        SharedBuffer face = null;
        SharedBuffer eye = null;
        boolean started = false;
        long lastStartAttempt = Long.MIN_VALUE / 2;
        long lastFrame = 0;
        try {
            service = EyeTrackingService.connect();
            service.addServiceListener(serviceListener);
            while (running) {
                long now = SystemClock.elapsedRealtime();
                // The service refuses to start while the headset is not worn, so keep trying.
                if (!started && now - lastStartAttempt >= START_RETRY_MS) {
                    lastStartAttempt = now;
                    try {
                        service.acquire(START_TIMEOUT_MS);
                        started = true;
                        face = service.trackingDataMemory(EyeTrackingService.MEMORY_FACE_TRACKING);
                        eye = service.trackingDataMemory(EyeTrackingService.MEMORY_EYE_TRACKING);
                        lastFrame = now;
                    } catch (EyeTrackingService.ServiceException e) {
                        status = "Waiting for the headset to start tracking (" + e.getMessage() + "). Is it on your head?";
                        face = closeQuietly(face);
                        eye = closeQuietly(eye);
                        if (started)
                            release(service);
                        started = false;
                    }
                }

                if (started) {
                    // Both reads run every time so neither ring buffer falls behind.
                    boolean newFace = face.readLatest(payload, 0, TrackingFrame.FACE_LENGTH);
                    boolean newEye = eye.readLatest(payload, TrackingFrame.FACE_LENGTH, TrackingFrame.EYE_LENGTH);
                    if (newFace || newEye) {
                        scratch.read(payload);
                        synchronized (lock) {
                            latest.copyFrom(scratch);
                            sequence++;
                        }
                        lastFrame = now;
                    }
                    status = now - lastFrame < STALE_MS ? null
                            : "Tracking started, waiting for data. Is the headset on your head?";
                }
                SystemClock.sleep(POLL_MS);
            }
        } catch (EyeTrackingService.ServiceException | RuntimeException e) {
            Log.e(Tracker.TAG, "Test tab tracking stopped", e);
            status = "Stopped: " + (e.getMessage() != null ? e.getMessage() : e.toString());
        } finally {
            closeQuietly(face);
            closeQuietly(eye);
            if (service != null) {
                if (started)
                    release(service);
                try {
                    service.removeServiceListener(serviceListener);
                } catch (EyeTrackingService.ServiceException e) {
                    Log.w(Tracker.TAG, "Cannot remove the service listener: " + e.getMessage());
                }
            }
        }
    }

    private static void release(EyeTrackingService service) {
        try {
            if (service.release())
                Log.i(Tracker.TAG, "Test tab closed, the camera is released.");
        } catch (EyeTrackingService.ServiceException e) {
            Log.w(Tracker.TAG, "Cannot stop tracking: " + e.getMessage());
        }
    }

    private static SharedBuffer closeQuietly(SharedBuffer buffer) {
        if (buffer != null)
            buffer.close();
        return null;
    }
}
