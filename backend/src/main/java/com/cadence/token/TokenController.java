package com.cadence.token;

import com.cadence.security.AuthPrincipal;
import com.cadence.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * P2-C.5 — AI token-spend read API. Sits alongside (does not modify) P2-A's
 * /me and /org controllers; these add the per-model/day token detail the admin
 * token panel (P2-E) needs, sourced from the events_daily_tokens aggregate.
 *
 *   GET /api/v1/me/tokens?range            — caller's own spend
 *   GET /api/v1/org/tokens?range&team      — team spend (admin; privacy-aware)
 */
@RestController
@RequestMapping("/api/v1")
public class TokenController {

    private final TokenQueryService service;

    public TokenController(TokenQueryService service) {
        this.service = service;
    }

    @GetMapping("/me/tokens")
    public TokenDtos.MeTokens meTokens(@RequestParam(required = false) String range) {
        AuthPrincipal p = CurrentUser.require();
        return service.me(p, range);
    }

    @GetMapping("/org/tokens")
    public TokenDtos.OrgTokens orgTokens(
            @RequestParam(required = false) String range,
            @RequestParam(required = false) String team) {
        AuthPrincipal p = CurrentUser.require();
        return service.org(p, range, team);
    }
}
