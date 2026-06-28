package com.cadence.github;

import com.cadence.common.ApiException;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Verifies the GitHub webhook HMAC ({@code X-Hub-Signature-256}) over the raw
 * request body using {@code GITHUB_WEBHOOK_SECRET}. This is the webhook's
 * authentication — the endpoint is otherwise public (no JWT). Uses JDK crypto
 * only (no new dependency).
 */
@Component
public class GithubSignatureVerifier {

    private static final String ALGO = "HmacSHA256";
    private static final String PREFIX = "sha256=";

    private final GithubProperties props;

    public GithubSignatureVerifier(GithubProperties props) {
        this.props = props;
    }

    /** Throws {@link ApiException} (401) on any failure; returns quietly if valid. */
    public void verify(byte[] body, String signatureHeader) {
        String secret = props.getWebhookSecret();
        if (secret == null || secret.isBlank()) {
            // Misconfiguration must fail closed — we cannot trust an unverifiable body.
            throw ApiException.unauthorized("GitHub webhook secret is not configured.");
        }
        if (signatureHeader == null || !signatureHeader.startsWith(PREFIX)) {
            throw ApiException.unauthorized("Missing or malformed X-Hub-Signature-256.");
        }
        String expected = PREFIX + hmacHex(secret, body == null ? new byte[0] : body);
        // Constant-time comparison over the full header strings.
        boolean ok = MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signatureHeader.getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            throw ApiException.unauthorized("Invalid webhook signature.");
        }
    }

    private static String hmacHex(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance(ALGO);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGO));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }
}
