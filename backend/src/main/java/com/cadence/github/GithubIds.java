package com.cadence.github;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * Deterministic (RFC 4122 v5, name-based SHA-1) event ids for GitHub webhooks.
 *
 * <p>GitHub delivers webhooks at-least-once and redelivers on failure. By
 * deriving the Event Contract {@code event_id} from stable identity (repo + sha
 * for a commit; repo + PR number + action + timestamp for a PR), a redelivered
 * webhook produces the same {@code event_id} and dedupes via the existing
 * {@code ON CONFLICT (event_id, ts_start) DO NOTHING} idempotency key.
 */
public final class GithubIds {
    private GithubIds() {}

    /** Fixed Cadence/github namespace for v5 derivation (arbitrary but stable). */
    private static final UUID NAMESPACE = UUID.fromString("a3c4f8e2-1b6d-4f9a-8c2e-7d5b9f0a1c33");

    /** v5 UUID of {@code name} under the Cadence github namespace. */
    public static UUID deterministic(String name) {
        return v5(NAMESPACE, name);
    }

    static UUID v5(UUID namespace, String name) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(toBytes(namespace));
            sha1.update(name.getBytes(StandardCharsets.UTF_8));
            byte[] h = sha1.digest();           // 20 bytes; take first 16
            h[6] = (byte) ((h[6] & 0x0f) | 0x50); // version 5
            h[8] = (byte) ((h[8] & 0x3f) | 0x80); // IETF variant
            return fromBytes(h);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-1 unavailable for UUID v5", e);
        }
    }

    private static byte[] toBytes(UUID u) {
        byte[] b = new byte[16];
        long hi = u.getMostSignificantBits();
        long lo = u.getLeastSignificantBits();
        for (int i = 0; i < 8; i++) b[i] = (byte) (hi >>> (8 * (7 - i)));
        for (int i = 0; i < 8; i++) b[8 + i] = (byte) (lo >>> (8 * (7 - i)));
        return b;
    }

    private static UUID fromBytes(byte[] h) {
        long hi = 0, lo = 0;
        for (int i = 0; i < 8; i++) hi = (hi << 8) | (h[i] & 0xff);
        for (int i = 8; i < 16; i++) lo = (lo << 8) | (h[i] & 0xff);
        return new UUID(hi, lo);
    }
}
