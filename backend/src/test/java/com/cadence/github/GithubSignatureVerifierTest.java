package com.cadence.github;

import com.cadence.common.ApiException;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class GithubSignatureVerifierTest {

    private static GithubSignatureVerifier verifier(String secret) {
        GithubProperties p = new GithubProperties();
        p.setWebhookSecret(secret);
        return new GithubSignatureVerifier(p);
    }

    private static String sign(String secret, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    }

    @Test
    void acceptsValidSignature() throws Exception {
        byte[] body = "{\"hello\":\"world\"}".getBytes(StandardCharsets.UTF_8);
        verifier("s3cr3t").verify(body, sign("s3cr3t", body));   // no throw
    }

    @Test
    void rejectsTamperedBody() throws Exception {
        byte[] body = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        String sig = sign("s3cr3t", body);
        byte[] tampered = "{\"a\":2}".getBytes(StandardCharsets.UTF_8);
        assertThrows(ApiException.class, () -> verifier("s3cr3t").verify(tampered, sig));
    }

    @Test
    void rejectsWrongSecret() throws Exception {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        String sig = sign("other", body);
        assertThrows(ApiException.class, () -> verifier("s3cr3t").verify(body, sig));
    }

    @Test
    void rejectsMissingHeader() {
        assertThrows(ApiException.class, () -> verifier("s3cr3t").verify(new byte[0], null));
    }

    @Test
    void rejectsMalformedHeader() {
        assertThrows(ApiException.class, () -> verifier("s3cr3t").verify(new byte[0], "deadbeef"));
    }

    @Test
    void failsClosedWhenSecretUnconfigured() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        // With no secret held we cannot verify anything → reject even a present sig.
        assertThrows(ApiException.class,
                () -> verifier("").verify(body, "sha256=deadbeef"));
    }
}
