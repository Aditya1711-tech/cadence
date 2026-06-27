package com.cadence.auth;

import com.cadence.common.ApiException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Opaque, rotating, revocable refresh tokens with token-family reuse detection
 * (P2-A.2 §1). Only SHA-256 hashes are stored. Operates across org boundaries
 * (the token is presented without org context).
 */
@Service
public class RefreshTokenService {

    static final Duration TTL = Duration.ofDays(60);

    private final JdbcTemplate jdbc;

    public RefreshTokenService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Mint a new refresh token in a fresh family. Returns the plaintext. */
    public String issue(UUID memberId, UUID orgId, UUID familyId, String deviceLabel) {
        String plaintext = Tokens.random();
        jdbc.update("""
                INSERT INTO refresh_tokens (member_id, org_id, token_hash, family_id,
                                            device_label, expires_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                memberId, orgId, Tokens.sha256(plaintext), familyId, deviceLabel,
                Timestamp.from(Instant.now().plus(TTL)));
        return plaintext;
    }

    public String issue(UUID memberId, UUID orgId, String deviceLabel) {
        return issue(memberId, orgId, UUID.randomUUID(), deviceLabel);
    }

    public record Rotated(UUID memberId, UUID orgId, String role, String refreshToken) {}

    /** Validate + rotate. On reuse of a consumed token, revoke the whole family. */
    @Transactional
    public Rotated rotate(String presented) {
        String hash = Tokens.sha256(presented);
        Row row;
        try {
            row = jdbc.queryForObject("""
                    SELECT rt.id, rt.member_id, rt.org_id, rt.family_id, rt.device_label,
                           rt.expires_at, rt.revoked_at, rt.replaced_by, m.role
                    FROM refresh_tokens rt JOIN members m ON m.id = rt.member_id
                    WHERE rt.token_hash = ?
                    """, (rs, i) -> new Row(
                            rs.getObject("id", UUID.class),
                            rs.getObject("member_id", UUID.class),
                            rs.getObject("org_id", UUID.class),
                            rs.getObject("family_id", UUID.class),
                            rs.getString("device_label"),
                            rs.getTimestamp("expires_at").toInstant(),
                            rs.getTimestamp("revoked_at") == null ? null : rs.getTimestamp("revoked_at").toInstant(),
                            rs.getObject("replaced_by", UUID.class),
                            rs.getString("role")), hash);
        } catch (EmptyResultDataAccessException e) {
            throw ApiException.unauthorized("Invalid refresh token.");
        }

        // Reuse of an already-rotated/revoked token → theft response: kill family.
        if (row.revokedAt() != null || row.replacedBy() != null) {
            revokeFamily(row.familyId());
            throw ApiException.unauthorized("Refresh token reuse detected; session revoked.");
        }
        if (row.expiresAt().isBefore(Instant.now())) {
            throw ApiException.unauthorized("Refresh token expired.");
        }

        String next = issue(row.memberId(), row.orgId(), row.familyId(), row.deviceLabel());
        jdbc.update("""
                UPDATE refresh_tokens SET revoked_at = now(),
                       replaced_by = (SELECT id FROM refresh_tokens WHERE token_hash = ?)
                WHERE id = ?
                """, Tokens.sha256(next), row.id());
        return new Rotated(row.memberId(), row.orgId(), row.role(), next);
    }

    /** Revoke the token (and its family) — logout. */
    @Transactional
    public void revoke(String presented) {
        UUID family = jdbc.query("SELECT family_id FROM refresh_tokens WHERE token_hash = ?",
                rs -> rs.next() ? rs.getObject(1, UUID.class) : null, Tokens.sha256(presented));
        if (family != null) revokeFamily(family);
    }

    public void revokeAllForMember(UUID memberId) {
        jdbc.update("UPDATE refresh_tokens SET revoked_at = now() WHERE member_id = ? AND revoked_at IS NULL",
                memberId);
    }

    private void revokeFamily(UUID familyId) {
        jdbc.update("UPDATE refresh_tokens SET revoked_at = now() WHERE family_id = ? AND revoked_at IS NULL",
                familyId);
    }

    private record Row(UUID id, UUID memberId, UUID orgId, UUID familyId, String deviceLabel,
                       Instant expiresAt, Instant revokedAt, UUID replacedBy, String role) {}
}
