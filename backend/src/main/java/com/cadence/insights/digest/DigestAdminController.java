package com.cadence.insights.digest;

import com.cadence.common.ApiException;
import com.cadence.insights.IsoWeek;
import com.cadence.security.AuthPrincipal;
import com.cadence.security.CurrentUser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Admin-only manual trigger for local dogfooding (P3-A.5): runs the digest
 * pipeline for the caller's org now, instead of waiting for the Sunday cron.
 * Only registered when the digest stack is enabled. Synchronous — fine for the
 * handful of members per org; the scheduled run is the production path.
 */
@RestController
@RequestMapping("/api/v1/insights")
@ConditionalOnProperty(prefix = "cadence.digest", name = "enabled", havingValue = "true")
class DigestAdminController {

    private final DigestService service;

    DigestAdminController(DigestService service) {
        this.service = service;
    }

    public record RunResult(String week, int digests) {}

    @PostMapping("/run")
    public RunResult run(@RequestParam(required = false) String week) {
        AuthPrincipal p = CurrentUser.require();
        if (!p.isAdmin()) throw ApiException.forbidden("Admin role required.");
        IsoWeek w = (week == null || week.isBlank())
                ? IsoWeek.mostRecentCompleted(OffsetDateTime.now(ZoneOffset.UTC))
                : IsoWeek.parse(week);
        return new RunResult(w.label(), service.runForOrg(p.orgId(), w));
    }
}
