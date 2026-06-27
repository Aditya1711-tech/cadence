package com.cadence.security;

import java.util.UUID;

/**
 * The authenticated caller, derived from a validated access JWT.
 * {@code memberId} equals {@code members.id} and the Event Contract member_id.
 */
public record AuthPrincipal(UUID memberId, UUID orgId, String role) {
    public boolean isAdmin() {
        return "admin".equals(role) || "owner".equals(role);
    }
}
