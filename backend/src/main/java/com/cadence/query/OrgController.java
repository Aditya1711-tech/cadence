package com.cadence.query;

import com.cadence.security.AuthPrincipal;
import com.cadence.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** P2-A.5 — admin read API: GET /org/members, GET /org/summary (§6). */
@RestController
@RequestMapping("/api/v1/org")
public class OrgController {

    private final OrgQueryService service;

    public OrgController(OrgQueryService service) {
        this.service = service;
    }

    @GetMapping("/members")
    public Summaries.MembersResponse members(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "100") int limit) {
        AuthPrincipal p = CurrentUser.require();
        return service.members(p, cursor, limit);
    }

    @GetMapping("/summary")
    public Summaries.OrgSummary summary(
            @RequestParam(required = false) String range,
            @RequestParam(required = false) String team) {
        AuthPrincipal p = CurrentUser.require();
        return service.summary(p, range, team);
    }
}
