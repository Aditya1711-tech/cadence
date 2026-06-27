package com.cadence.security;

import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {

    private static JwtService service(String secret, long ttl) {
        JwtProperties p = new JwtProperties();
        p.setSigningSecret(secret);
        p.setTtlMinutes(ttl);
        return new JwtService(p);
    }

    @Test
    void issuesAndParsesRoundTrip() {
        JwtService svc = service("0123456789abcdef0123456789abcdef", 60);
        UUID member = UUID.randomUUID();
        UUID org = UUID.randomUUID();
        String token = svc.issueAccessToken(member, org, "admin");

        AuthPrincipal p = svc.parse(token);
        assertEquals(member, p.memberId());
        assertEquals(org, p.orgId());
        assertEquals("admin", p.role());
        assertTrue(p.isAdmin());
    }

    @Test
    void rejectsSecretShorterThan32Bytes() {
        assertThrows(IllegalStateException.class, () -> service("too-short", 60));
    }

    @Test
    void rejectsTamperedToken() {
        JwtService svc = service("0123456789abcdef0123456789abcdef", 60);
        String token = svc.issueAccessToken(UUID.randomUUID(), UUID.randomUUID(), "member");
        assertThrows(JwtException.class, () -> svc.parse(token + "x"));
    }

    @Test
    void rejectsTokenSignedWithDifferentSecret() {
        JwtService a = service("0123456789abcdef0123456789abcdef", 60);
        JwtService b = service("ffffffffffffffffffffffffffffffff", 60);
        String token = a.issueAccessToken(UUID.randomUUID(), UUID.randomUUID(), "member");
        assertThrows(JwtException.class, () -> b.parse(token));
    }
}
