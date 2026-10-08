package io.github.codeyumx.picofacetracking;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Pairing by code, headset side (docs/protocol-v2.md, "Pairing"). VRCFaceTracking's Output page shows a 6-digit code;
 * the user types it on the headset. SPAKE2 in the 2048-bit MODP group of RFC 3526 turns the code into a new random
 * pairing key on both sides. The code never crosses the network, and a device that does not know it gets a single
 * guess per pairing attempt.
 */
final class PairingExchange {
    static final int DISCOVER = 8;
    static final int OFFER = 9;
    static final int START = 10;
    static final int RESPONSE = 11;
    static final int CONFIRM = 12;
    static final int REJECT = 13;

    static final int NONCE = 16;
    static final int CODE_DIGITS = 6;
    static final int MAX_NAME = 64;
    private static final int HEADER = Pairing.HEADER;
    private static final int ELEMENT = 256;
    private static final int CONFIRMATION = 32;
    static final int DISCOVER_LENGTH = HEADER;
    static final int RESPONSE_LENGTH = HEADER + 2 * NONCE + ELEMENT + CONFIRMATION;
    static final int CONFIRM_LENGTH = HEADER + 2 * NONCE + CONFIRMATION;
    static final int REJECT_LENGTH = HEADER + 2 * NONCE;
    private static final int MIN_START_LENGTH = HEADER + 2 * NONCE + ELEMENT + 1;

    private static final byte[] MAGIC = {'P', 'F', 'T', '2'};
    private static final BigInteger P = new BigInteger(
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74"
            + "020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F1437"
            + "4FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED"
            + "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF05"
            + "98DA48361C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208552BB"
            + "9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B"
            + "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF695581718"
            + "3995497CEA956AE515D2261898FA051015728E5A8AACAA68FFFFFFFFFFFFFFFF", 16);
    private static final BigInteger Q = P.shiftRight(1);
    private static final BigInteger G = BigInteger.valueOf(2);
    private static final BigInteger M = hashToGroup("PFT2 pairing M");
    private static final BigInteger N = hashToGroup("PFT2 pairing N");

    private PairingExchange() {
    }

    /** A PC that offers to pair: its START message. */
    static final class Offer {
        final byte[] pcNonce;
        final byte[] element;
        final String pcName;

        private Offer(byte[] pcNonce, byte[] element, String pcName) {
            this.pcNonce = pcNonce;
            this.element = element;
            this.pcName = pcName;
        }
    }

    /** The answer to one PC's START with the code the user typed, and what that PC must confirm. */
    static final class Attempt {
        final byte[] pcNonce;
        final byte[] response;
        final byte[] key;
        private final byte[] expectedConfirmation;

        private Attempt(byte[] pcNonce, byte[] response, byte[] key, byte[] expectedConfirmation) {
            this.pcNonce = pcNonce;
            this.response = response;
            this.key = key;
            this.expectedConfirmation = expectedConfirmation;
        }
    }

    static byte[] newNonce(SecureRandom random) {
        byte[] nonce = new byte[NONCE];
        random.nextBytes(nonce);
        return nonce;
    }

    /** Only the six digits count; spaces and other characters typed with them are dropped. Null unless six digits. */
    static String normalizeCode(String typed) {
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < typed.length(); i++) {
            char c = typed.charAt(i);
            if (c >= '0' && c <= '9')
                digits.append(c);
        }
        return digits.length() == CODE_DIGITS ? digits.toString() : null;
    }

    static boolean isDiscover(byte[] message, int length) {
        return length == DISCOVER_LENGTH && Pairing.type(message, length) == DISCOVER;
    }

    static byte[] offer(byte[] headsetNonce, String headsetName) {
        byte[] name = name(headsetName);
        byte[] message = new byte[HEADER + NONCE + 1 + name.length];
        header(message, OFFER);
        System.arraycopy(headsetNonce, 0, message, HEADER, NONCE);
        message[HEADER + NONCE] = (byte) name.length;
        System.arraycopy(name, 0, message, HEADER + NONCE + 1, name.length);
        return message;
    }

    /** The PC's offer from a START for this pairing attempt, or null. */
    static Offer parseStart(byte[] message, int length, byte[] headsetNonce) {
        if (length < MIN_START_LENGTH || Pairing.type(message, length) != START)
            return null;
        int nameLength = message[HEADER + 2 * NONCE + ELEMENT] & 0xff;
        if (nameLength > MAX_NAME || length != MIN_START_LENGTH + nameLength)
            return null;
        if (!MessageDigest.isEqual(Arrays.copyOfRange(message, HEADER, HEADER + NONCE), headsetNonce))
            return null;
        byte[] element = Arrays.copyOfRange(message, HEADER + 2 * NONCE, HEADER + 2 * NONCE + ELEMENT);
        if (!isGroupElement(new BigInteger(1, element)))
            return null;
        return new Offer(Arrays.copyOfRange(message, HEADER + NONCE, HEADER + 2 * NONCE), element,
                new String(message, MIN_START_LENGTH, nameLength, StandardCharsets.UTF_8));
    }

    static Attempt respond(Offer offer, byte[] headsetNonce, String code, SecureRandom random) {
        BigInteger y;
        do {
            byte[] secret = new byte[32];
            random.nextBytes(secret);
            y = new BigInteger(1, secret).mod(Q);
        } while (y.signum() == 0);
        return respond(offer, headsetNonce, code, y);
    }

    /** With a given secret exponent; for the test vectors. */
    static Attempt respond(Offer offer, byte[] headsetNonce, String code, BigInteger y) {
        byte[] codeHash = codeHash(code);
        BigInteger w = new BigInteger(1, codeHash).mod(Q);
        byte[] s = element(G.modPow(y, P).multiply(N.modPow(w, P)).mod(P));
        BigInteger t = new BigInteger(1, offer.element);
        byte[] shared = element(t.multiply(M.modPow(Q.subtract(w).mod(Q), P)).mod(P).modPow(y, P));

        byte[] transcript = sha256(ascii("PFT2 pairing"), headsetNonce, offer.pcNonce, offer.element, s, shared, codeHash);
        byte[] nonces = concat(headsetNonce, offer.pcNonce);
        byte[] headsetConfirmation = hmac(hmac(transcript, ascii("PFT2 confirm HMD")), nonces);
        byte[] pcConfirmation = hmac(hmac(transcript, ascii("PFT2 confirm PC")), nonces);
        byte[] key = hmac(transcript, ascii("PFT2 pairing key"));

        byte[] response = new byte[RESPONSE_LENGTH];
        header(response, RESPONSE);
        System.arraycopy(nonces, 0, response, HEADER, 2 * NONCE);
        System.arraycopy(s, 0, response, HEADER + 2 * NONCE, ELEMENT);
        System.arraycopy(headsetConfirmation, 0, response, HEADER + 2 * NONCE + ELEMENT, CONFIRMATION);
        return new Attempt(offer.pcNonce, response, key, pcConfirmation);
    }

    /** Whether the message is the PC's CONFIRM for this attempt: then the PC knew the code and holds the same key. */
    static boolean isConfirm(byte[] message, int length, byte[] headsetNonce, Attempt attempt) {
        return length == CONFIRM_LENGTH && Pairing.type(message, length) == CONFIRM && matchesNonces(message, headsetNonce, attempt)
                && MessageDigest.isEqual(Arrays.copyOfRange(message, HEADER + 2 * NONCE, CONFIRM_LENGTH), attempt.expectedConfirmation);
    }

    /** Whether the PC says the code was wrong. Not authenticated: it only ends this attempt. */
    static boolean isReject(byte[] message, int length, byte[] headsetNonce, Attempt attempt) {
        return length == REJECT_LENGTH && Pairing.type(message, length) == REJECT && matchesNonces(message, headsetNonce, attempt);
    }

    private static boolean matchesNonces(byte[] message, byte[] headsetNonce, Attempt attempt) {
        return MessageDigest.isEqual(Arrays.copyOfRange(message, HEADER, HEADER + NONCE), headsetNonce)
                && MessageDigest.isEqual(Arrays.copyOfRange(message, HEADER + NONCE, HEADER + 2 * NONCE), attempt.pcNonce);
    }

    private static boolean isGroupElement(BigInteger value) {
        return value.compareTo(BigInteger.ONE) > 0 && value.compareTo(P.subtract(BigInteger.ONE)) < 0
                && value.modPow(Q, P).equals(BigInteger.ONE);
    }

    private static byte[] codeHash(String code) {
        return sha256(ascii("PFT2 pairing code "), ascii(code));
    }

    /** Hashes a label to an element of the order-Q subgroup whose discrete logarithm nobody knows. */
    private static BigInteger hashToGroup(String label) {
        byte[] wide = new byte[9 * 32];
        for (int i = 0; i < 9; i++)
            System.arraycopy(sha256(ascii(label), new byte[]{(byte) i}), 0, wide, 32 * i, 32);
        BigInteger value = new BigInteger(1, wide).mod(P);
        return value.multiply(value).mod(P);
    }

    /** A group element as 256 big-endian bytes. */
    private static byte[] element(BigInteger value) {
        byte[] bytes = value.toByteArray();
        byte[] fixed = new byte[ELEMENT];
        int copy = Math.min(bytes.length, ELEMENT);
        System.arraycopy(bytes, bytes.length - copy, fixed, ELEMENT - copy, copy);
        return fixed;
    }

    private static byte[] name(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        int length = Math.min(bytes.length, MAX_NAME);
        // Do not cut a UTF-8 sequence in half.
        while (length > 0 && length < bytes.length && (bytes[length] & 0xc0) == 0x80)
            length--;
        return Arrays.copyOf(bytes, length);
    }

    private static void header(byte[] message, int type) {
        System.arraycopy(MAGIC, 0, message, 0, MAGIC.length);
        message[4] = (byte) type;
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts)
            length += part.length;
        byte[] out = new byte[length];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, offset, part.length);
            offset += part.length;
        }
        return out;
    }

    private static byte[] sha256(byte[]... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (byte[] part : parts)
                digest.update(part);
            return digest.digest();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
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
}
