package com.cadence.auth;

import com.cadence.auth.AuthDtos.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** P2-A.6 — public auth surface (§6, P2-A.2 §3). All under /api/v1/auth (permitAll). */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/register-org")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse registerOrg(@RequestBody @Valid RegisterOrgRequest req) {
        return auth.registerOrg(req);
    }

    @PostMapping("/login")
    public AuthResponse login(@RequestBody @Valid LoginRequest req) {
        return auth.login(req);
    }

    @PostMapping("/refresh")
    public TokenPair refresh(@RequestBody @Valid RefreshRequest req) {
        return auth.refresh(req);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@RequestBody @Valid LogoutRequest req) {
        auth.logout(req);
    }

    @GetMapping("/invite/{token}")
    public InvitePreview previewInvite(@PathVariable String token) {
        return auth.previewInvite(token);
    }

    @PostMapping("/invite/accept")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse acceptInvite(@RequestBody @Valid InviteAcceptRequest req) {
        return auth.acceptInvite(req);
    }

    @PostMapping("/password/forgot")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void forgotPassword(@RequestBody @Valid ForgotPasswordRequest req) {
        auth.forgotPassword(req);
    }

    @PostMapping("/password/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@RequestBody @Valid ResetPasswordRequest req) {
        auth.resetPassword(req);
    }

    @PostMapping("/device/enroll")
    public DeviceEnrollResponse enrollDevice(@RequestBody @Valid DeviceEnrollRequest req) {
        return auth.enrollDevice(req);
    }
}
