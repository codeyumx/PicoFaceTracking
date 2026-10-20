package io.github.codeyumx.picofacetracking;

import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Parcel;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.PortUnreachableException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.DatagramChannel;
import java.nio.charset.StandardCharsets;

/**
 * Speaks the PicoFacialDataDaemon protocol to VRCFaceTracking's Pico Facial Data Module: wait for a discovery request
 * from the PC, start tracking, stream the newest results, ping (the PC answers), and stop when the PC sends STOP or
 * stops answering. Without a pairing key this is version 1 (plain, the PC is identified by its address); with a key it
 * is version 2 (authenticated handshake, encrypted session, any address), see module/docs/protocol-v2.md.
 */
final class Tracker implements Runnable {
    interface Listener {
        void onStatus(Text status);

        /** The tracker stopped for good. */
        void onFailure(Text reason);

        /** No PC was set, and this PC sent the first discovery request; only it is answered from now on. */
        void onPaired(Inet4Address pc);
    }

    static final String TAG = "PicoFaceTracking";

    private static final int PORT = 9030;
    private static final byte[] MULTICAST_GROUP = {(byte) 239, (byte) 255, (byte) 255, (byte) 250};
    private static final String DISCOVER = "DISCOVER_DAEMON";
    /** The module only recognises the ping by its 6 byte length, so the NUL is part of it. */
    private static final byte[] PING = {'M', 'A', 'R', 'C', 'O', 0};
    private static final String PONG = "POLO";
    private static final String STOP = "STOP";
    private static final int START_TIMEOUT_MS = 1000;
    private static final long START_RETRY_MS = 1000;
    private static final long POLL_MS = 10;
    private static final long PING_WHILE_STREAMING_MS = 25_000;
    /**
     * The longest the PC goes without a packet (frame or ping): the module drops the connection after 2 seconds of
     * silence, so a ping follows 1 second after the last frame and every second while no frames flow.
     */
    private static final long PING_WHILE_IDLE_MS = 1000;
    private static final long CLIENT_TIMEOUT_MS = 25_000;
    private static final long STREAMING_GRACE_MS = 2000;
    private static final long HANDSHAKE_TIMEOUT_MS = 2000;
    private static final int DISCOVERY_RECEIVE_TIMEOUT_MS = 1000;
    private static final long MULTICAST_REJOIN_MS = 10_000;

    /** Null until paired: then the first PC on the local network that asks is accepted and remembered. */
    private Inet4Address allowedClient;
    /** Set when paired by key: then only protocol version 2 is spoken and allowedClient is not used. */
    private final Pairing pairing;
    private final boolean pauseForOtherApps;
    /** The paid tracking adjustments when unlocked, otherwise null. */
    private final PaidFeatures paid;
    private final WifiManager wifi;
    private final Listener listener;
    private volatile boolean running = true;

    Tracker(Inet4Address allowedClient, Pairing pairing, boolean pauseForOtherApps, PaidFeatures paid, WifiManager wifi, Listener listener) {
        this.allowedClient = allowedClient;
        this.pairing = pairing;
        this.pauseForOtherApps = pauseForOtherApps;
        this.paid = paid;
        this.wifi = wifi;
        this.listener = listener;
    }

    void stop() {
        running = false;
    }

    @Override
    public void run() {
        WifiManager.MulticastLock multicastLock = wifi.createMulticastLock(TAG);
        multicastLock.setReferenceCounted(false);
        multicastLock.acquire();
        // The service keeps a reference to this binder for as long as this process lives.
        Binder serviceListener = new Binder() {
            @Override
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                return true;
            }
        };
        EyeTrackingService service = null;
        try {
            service = EyeTrackingService.connect();
            service.addServiceListener(serviceListener);
            while (running) {
                Request request = discover();
                if (request == null)
                    break;
                stream(service, request);
            }
        } catch (EyeTrackingService.ServiceException | IOException | RuntimeException e) {
            Log.e(TAG, "Tracking stopped", e);
            listener.onFailure(Text.literal(e.getMessage() != null ? e.getMessage() : e.toString()));
        } finally {
            if (service != null) {
                try {
                    service.removeServiceListener(serviceListener);
                } catch (EyeTrackingService.ServiceException e) {
                    Log.w(TAG, "Cannot remove the service listener: " + e.getMessage());
                }
            }
            multicastLock.release();
        }
    }

    /** A discovery request: the PC's address and, in version 2, its handshake nonce. */
    private static final class Request {
        final InetSocketAddress address;
        final byte[] pcNonce;

        Request(InetSocketAddress address, byte[] pcNonce) {
            this.address = address;
            this.pcNonce = pcNonce;
        }
    }

    /**
     * Waits for a discovery request: with a key, a valid version 2 DISCOVER from any local PC; without one, from the
     * allowed client (or, when unpaired, the first local PC). Returns null once stopped, or after reporting a failure.
     */
    private Request discover() throws IOException {
        listener.onStatus(pairing != null
                ? new Text(R.string.status_waiting_key)
                : allowedClient != null
                ? new Text(R.string.status_waiting_address, allowedClient.getHostAddress())
                : new Text(R.string.status_waiting_any));
        InetAddress group = InetAddress.getByAddress(MULTICAST_GROUP);

        try (MulticastSocket socket = new MulticastSocket(null)) {
            socket.setReuseAddress(true);
            try {
                socket.bind(new InetSocketAddress(PORT));
            } catch (IOException e) {
                Log.e(TAG, "UDP port " + PORT + " is in use", e);
                listener.onFailure(new Text(R.string.error_port_in_use_tracking, PORT));
                return null;
            }
            socket.setSoTimeout(DISCOVERY_RECEIVE_TIMEOUT_MS);

            byte[] buffer = new byte[64];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            long nextJoin = 0;

            while (running) {
                long now = SystemClock.elapsedRealtime();
                // Re-joining keeps the membership alive across Wi-Fi reconnects. Direct (unicast) requests work regardless.
                if (now >= nextJoin) {
                    nextJoin = now + MULTICAST_REJOIN_MS;
                    try {
                        socket.leaveGroup(group);
                    } catch (IOException ignored) {
                        // Not a member yet.
                    }
                    try {
                        socket.joinGroup(group);
                    } catch (IOException e) {
                        Log.d(TAG, "Cannot join the discovery group yet: " + e.getMessage());
                    }
                }

                packet.setLength(buffer.length);
                try {
                    socket.receive(packet);
                } catch (SocketTimeoutException e) {
                    continue;
                }

                InetAddress sender = packet.getAddress();
                // Never answer an internet address, and pair only within the local network.
                if (!(sender instanceof Inet4Address) || !(sender.isSiteLocalAddress() || sender.isLinkLocalAddress())) {
                    Log.i(TAG, "Ignored a packet from " + sender.getHostAddress() + ", it is not on the local network.");
                    continue;
                }

                // The paired module asks every 2 seconds whether a headset wants to pair; only the pairing screen answers.
                if (Pairing.type(buffer, packet.getLength()) == PairingExchange.DISCOVER)
                    continue;

                if (pairing != null) {
                    // Paired by key: only version 2 is answered, so nobody can fall back to the unauthenticated protocol.
                    byte[] pcNonce = pairing.verifyDiscover(buffer, packet.getLength());
                    if (pcNonce == null) {
                        Log.i(TAG, "Ignored a packet from " + sender.getHostAddress() + ", it is not a discovery request signed with the pairing key.");
                        continue;
                    }
                    Log.i(TAG, "Discovered the paired PC at " + sender.getHostAddress() + ".");
                    return new Request(new InetSocketAddress(sender, packet.getPort()), pcNonce);
                }

                if (!isDiscoverRequest(buffer, packet.getLength())) {
                    Log.i(TAG, "Ignored a packet from " + sender.getHostAddress() + ", it is not a discovery request.");
                    continue;
                }
                if (allowedClient == null) {
                    allowedClient = (Inet4Address) sender;
                    Log.i(TAG, "Paired with " + sender.getHostAddress() + ".");
                    listener.onPaired(allowedClient);
                } else if (!allowedClient.equals(sender)) {
                    Log.i(TAG, "Ignored a discovery request from " + sender.getHostAddress() + ", it is not the paired PC.");
                    continue;
                }

                Log.i(TAG, "Discovered client " + sender.getHostAddress() + ".");
                return new Request(new InetSocketAddress(sender, packet.getPort()), null);
            }
            return null;
        }
    }

    /** Streams tracking data to the client until it stops answering, sends STOP, or the tracker is stopped. */
    private void stream(EyeTrackingService service, Request request) throws IOException {
        InetSocketAddress client = request.address;
        String pc = client.getAddress().getHostAddress();
        ByteBuffer payload = ByteBuffer.allocateDirect(TrackingFrame.LENGTH).order(ByteOrder.LITTLE_ENDIAN);
        PaidFeatures.FrameFilter adjustments = paid != null ? paid.newSession() : null;
        ByteBuffer incoming = ByteBuffer.allocate(64);
        ByteBuffer ping = ByteBuffer.wrap(PING);
        // Version 2: the frame is copied out of the direct buffer and encrypted into sealed.
        byte[] frame = new byte[TrackingFrame.LENGTH];
        byte[] sealed = new byte[TrackingFrame.LENGTH + Pairing.Session.OVERHEAD];

        SharedBuffer face = null;
        SharedBuffer eye = null;
        boolean started = false;
        long lastStartAttempt = Long.MIN_VALUE / 2;
        long lastSent = Long.MIN_VALUE / 2;
        // The last frame or ping that went out.
        long lastPacket = Long.MIN_VALUE / 2;
        long nextPing = 0;
        long unansweredSince = -1;
        String lastStartError = null;

        try (DatagramChannel channel = DatagramChannel.open()) {
            channel.connect(client);
            channel.configureBlocking(false);

            Pairing.Session session = null;
            if (request.pcNonce != null) {
                session = handshake(channel, request.pcNonce);
                if (session == null) {
                    Log.i(TAG, pc + " did not complete the handshake.");
                    return;
                }
            }
            listener.onStatus(new Text(session != null ? R.string.status_connected_encrypted : R.string.status_connected, pc));

            while (running) {
                long now = SystemClock.elapsedRealtime();

                // Answers from the PC: PONG/POLO keeps the session alive, STOP ends it.
                while (true) {
                    incoming.clear();
                    try {
                        if (channel.receive(incoming) == null)
                            break;
                    } catch (PortUnreachableException e) {
                        break;
                    }
                    incoming.flip();
                    boolean pong;
                    boolean stop;
                    if (session != null) {
                        int type = session.openEmpty(incoming.array(), incoming.limit());
                        pong = type == Pairing.PONG;
                        stop = type == Pairing.STOP;
                    } else {
                        String message = ascii(incoming);
                        pong = message.equals(PONG);
                        stop = message.equals(STOP);
                    }
                    if (pong) {
                        unansweredSince = -1;
                    } else if (stop) {
                        Log.i(TAG, pc + " sent STOP.");
                        return;
                    }
                }

                // The service refuses to start while the headset is not worn, so keep trying.
                if (!started && now - lastStartAttempt >= START_RETRY_MS) {
                    lastStartAttempt = now;
                    try {
                        // PICO Connect (or another app) already feeds face tracking to the PC; do not send it twice.
                        // Pings continue meanwhile, so the session stays open and tracking starts once the other app stops.
                        if (pauseForOtherApps && service.otherAppRunning()) {
                            listener.onStatus(new Text(R.string.status_paused, pc));
                        } else {
                            service.acquire(START_TIMEOUT_MS);
                            started = true;
                            face = service.trackingDataMemory(EyeTrackingService.MEMORY_FACE_TRACKING);
                            eye = service.trackingDataMemory(EyeTrackingService.MEMORY_EYE_TRACKING);
                            Log.i(TAG, "Tracking started for " + pc + ".");
                            lastStartError = null;
                        }
                    } catch (EyeTrackingService.ServiceException e) {
                        if (!e.getMessage().equals(lastStartError))
                            Log.w(TAG, "Cannot start tracking yet: " + e.getMessage());
                        lastStartError = e.getMessage();
                        listener.onStatus(new Text(R.string.status_waiting_headset, pc, e.getMessage()));
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
                    if (newFace && newEye) {
                        if (adjustments != null)
                            adjustments.apply(payload);
                        FaceParts.current().apply(payload);
                        payload.clear();
                        int written;
                        try {
                            if (session != null) {
                                payload.get(frame);
                                written = channel.write(ByteBuffer.wrap(sealed, 0, session.seal(Pairing.DATA, frame, frame.length, sealed)));
                            } else {
                                written = channel.write(payload);
                            }
                        } catch (PortUnreachableException e) {
                            Log.i(TAG, pc + " is no longer listening.");
                            return;
                        }
                        // A full send buffer drops the frame (write returns 0); the next frame follows 40 ms later.
                        if (written > 0) {
                            lastSent = now;
                            lastPacket = now;
                        }
                    }
                    listener.onStatus(now - lastSent < STREAMING_GRACE_MS
                            ? new Text(R.string.status_sending, pc)
                            : new Text(R.string.status_no_data, pc));
                }

                if (now >= nextPing || now - lastPacket >= PING_WHILE_IDLE_MS) {
                    ping.clear();
                    int written;
                    try {
                        written = session != null
                                ? channel.write(ByteBuffer.wrap(sealed, 0, session.seal(Pairing.PING, null, 0, sealed)))
                                : channel.write(ping);
                    } catch (PortUnreachableException e) {
                        Log.i(TAG, pc + " is no longer listening.");
                        return;
                    }
                    // When the send buffer is full the ping is retried on the next poll.
                    if (written > 0) {
                        lastPacket = now;
                        if (unansweredSince < 0)
                            unansweredSince = now;
                        nextPing = now + (now - lastSent < STREAMING_GRACE_MS ? PING_WHILE_STREAMING_MS : PING_WHILE_IDLE_MS);
                    }
                }

                if (unansweredSince >= 0 && now - unansweredSince > CLIENT_TIMEOUT_MS) {
                    Log.i(TAG, pc + " stopped answering.");
                    return;
                }

                SystemClock.sleep(POLL_MS);
            }
        } finally {
            closeQuietly(face);
            closeQuietly(eye);
            if (started)
                release(service);
        }
    }

    /**
     * Version 2: sends HELLO from the streaming socket, so the PC learns where the session comes from, and waits for
     * the matching START. Returns the session, or null when no valid START arrives in time.
     */
    private Pairing.Session handshake(DatagramChannel channel, byte[] pcNonce) throws IOException {
        byte[] headsetNonce = Pairing.newNonce();
        try {
            channel.write(ByteBuffer.wrap(pairing.hello(pcNonce, headsetNonce)));
        } catch (PortUnreachableException e) {
            return null;
        }
        ByteBuffer incoming = ByteBuffer.allocate(Pairing.HANDSHAKE_LENGTH + 1);
        long deadline = SystemClock.elapsedRealtime() + HANDSHAKE_TIMEOUT_MS;
        while (running && SystemClock.elapsedRealtime() < deadline) {
            incoming.clear();
            try {
                if (channel.receive(incoming) != null
                        && pairing.verifyStart(incoming.array(), incoming.position(), pcNonce, headsetNonce))
                    return pairing.session(pcNonce, headsetNonce);
            } catch (PortUnreachableException e) {
                return null;
            }
            SystemClock.sleep(POLL_MS);
        }
        return null;
    }

    private static void release(EyeTrackingService service) {
        try {
            if (service.release())
                Log.i(TAG, "Tracking stopped, the camera is released.");
        } catch (EyeTrackingService.ServiceException e) {
            Log.w(TAG, "Cannot stop tracking: " + e.getMessage());
        }
    }

    private static SharedBuffer closeQuietly(SharedBuffer buffer) {
        if (buffer != null)
            buffer.close();
        return null;
    }

    /** Matches DISCOVER_DAEMON, with or without a trailing NUL byte. */
    private static boolean isDiscoverRequest(byte[] buffer, int length) {
        if (length > 0 && buffer[length - 1] == 0)
            length--;
        return DISCOVER.equals(new String(buffer, 0, length, StandardCharsets.US_ASCII));
    }

    private static String ascii(ByteBuffer buffer) {
        int length = buffer.remaining();
        if (length > 0 && buffer.get(buffer.limit() - 1) == 0)
            length--;
        byte[] bytes = new byte[length];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.US_ASCII);
    }
}
