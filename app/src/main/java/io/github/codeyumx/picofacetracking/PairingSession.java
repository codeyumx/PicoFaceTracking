package io.github.codeyumx.picofacetracking;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One pairing by code with VRCFaceTracking (docs/protocol-v2.md, "Pairing"), started from the settings screen.
 * Tracking stops while it runs, because both use UDP port 9030, and starts again afterwards if it is switched on.
 */
final class PairingSession implements Runnable {
    private static final String TAG = Tracker.TAG;
    private static final int PORT = 9030;
    private static final byte[] MULTICAST_GROUP = {(byte) 239, (byte) 255, (byte) 255, (byte) 250};
    private static final long DURATION_MS = 5 * 60_000;
    private static final long RESEND_MS = 1000;
    private static final long ANSWER_TIMEOUT_MS = 6000;
    private static final long TRACKING_STOP_WAIT_MS = 5000;

    private static PairingSession current;

    private final Context context;
    private final SecureRandom random = new SecureRandom();
    /** PCs that sent a START for the current attempt, by address, in the order they appeared. */
    private final Map<InetSocketAddress, PairingExchange.Offer> pcs = new LinkedHashMap<>();
    private volatile boolean running = true;
    private volatile String status = "Starting...";
    private volatile boolean paired;
    /** Set by the screen, taken by the pairing thread. */
    private InetSocketAddress submittedPc;
    private String submittedCode;

    private PairingSession(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Starts pairing, or returns the pairing that is already running. */
    static synchronized PairingSession start(Context context) {
        if (current == null || !current.running) {
            current = new PairingSession(context);
            new Thread(current, "pairing").start();
        }
        return current;
    }

    /** The running or last pairing, or null. */
    static synchronized PairingSession current() {
        return current;
    }

    /** Whether pairing runs now; tracking waits for it. */
    static synchronized boolean active() {
        return current != null && current.running;
    }

    boolean running() {
        return running;
    }

    boolean paired() {
        return paired;
    }

    String status() {
        return status;
    }

    void cancel() {
        running = false;
    }

    /** "NAME (address)" for each PC offering to pair, with the addresses in the same order. */
    synchronized List<InetSocketAddress> pcs(List<String> labels) {
        labels.clear();
        for (Map.Entry<InetSocketAddress, PairingExchange.Offer> pc : pcs.entrySet())
            labels.add(pc.getValue().pcName + " (" + pc.getKey().getAddress().getHostAddress() + ")");
        return new ArrayList<>(pcs.keySet());
    }

    /** Pairs with the PC using the code the user typed; returns an error to show, or null. */
    synchronized String submit(InetSocketAddress pc, String typedCode) {
        String code = PairingExchange.normalizeCode(typedCode);
        if (code == null)
            return "Type the 6 digits shown in VRCFaceTracking.";
        if (pc == null || !pcs.containsKey(pc))
            return "Choose the PC first.";
        submittedPc = pc;
        submittedCode = code;
        return null;
    }

    @Override
    public void run() {
        Prefs prefs = new Prefs(context);
        WifiManager.MulticastLock multicastLock = context.getSystemService(WifiManager.class).createMulticastLock(TAG + " pairing");
        multicastLock.setReferenceCounted(false);
        multicastLock.acquire();
        try {
            TrackingService.stop(context);
            long waitUntil = SystemClock.elapsedRealtime() + TRACKING_STOP_WAIT_MS;
            while (TrackingService.running() && SystemClock.elapsedRealtime() < waitUntil)
                SystemClock.sleep(100);
            pair(prefs);
        } catch (IOException e) {
            Log.w(TAG, "Pairing failed", e);
            status = "Pairing stopped: " + e.getMessage();
        } finally {
            multicastLock.release();
            running = false;
            if (prefs.enabled())
                TrackingService.start(context);
        }
    }

    private void pair(Prefs prefs) throws IOException {
        try (MulticastSocket socket = new MulticastSocket(null)) {
            socket.setReuseAddress(true);
            try {
                socket.bind(new InetSocketAddress(PORT));
            } catch (IOException e) {
                throw new IOException("UDP port " + PORT + " is in use. Restart the headset and try again.", e);
            }
            try {
                socket.joinGroup(InetAddress.getByAddress(MULTICAST_GROUP));
            } catch (IOException e) {
                Log.d(TAG, "Cannot join the discovery group, only unicast discovery works: " + e.getMessage());
            }
            socket.setSoTimeout(200);

            String headsetName = headsetName();
            byte[] headsetNonce = PairingExchange.newNonce(random);
            byte[] buffer = new byte[1024];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            long end = SystemClock.elapsedRealtime() + DURATION_MS;
            PairingExchange.Attempt attempt = null;
            InetSocketAddress attemptPc = null;
            long attemptStarted = 0;
            long lastSent = 0;
            status = waitingStatus();

            while (running) {
                long now = SystemClock.elapsedRealtime();
                if (now >= end) {
                    status = "Pairing ended: no code was entered within 5 minutes.";
                    return;
                }

                InetSocketAddress pc;
                String code;
                synchronized (this) {
                    pc = submittedPc;
                    code = submittedCode;
                    submittedPc = null;
                    submittedCode = null;
                }
                if (pc != null) {
                    PairingExchange.Offer offer;
                    synchronized (this) {
                        offer = pcs.get(pc);
                    }
                    if (offer != null) {
                        status = "Checking the code with " + offer.pcName + "...";
                        attempt = PairingExchange.respond(offer, headsetNonce, code, random);
                        attemptPc = pc;
                        attemptStarted = now;
                        lastSent = 0;
                    }
                }

                if (attempt != null) {
                    if (now - attemptStarted > ANSWER_TIMEOUT_MS) {
                        status = "The PC did not answer. Check that VRCFaceTracking still runs, then type the code again.";
                        attempt = null;
                    } else if (now - lastSent >= RESEND_MS) {
                        socket.send(new DatagramPacket(attempt.response, attempt.response.length, attemptPc));
                        lastSent = now;
                    }
                }

                try {
                    socket.receive(packet);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                InetAddress sender = packet.getAddress();
                if (!(sender instanceof Inet4Address) || !(sender.isSiteLocalAddress() || sender.isLinkLocalAddress()))
                    continue;
                InetSocketAddress from = new InetSocketAddress(sender, packet.getPort());
                int length = packet.getLength();

                if (PairingExchange.isDiscover(buffer, length)) {
                    byte[] offer = PairingExchange.offer(headsetNonce, headsetName);
                    socket.send(new DatagramPacket(offer, offer.length, from));
                    continue;
                }

                PairingExchange.Offer offer = PairingExchange.parseStart(buffer, length, headsetNonce);
                if (offer != null) {
                    boolean added;
                    synchronized (this) {
                        added = pcs.put(from, offer) == null;
                    }
                    if (added) {
                        Log.i(TAG, "Pairing offered by " + offer.pcName + " at " + sender.getHostAddress() + ".");
                        if (attempt == null)
                            status = waitingStatus();
                    }
                    continue;
                }

                if (attempt == null || !from.equals(attemptPc))
                    continue;
                if (PairingExchange.isConfirm(buffer, length, headsetNonce, attempt)) {
                    String pcName;
                    synchronized (this) {
                        pcName = pcs.get(attemptPc).pcName;
                    }
                    prefs.setPairingKey(Base64.encodeToString(attempt.key, Base64.NO_WRAP));
                    paired = true;
                    status = "Paired with " + pcName + ". Tracking data is now encrypted, and the PC is found again when its address changes.";
                    Log.i(TAG, "Paired by code with " + pcName + " at " + sender.getHostAddress() + ".");
                    return;
                }
                if (PairingExchange.isReject(buffer, length, headsetNonce, attempt)) {
                    // A new attempt: the module shows a new code for it.
                    headsetNonce = PairingExchange.newNonce(random);
                    synchronized (this) {
                        pcs.clear();
                    }
                    attempt = null;
                    status = "Wrong code. VRCFaceTracking shows a new code in a few seconds; type that one.";
                }
            }
            status = "Pairing cancelled.";
        }
    }

    private String waitingStatus() {
        synchronized (this) {
            if (!pcs.isEmpty())
                return "Type the 6-digit pairing code from VRCFaceTracking's Output page.";
        }
        return "Looking for VRCFaceTracking on your network. On the PC, run VRCFaceTracking with "
                + "\"Pico Facial Data Module (paired)\"; its Output page then shows a 6-digit pairing code.";
    }

    private String headsetName() {
        String name = Settings.Global.getString(context.getContentResolver(), Settings.Global.DEVICE_NAME);
        return name != null && !name.isEmpty() ? name : Build.MODEL;
    }
}
