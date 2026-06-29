package com.cadence.insights.pattern;

import com.cadence.security.AuthPrincipal;
import com.cadence.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * P3-B.3 — pattern findings read API. An ADDITIVE Phase-3 route (documented in
 * §6) that lets the admin view (P2-E) consume findings now, without waiting on
 * P3-A's digest. Sits alongside P3-A's {@code GET /insights/weekly}; the same
 * findings also ride into the digest via the additive {@code facts.patterns}
 * field (wired by P3-A — see the NEEDS line in PROGRESS.md).
 *
 *   GET /api/v1/insights/patterns?range            — caller's own findings
 *   GET /api/v1/insights/patterns?range&scope=org  — team findings (admin)
 *
 * <p>{@code range} accepts {@code <N>d}/{@code <N>w} (default 4w). Low-history
 * callers get {@code low_confidence=true} and an empty {@code findings} list.
 */
@RestController
@RequestMapping("/api/v1/insights")
public class PatternController {

    private final PatternService service;

    public PatternController(PatternService service) {
        this.service = service;
    }

    @GetMapping("/patterns")
    public Findings.PatternFindings patterns(
            @RequestParam(required = false) String range,
            @RequestParam(required = false) String scope) {
        AuthPrincipal p = CurrentUser.require();
        return "org".equalsIgnoreCase(scope)
                ? service.forOrg(p, range)
                : service.forMember(p, range);
    }
}
