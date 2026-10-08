package io.github.codeyumx.picofacetracking;

import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * A supporter licence: a small text file, signed with the author's private key (ECDSA P-256 with SHA-256), that unlocks
 * the paid tracking adjustments. Format:
 * <pre>
 * Pico Face Tracking licence
 * licensee: Patreon supporters
 * expires: 2026-11-30
 * signature: (Base64 DER signature)
 * </pre>
 * The signature covers every line above it, each ending in a line feed. "expires" is optional and is the last valid day.
 */
final class Licence {
    private static final String HEADER = "Pico Face Tracking licence";
    private static final String LICENSEE = "licensee: ";
    private static final String EXPIRES = "expires: ";
    private static final String SIGNATURE = "signature: ";
    /** Public half of the licence signing key, X.509 SubjectPublicKeyInfo. */
    private static final String PUBLIC_KEY = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEq6kWwgSaRDHi52dA7jMtIgT39o7vqgGYPEKdLuzaWguQy0UrvrTwCZ9jew1gQnF/Z9uGVKt78DgLy4GQk67KxQ==";

    final String licensee;
    /** Last valid day, or null when the licence does not end. */
    final LocalDate expires;

    private Licence(String licensee, LocalDate expires) {
        this.licensee = licensee;
        this.expires = expires;
    }

    boolean expired() {
        return expires != null && LocalDate.now().isAfter(expires);
    }

    String describe() {
        return "licensed to " + licensee + (expires != null ? ", until " + expires : "");
    }

    /** Parses a licence file and checks its signature. Throws IllegalArgumentException with the reason. */
    static Licence parse(String text) {
        String normalized = text.replace("\r\n", "\n");
        int signatureLine = normalized.indexOf("\n" + SIGNATURE);
        if (!normalized.startsWith(HEADER + "\n") || signatureLine < 0)
            throw new IllegalArgumentException("not a Pico Face Tracking licence file");

        String signed = normalized.substring(0, signatureLine + 1);
        byte[] signature;
        try {
            signature = Base64.decode(normalized.substring(signatureLine + 1 + SIGNATURE.length()).trim(), Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("the signature is damaged");
        }
        if (!verify(signed.getBytes(StandardCharsets.UTF_8), signature))
            throw new IllegalArgumentException("the signature does not match: the file was changed or is not a real licence");

        String licensee = null;
        LocalDate expires = null;
        for (String line : signed.split("\n")) {
            if (line.startsWith(LICENSEE)) {
                licensee = line.substring(LICENSEE.length()).trim();
            } else if (line.startsWith(EXPIRES)) {
                try {
                    expires = LocalDate.parse(line.substring(EXPIRES.length()).trim());
                } catch (DateTimeParseException e) {
                    throw new IllegalArgumentException("the end date is not readable");
                }
            }
        }
        if (licensee == null || licensee.isEmpty())
            throw new IllegalArgumentException("the licence names no licensee");
        return new Licence(licensee, expires);
    }

    /** The saved licence when it is valid today, otherwise null. */
    static Licence valid(Prefs prefs) {
        Licence licence = saved(prefs);
        return licence != null && !licence.expired() ? licence : null;
    }

    /** The saved licence, expired or not, or null when there is none. */
    static Licence saved(Prefs prefs) {
        String text = prefs.licence();
        if (text.isEmpty())
            return null;
        try {
            return parse(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** One word for the adb control receiver: none, valid or expired. */
    static String state(Prefs prefs) {
        Licence licence = saved(prefs);
        return licence == null ? "none" : licence.expired() ? "expired" : "valid";
    }

    private static boolean verify(byte[] data, byte[] signature) {
        try {
            PublicKey key = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Base64.decode(PUBLIC_KEY, Base64.DEFAULT)));
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(key);
            verifier.update(data);
            return verifier.verify(signature);
        } catch (GeneralSecurityException e) {
            // Also thrown for a signature that is not valid DER.
            return false;
        }
    }
}
