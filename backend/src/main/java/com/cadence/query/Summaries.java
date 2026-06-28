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

    /** One day's commit count (org-level or per-member), source='github'. */
    public record DayCount(LocalDate date, long count) {}

    /** A member's commit count over the window, source='github'. */
    public record MemberCommits(UUID memberId, String displayName, long count) {}

    /**
     * Commit-activity facet of /org/summary (P2-D). Counts {@code source='github'}
     * commit events (those carrying {@code meta.commit_sha}; PR/code_review events
     * are excluded). GitHub events are zero-duration, so commits live in their own
     * facet rather than the time-by-category rollups. {@code byMember} is omitted
     * under {@code aggregate_only}; the org-level {@code total}/{@code byDay}
     * aggregates are returned at every privacy level.
     */
    public record CommitActivity(long total, List<DayCount> byDay, List<MemberCommits> byMember) {}

    /**
     * GET /org/summary — honors the org privacy_level.
     *
     * <p>The {@code commits} field is an ADDITIVE P2-D contract extension (commit
     * activity as a first-class fact alongside time + tokens). Existing readers
     * that ignore it are unaffected; see 00-SYSTEM-KNOWLEDGE.md §6.
     */
    public record OrgSummary(
            java.time.OffsetDateTime from,
            java.time.OffsetDateTime to,
            String team,
            String privacyLevel,
            List<CategoryBucket> orgTotalsByCategory,
            List<DayBucket> orgByDay,
            List<MemberRollup> byMember,
            CommitActivity commits) {}
}
