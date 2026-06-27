package com.cadence.auth;

import com.cadence.auth.AuthDtos.*;
import com.cadence.security.AuthPrincipal;
import com.cadence.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * Authenticated onboarding endpoints (P2-A.6): admin creates invites; a member
 * mints a one-time device-enrollment code for their daemon.
 */
@RestController
@RequestMapping("/api/v1")
public class OnboardingController {

    private final AuthService auth;

    public OnboardingController(AuthService auth) {
        this.auth = auth;
    }

    /** Admin: create an invite (targeted email or open shareable link). */
    @PostMapping("/org/invites")
    @ResponseStatus(HttpStatus.CREATED)
    public CreateInviteResponse createInvite(@RequestBody @Valid CreateInviteRequest req) {
        AuthPrincipal p = CurrentUser.require();
        return auth.createInvite(p, req);
    }

    /** Member: mint a one-time device-enrollment code for the daemon. */
    @PostMapping("/me/device-codes")
    @ResponseStatus(HttpStatus.CREATED)
    public DeviceCodeResponse mintDeviceCode(@RequestParam(required = false) String deviceLabel) {
        AuthPrincipal p = CurrentUser.require();
        return auth.mintDeviceCode(p, deviceLabel);
    }
}
