package com.cadence.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** High-entropy token generation + hashing for invites, refresh, and OTTs (P2-A.2 §6). */
final class Tokens {
    private Tokens() {}

    private static final SecureRandom RNG = new SecureRandom();

    /** 32 bytes of entropy, base64url, no padding. */
    static String random() {
        byte[] b = new byte[32];
        RNG.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** A shorter human-typeable code (device enrollment): 20 base32-ish chars. */
    static String shortCode() {
        byte[] b = new byte[12];
        RNG.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** Stable hash stored in the DB; the plaintext token lives only with the client. */
    static String sha256(String token) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
