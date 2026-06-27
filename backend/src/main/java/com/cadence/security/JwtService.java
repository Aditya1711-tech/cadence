package com.cadence.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Issues and validates self-issued HS256 access tokens (P2-A.2 §1).
 * Claims: sub=member_id, org=org_id, role, typ=access.
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final long ttlMinutes;

    public JwtService(JwtProperties props) {
        byte[] secret = props.getSigningSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException(
                "JWT_SIGNING_SECRET must be at least 32 bytes (got " + secret.length + ").");
        }
        this.key = Keys.hmacShaKeyFor(secret);
        this.ttlMinutes = props.getTtlMinutes();
    }

    /** Mint an access token for a member. */
    public String issueAccessToken(UUID memberId, UUID orgId, String role) {
        Instant now = Instant.now();
        Instant exp = now.plus(Duration.ofMinutes(ttlMinutes));
        return Jwts.builder()
                .subject(memberId.toString())
                .claim("org", orgId.toString())
                .claim("role", role)
                .claim("typ", "access")
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(key)
                .compact();
    }

    /** Validate signature + expiry; map to a principal. Throws on any problem. */
    public AuthPrincipal parse(String token) {
        try {
            Claims c = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload();
            if (!"access".equals(c.get("typ", String.class))) {
                throw new JwtException("not an access token");
            }
            return new AuthPrincipal(
                    UUID.fromString(c.getSubject()),
                    UUID.fromString(c.get("org", String.class)),
                    c.get("role", String.class));
        } catch (IllegalArgumentException e) {
            throw new JwtException("malformed token claims", e);
        }
    }

    public long ttlMinutes() { return ttlMinutes; }
}
