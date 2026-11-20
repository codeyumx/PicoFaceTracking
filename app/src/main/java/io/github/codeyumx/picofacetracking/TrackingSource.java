package io.github.codeyumx.picofacetracking;

import android.os.PowerManager;
import android.util.Log;

import java.nio.ByteBuffer;

/**
 * One reader of the headset's eye and face tracking (the connection to the PC or the Test tab): starts the algorithms
 * through EyeTrackingService.acquire, maps the ring buffers, and starts again when the service dropped them.
 * <p>
 * PICO's service stops eye tracking and forgets every client when the headset sleeps ("stopAlgorithm by sleep") and
 * does not start it again on waking; face tracking can keep running, so eye data stops and the eyes stay grey. So
 * every few seconds the reader checks that the service runs both and that the eye ring buffer gets new slots. (The
 * face ring buffer gets none while the headset is not worn, so it is not checked.)
 */
final class TrackingSource {
    static final int NEW_FACE = 1;
    static final int NEW_EYE = 2;

    private static final int START_TIMEOUT_MS = 1000;
    private static final long START_RETRY_MS = 1000;
    private static final long CHECK_MS = 2000;
    /** The eye ring buffer without a new slot for this long counts as stopped; slots come many times a second. */
    private static final long STALE_MS = 2000;
    /** Starting again does not help when the headset sees nothing at all, so at most this often. */
    private static final long RESTART_INTERVAL_MS = 10_000;

    private final EyeTrackingService service;
    private final PowerManager power;
    /** Names the reader in the log. */
    private final String reader;

    private SharedBuffer face;
    private SharedBuffer eye;
    private boolean started;
    /** The resets() value the lease belongs to. */
    private int lease;
    private long lastStartAttempt = Long.MIN_VALUE / 2;
    private long nextCheck;
    private long lastEye;
    private long lastRestart = Long.MIN_VALUE / 2;

    TrackingSource(EyeTrackingService service, PowerManager power, String reader) {
        this.service = service;
        this.power = power;
        this.reader = reader;
    }

    boolean started() {
        return started;
    }

    /** Whether it is time to try starting: not started, and a second since the last try. Counts as a try. */
    boolean startDue(long now) {
        if (started || now - lastStartAttempt < START_RETRY_MS)
            return false;
        lastStartAttempt = now;
        return true;
    }

    /** Starts tracking, or joins this app's running tracking, and maps the ring buffers. Fails while not worn. */
    void start(long now) throws EyeTrackingService.ServiceException {
        try {
            lease = service.acquire(START_TIMEOUT_MS);
            started = true;
            face = service.trackingDataMemory(EyeTrackingService.MEMORY_FACE_TRACKING);
            eye = service.trackingDataMemory(EyeTrackingService.MEMORY_EYE_TRACKING);
        } catch (EyeTrackingService.ServiceException e) {
            stop();
            throw e;
        }
        lastEye = now;
        nextCheck = now + CHECK_MS;
    }

    /**
     * Copies the newest face slot to the start of payload and the newest eye slot after it.
     *
     * @return NEW_FACE and NEW_EYE for the slots that were new; 0 while not started.
     */
    int read(ByteBuffer payload, long now) {
        // A reset or restart ended the lease; start again at once, without releasing.
        if (started && EyeTrackingService.resets() != lease) {
            drop();
            lastStartAttempt = Long.MIN_VALUE / 2;
        }
        if (!started)
            return 0;

        // Both reads run every time so neither ring buffer falls behind.
        int fresh = 0;
        if (face.readLatest(payload, 0, TrackingFrame.FACE_LENGTH))
            fresh |= NEW_FACE;
        if (eye.readLatest(payload, TrackingFrame.FACE_LENGTH, TrackingFrame.EYE_LENGTH)) {
            fresh |= NEW_EYE;
            lastEye = now;
        }
        if (now >= nextCheck) {
            nextCheck = now + CHECK_MS;
            check(now);
        }
        return fresh;
    }

    /** Starts again when the service no longer runs eye and face tracking for this app, see the class comment. */
    private void check(long now) {
        // Asleep the service stops tracking on purpose; it is started again once the headset is awake.
        if (!power.isInteractive() || now - lastRestart < RESTART_INTERVAL_MS)
            return;
        String problem;
        try {
            problem = !service.trackingRunning() ? "the tracking service stopped eye or face tracking"
                    : now - lastEye > STALE_MS ? "no eye tracking data for " + (now - lastEye) + " ms"
                    : null;
        } catch (EyeTrackingService.ServiceException e) {
            problem = e.getMessage();
        }
        if (problem == null)
            return;
        Log.i(Tracker.TAG, reader + ": " + problem + ", starting tracking again.");
        lastRestart = now;
        service.restart(lease);
        drop();
        lastStartAttempt = Long.MIN_VALUE / 2;
    }

    /** Releases this reader's share of the tracking; the last reader in the app stops it. */
    void stop() {
        boolean release = started;
        int ended = lease;
        drop();
        if (!release)
            return;
        try {
            if (service.release(ended))
                Log.i(Tracker.TAG, "Tracking stopped, the camera is released.");
        } catch (EyeTrackingService.ServiceException e) {
            Log.w(Tracker.TAG, "Cannot stop tracking: " + e.getMessage());
        }
    }

    /** Forgets the lease without releasing it. */
    private void drop() {
        if (face != null)
            face.close();
        if (eye != null)
            eye.close();
        face = null;
        eye = null;
        started = false;
    }
}
