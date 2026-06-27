package com.cadence.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Request/response shapes for the auth surface (P2-A.2 §3). snake_case on the wire. */
public final class AuthDtos {
    private AuthDtos() {}

    public record RegisterOrgRequest(
            @NotBlank String orgName,
            @NotBlank @Email String email,
            @NotBlank @Size(min = 10, message = "must be at least 10 characters") String password,
            String displayName) {}

    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password,
            String orgSlug) {}

    public record RefreshRequest(@NotBlank String refreshToken) {}

    public record LogoutRequest(@NotBlank String refreshToken) {}

    public record InviteAcceptRequest(
            @NotBlank String token,
            @NotBlank @Size(min = 10) String password,
            String displayName,
            @Email String email) {}   // required for open links; ignored for targeted invites

    public record ForgotPasswordRequest(@NotBlank @Email String email) {}

    public record ResetPasswordRequest(
            @NotBlank String token,
            @NotBlank @Size(min = 10) String newPassword) {}

    public record DeviceEnrollRequest(@NotBlank String code) {}

    public record CreateInviteRequest(
            @Email String email, String role, UUID teamId, Integer maxUses, Integer ttlHours) {}

    // ---- responses ----

    public record MemberView(UUID id, String email, String displayName, String role, String status) {}
    public record OrgView(UUID id, String name, String slug, String privacyLevel) {}

    /** Full auth response: token pair + identity (register, login, invite-accept). */
    public record AuthResponse(
            String accessToken, String refreshToken, String tokenType, long expiresInSeconds,
            MemberView member, OrgView org) {}

    /** Token pair only (refresh). */
    public record TokenPair(
            String accessToken, String refreshToken, String tokenType, long expiresInSeconds) {}

    public record CreateInviteResponse(String token, String url, OffsetDateTime expiresAt) {}

    public record InvitePreview(String orgName, String email) {}

    public record DeviceCodeResponse(String code, OffsetDateTime expiresAt) {}

    public record DeviceEnrollResponse(
            UUID memberId, String accessToken, String refreshToken,
            String tokenType, long expiresInSeconds) {}
}
