package com.cadence.insights;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * {@code GET /api/v1/insights/weekly} response (P3-A.4, §6). Carries the
 * structured pre-aggregated {@code facts} plus the generated narrative for the
 * caller's own digest, and — for admins — the org-rollup digest. {@code facts}
 * is always live (recomputed from SQL); {@code narrative}/{@code spotted}/
 * {@code cardSvg} are populated once the weekly digest job (P3-A.5) has run, and
 * are null/empty until then.
 */
public record WeeklyInsightsResponse(String week, Section member, Section org) {

    public record Section(
            Object facts,                 // MemberWeekFacts or OrgWeekFacts
            String narrative,             // null until the digest job narrates
            List<Spotted> spotted,        // the 3 spotted insights; empty until narrated
            String cardSvg,               // shareable card; null until rendered (P3-A.7)
            OffsetDateTime generatedAt,   // digest creation time; null if facts-only
            String status) {}             // pending|rendered|sent|failed|null

    public record Spotted(String title, String detail) {}
}
