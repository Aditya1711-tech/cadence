package com.cadence.security;

import com.cadence.common.ApiException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Convenience accessor for the authenticated {@link AuthPrincipal}. */
public final class CurrentUser {
    private CurrentUser() {}

    public static AuthPrincipal require() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AuthPrincipal p)) {
            throw ApiException.unauthorized("Not authenticated.");
        }
        return p;
    }
}
