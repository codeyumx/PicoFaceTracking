package io.github.codeyumx.picofacetracking;

import android.os.ParcelFileDescriptor;

import java.nio.ByteBuffer;

/** A read-only mapping of one of the tracking service's ring buffers. */
final class SharedBuffer implements AutoCloseable {
    static {
        System.loadLibrary("sharedbuffer");
    }

    private long handle;

    private SharedBuffer(long handle) {
        this.handle = handle;
    }

    /** Takes ownership of the file descriptor. */
    static SharedBuffer map(ParcelFileDescriptor memory, int size) throws EyeTrackingService.ServiceException {
        long handle = nativeMap(memory.detachFd(), size);
        if (handle == 0)
            throw new EyeTrackingService.ServiceException("Cannot map " + size + " bytes of tracking memory.");
        return new SharedBuffer(handle);
    }

    /**
     * Copies the start of the newest slot into a direct buffer.
     *
     * @return true when a slot that was not read before was copied.
     * @throws IllegalStateException when the ring buffer does not have the expected layout.
     */
    boolean readLatest(ByteBuffer destination, int offset, int length) {
        int result = nativeReadLatest(handle, destination, offset, length);
        if (result < 0)
            throw new IllegalStateException("The tracking data does not have the expected layout.");
        return result == 1;
    }

    @Override
    public void close() {
        if (handle != 0) {
            nativeUnmap(handle);
            handle = 0;
        }
    }

    private static native long nativeMap(int fd, int size);

    private static native int nativeReadLatest(long handle, ByteBuffer destination, int offset, int length);

    private static native void nativeUnmap(long handle);
}
