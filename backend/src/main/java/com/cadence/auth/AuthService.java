package com.cadence.auth;

import com.cadence.auth.AuthDtos.*;
import com.cadence.common.ApiException;
import com.cadence.mail.Mailer;
import com.cadence.security.AuthPrincipal;
import com.cadence.security.JwtService;
import com.cadence.tenancy.Tenancy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Auth + onboarding logic (P2-A.6, P2-A.2). Public flows (register/login/refresh/
 * invite-accept/reset/enroll) operate across org boundaries by secret token; org-
 * scoped admin flows (create invite, device code) bind the tenant context.
 *
 * NOTE: this milestone uses the single primary datasource. In dev it connects as
 * the DB owner (RLS bypassed). Production hardening (a non-owner cadence_app role
 * for org-scoped requests + a privileged auth datasource for the cross-org flows)
 * is a documented follow-up (P2-A.8 / deploy); RLS policies are already in place.
 */
@Service
public class AuthService {

    private final JdbcTemplate jdbc;
    private final JwtService jwt;
    private final RefreshTokenService refresh;
    private final PasswordEncoder encoder;
    private final Tenancy tenancy;
    private final Mailer mailer;
    private final String defaultPrivacy;
    private final String publicBaseUrl;

    public AuthService(JdbcTemplate jdbc, JwtService jwt, RefreshTokenService refresh,
                       PasswordEncoder encoder, Tenancy tenancy, Mailer mailer,
                       @Value("${cadence.org.default-privacy}") String defaultPrivacy,
                       @Value("${cadence.app.public-base-url}") String publicBaseUrl) {
        this.jdbc = jdbc;
        this.jwt = jwt;
        this.refresh = refresh;
        this.encoder = encoder;
        this.tenancy = tenancy;
        this.mailer = mailer;
        this.defaultPrivacy = defaultPrivacy;
        this.publicBaseUrl = publicBaseUrl;
    }

    // ── register-org ─────────────────────────────────────────────────────────
    @Transactional
    public AuthResponse registerOrg(RegisterOrgRequest req) {
        String slug = uniqueSlug(req.orgName());
        UUID orgId = jdbc.queryForObject("""
                INSERT INTO orgs (name, slug, privacy_level) VALUES (?, ?, ?) RETURNING id
                """, UUID.class, req.orgName(), slug, defaultPrivacy);
        UUID memberId = jdbc.queryForObject("""
                INSERT INTO members (org_id, email, password_hash, display_name, role, status)
                VALUES (?, ?, ?, ?, 'owner', 'active') RETURNING id
                """, UUID.class, orgId, req.email(), encoder.encode(req.password()), req.displayName());
        jdbc.update("INSERT INTO seats (org_id, member_id, status) VALUES (?, ?, 'active')",
                orgId, memberId);
        return authResponse(memberId, orgId, "owner", "daemon", req.email(), req.displayName(), "active");
    }

    // ── login ────────────────────────────────────────────────────────────────
    @Transactional
    public AuthResponse login(LoginRequest req) {
        StringBuilder sql = new StringBuilder("""
                SELECT m.id, m.org_id, m.role, m.status, m.password_hash, m.display_name,
                       o.slug
                FROM members m JOIN orgs o ON o.id = m.org_id
                WHERE m.email = ?
                """);
        Object[] args;
        if (req.orgSlug() != null && !req.orgSlug().isBlank()) {
            sql.append(" AND o.slug = ?");
            args = new Object[]{req.email(), req.orgSlug()};
        } else {
            args = new Object[]{req.email()};
        }
        List<LoginRow> rows = jdbc.query(sql.toString(), (rs, i) -> new LoginRow(
                rs.getObject("id", UUID.class), rs.getObject("org_id", UUID.class),
                rs.getString("role"), rs.getString("status"), rs.getString("password_hash"),
                rs.getString("display_name"), rs.getString("slug")), args);

        if (rows.size() > 1) {
            throw ApiException.conflict("Email belongs to multiple orgs; retry with org_slug: "
                    + rows.stream().map(LoginRow::slug).toList());
        }
        if (rows.isEmpty()) throw ApiException.unauthorized("Invalid credentials.");
        LoginRow m = rows.get(0);
        if (!"active".equals(m.status()) || m.passwordHash() == null
                || !encoder.matches(req.password(), m.passwordHash())) {
            throw ApiException.unauthorized("Invalid credentials.");
        }
        return authResponse(m.id(), m.orgId(), m.role(), "daemon", req.email(), m.displayName(), m.status());
    }

    // ── refresh / logout ──────────────────────────────────────────────────────
    public TokenPair refresh(RefreshRequest req) {
        var r = refresh.rotate(req.refreshToken());
        String access = jwt.issueAccessToken(r.memberId(), r.orgId(), r.role());
        return new TokenPair(access, r.refreshToken(), "Bearer", jwt.ttlMinutes() * 60);
    }

    public void logout(LogoutRequest req) {
        refresh.revoke(req.refreshToken());
    }

    // ── invites ────────────────────────────────────────────────────────────────
    @Transactional
    public CreateInviteResponse createInvite(AuthPrincipal admin, CreateInviteRequest req) {
        if (!admin.isAdmin()) throw ApiException.forbidden("Admin role required.");
        tenancy.bind(admin);
        String role = (req.role() == null) ? "member" : req.role();
        if (!role.equals("member") && !role.equals("admin")) {
            throw ApiException.badRequest("Invite role must be 'member' or 'admin'.");
        }
        int ttlHours = req.ttlHours() == null ? 168 : req.ttlHours();
        Instant exp = Instant.now().plus(Duration.ofHours(ttlHours));
        String token = Tokens.random();
        jdbc.update("""
                INSERT INTO invites (org_id, email, token_hash, role, team_id, max_uses,
                                     expires_at, created_by_member_id)
                VALUES (?, ?, ?, ?, ?::uuid, ?::int, ?, ?)
                """, admin.orgId(), req.email(), Tokens.sha256(token), role,
                req.teamId() == null ? null : req.teamId().toString(),
                req.maxUses(), Timestamp.from(exp), admin.memberId());
        String url = publicBaseUrl + "/invite/" + token;
        return new CreateInviteResponse(token, url, exp.atOffset(ZoneOffset.UTC));
    }

    public InvitePreview previewInvite(String token) {
        try {
            return jdbc.queryForObject("""
                    SELECT o.name AS org_name, i.email
                    FROM invites i JOIN orgs o ON o.id = i.org_id
                    WHERE i.token_hash = ?
                      AND (i.expires_at IS NULL OR i.expires_at > now())
                      AND (i.max_uses IS NULL OR i.uses < i.max_uses)
                    """, (rs, n) -> new InvitePreview(rs.getString("org_name"), rs.getString("email")),
                    Tokens.sha256(token));
        } catch (EmptyResultDataAccessException e) {
            throw ApiException.gone("Invite is invalid, expired, or fully used.");
        }
    }

    @Transactional
    public AuthResponse acceptInvite(InviteAcceptRequest req) {
        InviteRow inv;
        try {
            inv = jdbc.queryForObject("""
                    SELECT id, org_id, email, role, team_id, max_uses, uses
                    FROM invites
                    WHERE token_hash = ?
                      AND (expires_at IS NULL OR expires_at > now())
                      AND (max_uses IS NULL OR uses < max_uses)
                    FOR UPDATE
                    """, (rs, n) -> new InviteRow(
                            rs.getObject("id", UUID.class), rs.getObject("org_id", UUID.class),
                            rs.getString("email"), rs.getString("role"),
                            rs.getObject("team_id", UUID.class)), Tokens.sha256(req.token()));
        } catch (EmptyResultDataAccessException e) {
            throw ApiException.gone("Invite is invalid, expired, or fully used.");
        }

        String email = inv.email() != null ? inv.email() : req.email();
        if (email == null || email.isBlank()) {
            throw ApiException.badRequest("An email is required to accept an open invite.");
        }

        // Activate an existing invited member or create a fresh one.
        UUID memberId = jdbc.query("SELECT id FROM members WHERE org_id = ? AND email = ?",
                rs -> rs.next() ? rs.getObject(1, UUID.class) : null, inv.orgId(), email);
        String pwHash = encoder.encode(req.password());
        if (memberId != null) {
            jdbc.update("""
                    UPDATE members SET password_hash = ?, display_name = coalesce(?, display_name),
                           status = 'active', role = ? WHERE id = ?
                    """, pwHash, req.displayName(), inv.role(), memberId);
        } else {
            memberId = jdbc.queryForObject("""
                    INSERT INTO members (org_id, email, password_hash, display_name, role, status)
                    VALUES (?, ?, ?, ?, ?, 'active') RETURNING id
                    """, UUID.class, inv.orgId(), email, pwHash, req.displayName(), inv.role());
        }
        jdbc.update("""
                INSERT INTO seats (org_id, member_id, status) VALUES (?, ?, 'active')
                ON CONFLICT DO NOTHING
                """, inv.orgId(), memberId);
        if (inv.teamId() != null) {
            jdbc.update("""
                    INSERT INTO team_members (org_id, team_id, member_id) VALUES (?, ?, ?)
                    ON CONFLICT DO NOTHING
                    """, inv.orgId(), inv.teamId(), memberId);
        }
        jdbc.update("UPDATE invites SET uses = uses + 1 WHERE id = ?", inv.id());

        return authResponse(memberId, inv.orgId(), inv.role(), "daemon", email, req.displayName(), "active");
    }

    // ── password reset ──────────────────────────────────────────────────────────
    public void forgotPassword(ForgotPasswordRequest req) {
        List<UUID[]> matches = jdbc.query(
                "SELECT id, org_id FROM members WHERE email = ? AND status <> 'disabled'",
                (rs, i) -> new UUID[]{rs.getObject("id", UUID.class), rs.getObject("org_id", UUID.class)},
                req.email());
        for (UUID[] m : matches) {
            String token = Tokens.random();
            jdbc.update("""
                    INSERT INTO one_time_tokens (org_id, member_id, kind, token_hash, expires_at)
                    VALUES (?, ?, 'password_reset', ?, ?)
                    """, m[1], m[0], Tokens.sha256(token),
                    Timestamp.from(Instant.now().plus(Duration.ofMinutes(30))));
            mailer.send(req.email(), "Reset your Cadence password",
                    "Reset link: " + publicBaseUrl + "/reset-password?token=" + token
                            + "\nThis link expires in 30 minutes.");
        }
        // Always 202 — never reveal whether the email exists.
    }

    @Transactional
    public void resetPassword(ResetPasswordRequest req) {
        UUID memberId = consumeOtt("password_reset", req.token());
        jdbc.update("UPDATE members SET password_hash = ? WHERE id = ?",
                encoder.encode(req.newPassword()), memberId);
        refresh.revokeAllForMember(memberId);
    }

    // ── device enrollment ───────────────────────────────────────────────────────
    @Transactional
    public DeviceCodeResponse mintDeviceCode(AuthPrincipal p, String deviceLabel) {
        tenancy.bind(p);
        String code = Tokens.shortCode();
        Instant exp = Instant.now().plus(Duration.ofMinutes(15));
        jdbc.update("""
                INSERT INTO one_time_tokens (org_id, member_id, kind, token_hash, expires_at, meta)
                VALUES (?, ?, 'device_enroll', ?, ?, ?::jsonb)
                """, p.orgId(), p.memberId(), Tokens.sha256(code), Timestamp.from(exp),
                deviceLabel == null ? "{}" : "{\"device_label\":\"" + deviceLabel + "\"}");
        return new DeviceCodeResponse(code, exp.atOffset(ZoneOffset.UTC));
    }

    @Transactional
    public DeviceEnrollResponse enrollDevice(DeviceEnrollRequest req) {
        OttRow ott = lookupOtt("device_enroll", req.code());
        jdbc.update("UPDATE one_time_tokens SET used_at = now() WHERE id = ?", ott.id());
        String role = jdbc.queryForObject("SELECT role FROM members WHERE id = ?",
                String.class, ott.memberId());
        String access = jwt.issueAccessToken(ott.memberId(), ott.orgId(), role);
        String refreshTok = refresh.issue(ott.memberId(), ott.orgId(), "daemon");
        return new DeviceEnrollResponse(ott.memberId(), access, refreshTok, "Bearer",
                jwt.ttlMinutes() * 60);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────
    private AuthResponse authResponse(UUID memberId, UUID orgId, String role, String deviceLabel,
                                      String email, String displayName, String status) {
        String access = jwt.issueAccessToken(memberId, orgId, role);
        String refreshTok = refresh.issue(memberId, orgId, deviceLabel);
        OrgRow o = jdbc.queryForObject(
                "SELECT name, slug, privacy_level FROM orgs WHERE id = ?",
                (rs, i) -> new OrgRow(rs.getString("name"), rs.getString("slug"),
                        rs.getString("privacy_level")), orgId);
        return new AuthResponse(access, refreshTok, "Bearer", jwt.ttlMinutes() * 60,
                new MemberView(memberId, email, displayName, role, status),
                new OrgView(orgId, o.name(), o.slug(), o.privacyLevel()));
    }

    private UUID consumeOtt(String kind, String token) {
        OttRow row = lookupOtt(kind, token);
        jdbc.update("UPDATE one_time_tokens SET used_at = now() WHERE id = ?", row.id());
        return row.memberId();
    }

    private OttRow lookupOtt(String kind, String token) {
        try {
            return jdbc.queryForObject("""
                    SELECT id, org_id, member_id FROM one_time_tokens
                    WHERE kind = ? AND token_hash = ? AND used_at IS NULL AND expires_at > now()
                    FOR UPDATE
                    """, (rs, i) -> new OttRow(rs.getObject("id", UUID.class),
                            rs.getObject("org_id", UUID.class), rs.getObject("member_id", UUID.class)),
                    kind, Tokens.sha256(token));
        } catch (EmptyResultDataAccessException e) {
            throw ApiException.gone("Code is invalid, expired, or already used.");
        }
    }

    private String uniqueSlug(String name) {
        String base = name.toLowerCase().replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        if (base.isBlank()) base = "org";
        return base + "-" + Tokens.shortCode().substring(0, 6).toLowerCase();
    }

    private record LoginRow(UUID id, UUID orgId, String role, String status,
                            String passwordHash, String displayName, String slug) {}
    private record InviteRow(UUID id, UUID orgId, String email, String role, UUID teamId) {}
    private record OttRow(UUID id, UUID orgId, UUID memberId) {}
    private record OrgRow(String name, String slug, String privacyLevel) {}
}
