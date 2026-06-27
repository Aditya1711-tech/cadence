package com.cadence.query;

import com.cadence.event.EventDto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Frozen response shapes for the query endpoints (P2-A.5, §6). Grouped as nested
 * records; all are snake_case on the wire via the global Jackson strategy.
 */
public final class Summaries {
    private Summaries() {}

    /** GET /me/timeline — paginated (§6 cursor + limit). */
    public record TimelineResponse(List<EventDto> items, String nextCursor) {}

    public record CategoryBucket(String category, long totalMs, long eventCount) {}

    public record DayBucket(LocalDate date, List<CategoryBucket> byCategory) {}

    public record ModelBucket(String model, BigDecimal costUsd, long tokensIn, long tokensOut) {}

    public record TokenSummary(BigDecimal totalCostUsd, List<ModelBucket> byModel) {}

    /** GET /me/summary and the per-member slice of /org/summary. */
    public record Summary(
            java.time.OffsetDateTime from,
            java.time.OffsetDateTime to,
            List<CategoryBucket> byCategory,
            List<DayBucket> byDay,
            TokenSummary tokens) {}

    /** GET /org/members — paginated roster (admin). */
    public record MemberSummary(
            UUID memberId, String email, String displayName,
            String role, String status, List<String> teams) {}

    public record MembersResponse(List<MemberSummary> items, String nextCursor) {}

    /** Per-member rollup inside /org/summary (omitted under aggregate_only). */
    public record MemberRollup(
            UUID memberId, String displayName,
            List<CategoryBucket> byCategory, TokenSummary tokens) {}

    /** GET /org/summary — honors the org privacy_level. */
    public record OrgSummary(
            java.time.OffsetDateTime from,
            java.time.OffsetDateTime to,
            String team,
            String privacyLevel,
            List<CategoryBucket> orgTotalsByCategory,
            List<DayBucket> orgByDay,
            List<MemberRollup> byMember) {}
}
