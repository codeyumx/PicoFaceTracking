package io.github.codeyumx.picofacetracking;

import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import java.io.IOException;
import java.lang.reflect.Method;

/**
 * Raw binder client for PICO's eye and face tracking service (pxreyetrackingservice, interface pvr.IEyeTrackingService).
 * The transaction layout comes from github.com/thoricelli/PicoFacialDataDaemon and github.com/thoricelli/PICO-documentation.
 */
final class EyeTrackingService {
    private static final String SERVICE_NAME = "pxreyetrackingservice";
    private static final String DESCRIPTOR = "pvr.IEyeTrackingService";

    private static final int START_ALGORITHM = IBinder.FIRST_CALL_TRANSACTION + 5;
    private static final int GET_ALGORITHM_RESULT = IBinder.FIRST_CALL_TRANSACTION + 7;
    private static final int STOP_ALGORITHM = IBinder.FIRST_CALL_TRANSACTION + 8;
    private static final int ADD_SERVICE_LISTENER = IBinder.FIRST_CALL_TRANSACTION + 10;
    private static final int REMOVE_SERVICE_LISTENER = IBinder.FIRST_CALL_TRANSACTION + 11;
    private static final int GET_TRACKING_DATA_SHARED_MEMORY = IBinder.FIRST_CALL_TRANSACTION + 17;

    /** The combined eye and face tracking camera. */
    private static final int CAMERA = 5;

    /** Eye tracking (0x4) and face tracking (0x8), the algorithms this app runs. */
    private static final int EYE_AND_FACE = 0x4 | 0x8;
    /**
     * PICO Connect's face tracking combined with lip sync (GetSupportedTrackingMode 8204 = 0x2000 | 0x8 | 0x4). While it
     * runs, this app's start joins it ("ft combined algorithm has been started"). StandardAlgorithm::stopFaceAlgorithm
     * in libpxreyetrackingservice.so leaves the combined clients only for a stop with both 0x2000 and 0x8; a stop with
     * 0x8 alone leaves plain face tracking. When the last combined client leaves, or none is left, it stops the mode.
     */
    private static final int FACE_COMBINED = 0x2000 | 0x8;

    static final int MEMORY_EYE_TRACKING = 2;
    static final int MEMORY_FACE_TRACKING = 3;

    /** Users in this app (the tracker and the Test tab) that hold the running algorithms; see acquire. */
    private static final Object LEASE = new Object();
    private static int holders;
    /** Whether another app ran eye or face tracking when this app started it. */
    private static boolean otherAppAtStart;
    /** Counts resets; users map the ring buffers again when it changes, as the service may replace them. */
    private static volatile int resets;

    private final IBinder binder;

    private EyeTrackingService(IBinder binder) {
        this.binder = binder;
    }

    static EyeTrackingService connect() throws ServiceException {
        IBinder binder;
        try {
            Method getService = Class.forName("android.os.ServiceManager").getMethod("getService", String.class);
            binder = (IBinder) getService.invoke(null, SERVICE_NAME);
        } catch (ReflectiveOperationException e) {
            throw new ServiceException("Cannot look up the eye tracking service: " + e, e);
        }
        if (binder == null)
            throw new ServiceException("The eye tracking service is not available on this headset.");
        return new EyeTrackingService(binder);
    }

    /**
     * Starts eye and face tracking for one more user in this app. The first user starts the algorithms and the last
     * release stops them, so the Test tab and the tracker do not stop each other. Fails while the headset is not worn.
     *
     * @return the resets() value this lease belongs to; once resets() differs, a reset has ended the lease.
     */
    int acquire(int timeoutMs) throws ServiceException {
        synchronized (LEASE) {
            if (holders == 0) {
                otherAppAtStart = algorithmRunning();
                startAlgorithm(timeoutMs);
            }
            holders++;
            return resets;
        }
    }

    /**
     * Ends the acquire that returned lease; returns true when that was the last user and the camera is released. A
     * lease a reset already ended is not counted any more, so releasing it does nothing.
     */
    boolean release(int lease) throws ServiceException {
        synchronized (LEASE) {
            if (holders == 0 || lease != resets)
                return false;
            holders--;
            if (holders > 0)
                return false;
            stopAlgorithm();
            return true;
        }
    }

    /**
     * Whether another app, such as PICO Connect, runs eye or face tracking. While this app runs it, the service cannot
     * tell the apps apart, so the answer from when this app started it is used.
     */
    boolean otherAppRunning() throws ServiceException {
        synchronized (LEASE) {
            return holders > 0 ? otherAppAtStart : algorithmRunning();
        }
    }

    /**
     * Stops this app's eye and face tracking and ends every lease in the app; users notice the new resets() value and
     * acquire again without releasing. The service keeps one entry per calling process ("clients' pid" in dumpsys
     * pxreyetrackingservice), and a stop only removes the caller's: other apps' tracking (PICO Connect runs through
     * com.pico.xr.openxr_runtime) keeps running. Combined face tracking left running without any client stops.
     *
     * @return whether the service reports no eye or face tracking running afterwards, so no other app uses it.
     */
    boolean reset() throws ServiceException {
        synchronized (LEASE) {
            stopQuietly(EYE_AND_FACE);
            stopQuietly(FACE_COMBINED);
            holders = 0;
            otherAppAtStart = false;
            resets++;
            return !algorithmRunning();
        }
    }

    /**
     * Starts this app's tracking again after the service dropped it: when the headset sleeps, the service stops eye
     * tracking and forgets every client ("stopAlgorithm by sleep") and does not start it again on waking, while face
     * tracking may keep running, so the eyes stay grey. Ends every lease in the app like reset; a lease that already
     * ended does nothing.
     */
    void restart(int lease) {
        synchronized (LEASE) {
            if (lease != resets)
                return;
            stopQuietly(EYE_AND_FACE);
            stopQuietly(FACE_COMBINED);
            holders = 0;
            resets++;
        }
    }

    /** A stop of what is not running is answered with an error code, which is expected here. */
    private void stopQuietly(int algorithms) {
        try {
            stopAlgorithm(algorithms);
        } catch (ServiceException ignored) {
            // Nothing of this kind left to stop.
        }
    }

    /** Changes with every reset; see resets. */
    static int resets() {
        return resets;
    }

    private void startAlgorithm(int timeoutMs) throws ServiceException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(CAMERA);
            data.writeString(Integer.toString(EYE_AND_FACE));
            data.writeInt(timeoutMs);
            transact(START_ALGORITHM, data, reply);
            readStatus("StartAlgorithm", reply);
            checkResult("StartAlgorithm", reply.readInt());
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /** Leaves eye tracking and plain face tracking, then combined face tracking, whichever this app joined. */
    private void stopAlgorithm() throws ServiceException {
        try {
            stopAlgorithm(EYE_AND_FACE);
        } finally {
            stopQuietly(FACE_COMBINED);
        }
    }

    private void stopAlgorithm(int algorithms) throws ServiceException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(CAMERA);
            data.writeInt(algorithms);
            transact(STOP_ALGORITHM, data, reply);
            readStatus("StopAlgorithm", reply);
            checkResult("StopAlgorithm", reply.readInt());
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /**
     * Whether any client runs eye or face tracking. Face tracking combined with lip sync is left out: after the headset
     * slept it can keep running with no client at all (dumpsys: "0 clients connect to FT-combined").
     */
    private boolean algorithmRunning() throws ServiceException {
        return algorithmResult("et_running") != 0 || algorithmResult("ft_running") != 0;
    }

    /** Whether both eye tracking and face tracking (plain or combined with lip sync) run, as this app needs. */
    boolean trackingRunning() throws ServiceException {
        return algorithmResult("et_running") != 0
                && (algorithmResult("ft_running") != 0 || algorithmResult("ft_lipsync_running") != 0);
    }

    private int algorithmResult(String key) throws ServiceException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(CAMERA);
            data.writeString(key);
            transact(GET_ALGORITHM_RESULT, data, reply);
            readStatus("GetAlgorithmResult", reply);
            return reply.readInt();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /** Registers a listener binder, so the service holds a reference tied to this process. */
    void addServiceListener(IBinder listener) throws ServiceException {
        listenerTransaction(ADD_SERVICE_LISTENER, "AddServiceListener", listener);
    }

    void removeServiceListener(IBinder listener) throws ServiceException {
        listenerTransaction(REMOVE_SERVICE_LISTENER, "RemoveServiceListener", listener);
    }

    private void listenerTransaction(int code, String call, IBinder listener) throws ServiceException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeStrongBinder(listener);
            transact(code, data, reply);
            readStatus(call, reply);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /** Maps the ring buffer the service writes tracking results of the given type into. */
    SharedBuffer trackingDataMemory(int type) throws ServiceException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(type);
            transact(GET_TRACKING_DATA_SHARED_MEMORY, data, reply);
            readStatus("GetTrackingDataSharedMemory", reply);
            checkResult("GetTrackingDataSharedMemory", reply.readInt());
            if (reply.readInt() == 0)
                throw new ServiceException("GetTrackingDataSharedMemory returned no memory.");

            ParcelFileDescriptor memory = reply.readFileDescriptor();
            int size = reply.readInt();
            if (memory == null)
                throw new ServiceException("GetTrackingDataSharedMemory returned no file descriptor.");
            if (size <= 0) {
                closeQuietly(memory);
                throw new ServiceException("GetTrackingDataSharedMemory returned an empty memory region.");
            }
            return SharedBuffer.map(memory, size);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private void transact(int code, Parcel data, Parcel reply) throws ServiceException {
        try {
            if (!binder.transact(code, data, reply, 0))
                throw new ServiceException("The eye tracking service did not handle transaction " + code + ".");
        } catch (RemoteException e) {
            throw new ServiceException("The eye tracking service is unreachable: " + e, e);
        }
    }

    /** Native AIDL replies start with an exception code, followed by a message when the code is not 0. */
    private static void readStatus(String call, Parcel reply) throws ServiceException {
        int exception = reply.readInt();
        if (exception == 0)
            return;
        String message = reply.dataAvail() > 0 ? reply.readString() : null;
        throw new ServiceException(call + " was refused (exception " + exception + (message != null ? ": " + message : "") + ").");
    }

    private static void checkResult(String call, int result) throws ServiceException {
        if (result != 0)
            throw new ServiceException(call + " failed with code " + result + ".");
    }

    private static void closeQuietly(ParcelFileDescriptor descriptor) {
        try {
            descriptor.close();
        } catch (IOException ignored) {
            // Nothing left to release.
        }
    }

    static final class ServiceException extends Exception {
        ServiceException(String message) {
            super(message);
        }

        ServiceException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
