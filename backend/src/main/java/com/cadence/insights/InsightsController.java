package com.cadence.insights;

import com.cadence.security.AuthPrincipal;
import com.cadence.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * P3-A.4 — weekly insights API: {@code GET /api/v1/insights/weekly} (§6).
 * Returns the caller's structured weekly facts + narrative; admins additionally
 * receive the org-rollup digest (privacy-bounded). Auth is required (the global
 * JWT filter chain); no extra role gate — non-admins simply get a null org
 * section.
 */
@RestController
@RequestMapping("/api/v1/insights")
public class InsightsController {

    private final WeeklyInsightsService service;

    public InsightsController(WeeklyInsightsService service) {
        this.service = service;
    }

    @GetMapping("/weekly")
    public WeeklyInsightsResponse weekly(@RequestParam(required = false) String week) {
        AuthPrincipal p = CurrentUser.require();
        return service.weekly(p, week);
    }
}
