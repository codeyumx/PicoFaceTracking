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
     * Every algorithm the service offers (GetSupportedTrackingMode 8204): eye, face and 0x2000, which PICO Connect's
     * combined face and lip-sync mode appears to use.
     */
    private static final int ALL_ALGORITHMS = EYE_AND_FACE | 0x2000;
    private static final int RESET_ATTEMPTS = 8;

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
     * Stops everything on the tracking camera, including clients other apps left behind. PICO Connect can leave face
     * tracking in its combined face and lip-sync mode ("ft combined algorithm has been started"), whose frames report
     * the eyes as not seen; a stop from another app does not end it, and the next start joins it instead of starting
     * plain face tracking. This app's users lose their lease: they notice the new resets() value and acquire again
     * without releasing, which starts plain eye and face tracking.
     *
     * @return whether the service reports no eye or face tracking running afterwards.
     */
    boolean reset() throws ServiceException {
        synchronized (LEASE) {
            int attempts = 0;
            do {
                try {
                    stopAlgorithm(ALL_ALGORITHMS);
                } catch (ServiceException e) {
                    // Nothing left that this call can stop.
                    break;
                }
            } while (++attempts < RESET_ATTEMPTS && algorithmRunning());
            holders = 0;
            otherAppAtStart = false;
            resets++;
            return !algorithmRunning();
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

    private void stopAlgorithm() throws ServiceException {
        stopAlgorithm(EYE_AND_FACE);
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

    /** Whether any client runs eye or face tracking. The service counts clients per process. */
    private boolean algorithmRunning() throws ServiceException {
        return algorithmResult("et_running") != 0 || algorithmResult("ft_running") != 0;
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
