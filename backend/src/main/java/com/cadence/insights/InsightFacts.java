package com.cadence.insights;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The frozen AGGREGATED-FACT contract the LLM narrates from (P3-A.1). The model
 * only ever sees these pre-aggregated facts — never raw event rows, never
 * prompt/response content (kickoff hard rule; 00-SYSTEM-KNOWLEDGE.md §6/§8).
 * Every number here is produced by SQL ({@link InsightsAggregationService}); the
 * model writes prose into {@code digests}.
 *
 * <p>All records serialize snake_case via the app's global Jackson strategy. The
 * one field whose snake_case differs from the documented wire name carries an
 * explicit {@link JsonProperty} ({@code deltas_vs_4wk_avg}). Shape reference:
 * backend/insights/docs/P3-A.1-aggregated-fact-shape.md.
 */
public final class InsightFacts {
    private InsightFacts() {}

    /** The eight canonical categories, seeded into {@code by_category_h} for a stable shape. */
    static final List<String> CATEGORIES = List.of(
            "deep_work", "meetings", "comms", "research",
            "code_review", "ai_assisted", "idle", "other");

    /** The focus categories that count toward fragmentation + peak block (P3-A.1 §3.1). */
    static final List<String> FOCUS_CATEGORIES =
            List.of("deep_work", "code_review", "ai_assisted", "research");

    public record Period(OffsetDateTime from, OffsetDateTime to, String isoWeek) {}

    public record PeakBlock(String dow, int hour, String category, long totalMs) {}

    public record Fragmentation(long switches, double switchesPerFocusH, double meanSessionMin) {}

    /** Delta vs the member's/org's trailing 4 completed weeks; null when &lt;4wk history. */
    public record Deltas(double deepWorkH, double meetingH, BigDecimal tokenCostUsd,
                         long commits, int fragmentationIndex) {}

    public record TopContributor(UUID memberId, String displayName, long commits, double deepWorkH) {}

    /** grain = {@code member} (MemberWeekFacts). */
    public record MemberWeekFacts(
            UUID orgId, UUID memberId, String displayName, String grain, Period period,
            double deepWorkH, double meetingH, BigDecimal tokenCostUsd, long commits,
            Integer fragmentationIndex,
            Map<String, Double> byCategoryH, long tokensIn, long tokensOut,
            Fragmentation fragmentation, PeakBlock peakBlock,
            @JsonProperty("deltas_vs_4wk_avg") Deltas deltasVs4wkAvg,
            int historyWeeks, boolean lowConfidence) {}

    /** grain = {@code org} (OrgWeekFacts). {@code topContributors} is null under aggregate_only. */
    public record OrgWeekFacts(
            UUID orgId, String grain, Period period, int activeMembers,
            double deepWorkH, double meetingH, BigDecimal tokenCostUsd, long commits,
            Integer fragmentationIndex,
            Map<String, Double> byCategoryH, long tokensIn, long tokensOut,
            List<TopContributor> topContributors, PeakBlock peakBlock,
            @JsonProperty("deltas_vs_4wk_avg") Deltas deltasVs4wkAvg) {}
}
