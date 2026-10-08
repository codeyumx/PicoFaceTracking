package io.github.codeyumx.picofacetracking;

import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Protocol version 2: a key shared with the PC authenticates the handshake and encrypts the session. See docs/protocol-v2.md. */
final class Pairing {
    static final int DISCOVER = 1;
    static final int HELLO = 2;
    static final int START = 3;
    static final int DATA = 4;
    static final int PING = 5;
    static final int PONG = 6;
    static final int STOP = 7;

    private static final byte[] MAGIC = {'P', 'F', 'T', '2'};
    static final int HEADER = 5;
    static final int NONCE = 16;
    private static final int MAC = 16;
    private static final int COUNTER = 8;
    private static final int TAG = 16;
    static final int DISCOVER_LENGTH = HEADER + NONCE + MAC;
    static final int HANDSHAKE_LENGTH = HEADER + 2 * NONCE + MAC;
    static final int KEY_LENGTH = 32;

    private static final byte[] FROM_HEADSET = {'H', 'M', 'D', 0};
    private static final byte[] FROM_PC = {'P', 'C', 0, 0};

    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] authKey;
    private final byte[] encryptionKey;

    private Pairing(byte[] key) {
        authKey = hmac(key, "PFT2 auth".getBytes(StandardCharsets.US_ASCII));
        encryptionKey = hmac(key, "PFT2 enc".getBytes(StandardCharsets.US_ASCII));
    }

    /** Returns null unless the text is a Base64 encoded 32-byte key. */
    static Pairing fromBase64(String text) {
        if (text == null || text.isEmpty())
            return null;
        try {
            byte[] key = Base64.decode(text.trim(), Base64.DEFAULT);
            return key.length == KEY_LENGTH ? new Pairing(key) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The message type of a version 2 message, or -1. */
    static int type(byte[] message, int length) {
        if (length < HEADER)
            return -1;
        for (int i = 0; i < MAGIC.length; i++) {
            if (message[i] != MAGIC[i])
                return -1;
        }
        return message[4] & 0xff;
    }

    /** The PC's nonce from a DISCOVER, or null when it is not a valid DISCOVER for this key. */
    byte[] verifyDiscover(byte[] message, int length) {
        if (length != DISCOVER_LENGTH || type(message, length) != DISCOVER || !macMatches(message, HEADER + NONCE))
            return null;
        return Arrays.copyOfRange(message, HEADER, HEADER + NONCE);
    }

    static byte[] newNonce() {
        byte[] nonce = new byte[NONCE];
        RANDOM.nextBytes(nonce);
        return nonce;
    }

    byte[] hello(byte[] pcNonce, byte[] headsetNonce) {
        return handshake(HELLO, pcNonce, headsetNonce);
    }

    /** Whether the message is the START for this handshake. */
    boolean verifyStart(byte[] message, int length, byte[] pcNonce, byte[] headsetNonce) {
        if (length != HANDSHAKE_LENGTH || type(message, length) != START || !macMatches(message, HEADER + 2 * NONCE))
            return false;
        return MessageDigest.isEqual(Arrays.copyOfRange(message, HEADER, HEADER + NONCE), pcNonce)
                && MessageDigest.isEqual(Arrays.copyOfRange(message, HEADER + NONCE, HEADER + 2 * NONCE), headsetNonce);
    }

    Session session(byte[] pcNonce, byte[] headsetNonce) {
        byte[] nonces = new byte[2 * NONCE];
        System.arraycopy(pcNonce, 0, nonces, 0, NONCE);
        System.arraycopy(headsetNonce, 0, nonces, NONCE, NONCE);
        return new Session(hmac(encryptionKey, nonces));
    }

    private byte[] handshake(int type, byte[] pcNonce, byte[] headsetNonce) {
        byte[] message = new byte[HANDSHAKE_LENGTH];
        header(message, type);
        System.arraycopy(pcNonce, 0, message, HEADER, NONCE);
        System.arraycopy(headsetNonce, 0, message, HEADER + NONCE, NONCE);
        byte[] mac = hmac(authKey, Arrays.copyOf(message, HEADER + 2 * NONCE));
        System.arraycopy(mac, 0, message, HEADER + 2 * NONCE, MAC);
        return message;
    }

    private boolean macMatches(byte[] message, int signedLength) {
        byte[] expected = Arrays.copyOf(hmac(authKey, Arrays.copyOf(message, signedLength)), MAC);
        return MessageDigest.isEqual(expected, Arrays.copyOfRange(message, signedLength, signedLength + MAC));
    }

    private static void header(byte[] message, int type) {
        System.arraycopy(MAGIC, 0, message, 0, MAGIC.length);
        message[4] = (byte) type;
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }

    /** One encrypted session: AES-256-GCM with a counter nonce per direction. */
    static final class Session {
        static final int OVERHEAD = HEADER + COUNTER + TAG;

        private final SecretKeySpec key;
        private final Cipher cipher;
        private final byte[] nonce = new byte[12];
        private long sent;
        private long received;

        private Session(byte[] key) {
            this.key = new SecretKeySpec(key, "AES");
            try {
                cipher = Cipher.getInstance("AES/GCM/NoPadding");
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("AES-GCM is not available", e);
            }
        }

        /** Encrypts a message from the headset into out; returns its length. */
        int seal(int type, byte[] plaintext, int plaintextLength, byte[] out) {
            sent++;
            header(out, type);
            putLong(out, HEADER, sent);
            setNonce(FROM_HEADSET, sent);
            try {
                cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG * 8, nonce));
                cipher.updateAAD(out, 0, HEADER + COUNTER);
                int written = plaintextLength == 0
                        ? cipher.doFinal(out, HEADER + COUNTER)
                        : cipher.doFinal(plaintext, 0, plaintextLength, out, HEADER + COUNTER);
                return HEADER + COUNTER + written;
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("Encryption failed", e);
            }
        }

        /** The type of a valid, new message from the PC with an empty body (PONG, STOP), or -1. */
        int openEmpty(byte[] message, int length) {
            int type = type(message, length);
            if (type < 0 || length != OVERHEAD)
                return -1;
            long counter = getLong(message, HEADER);
            if (counter <= received)
                return -1;
            setNonce(FROM_PC, counter);
            try {
                cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG * 8, nonce));
                cipher.updateAAD(message, 0, HEADER + COUNTER);
                cipher.doFinal(message, HEADER + COUNTER, TAG);
            } catch (GeneralSecurityException e) {
                return -1;
            }
            received = counter;
            return type;
        }

        private void setNonce(byte[] direction, long counter) {
            System.arraycopy(direction, 0, nonce, 0, 4);
            putLong(nonce, 4, counter);
        }
    }

    private static void putLong(byte[] buffer, int offset, long value) {
        for (int i = 0; i < 8; i++)
            buffer[offset + i] = (byte) (value >>> (8 * i));
    }

    private static long getLong(byte[] buffer, int offset) {
        long value = 0;
        for (int i = 7; i >= 0; i--)
            value = (value << 8) | (buffer[offset + i] & 0xff);
        return value;
    }
}
