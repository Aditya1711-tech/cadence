package com.cadence.insights.pattern;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Frozen response shapes for the pattern engine (P3-B.3). Snake_case on the wire
 * via the app's global Jackson strategy (§5), same as {@code Summaries.*}.
 *
 * <p>A {@link Finding} is a typed, pre-aggregated fact: every number in
 * {@code evidence} comes from SQL — the LLM never sees raw events (kickoff hard
 * rule). The narrator (P3-A) grounds prose in these numbers; the admin view
 * (P2-E) renders {@code title}/{@code detail}. This is the ADDITIVE extension the
 * digest + admin surfaces read, same contract discipline as P2-D's commits facet.
 */
public final class Findings {
    private Findings() {}

    /** Categories of insight P3-B surfaces; the wire {@code kind}. */
    public static final String KIND_PEAK_WINDOW = "peak_window";
    public static final String KIND_MEETING_OUTPUT = "meeting_output";
    public static final String KIND_CONTEXT_SWITCH = "context_switch";

    /** We only ever surface high-confidence findings (medium/low are dropped). */
    public static final String CONFIDENCE_HIGH = "high";

    /**
     * One high-confidence pattern finding.
     *
     * @param kind       one of the {@code KIND_*} constants
     * @param title      short headline (e.g. "Peak focus: Tuesday mornings")
     * @param detail     one grounded sentence; every number traces to {@code evidence}
     * @param confidence always {@link #CONFIDENCE_HIGH}
     * @param strength   ranking score in [0,1] (effect size / |r| / concentration)
     * @param evidence   the SQL-derived numbers the narrator may quote
     */
    public record Finding(
            String kind,
            String title,
            String detail,
            String confidence,
            double strength,
            Map<String, Object> evidence) {}

    /**
     * The pattern payload for a member or org over a window — the body of
     * {@code GET /api/v1/insights/patterns} and the additive {@code facts.patterns}.
     *
     * <p>{@code findings} is empty when {@code lowConfidence} (history &lt;
     * {@code CADENCE_PATTERN_MIN_DAYS}); otherwise it holds ≤ 3 high-confidence
     * findings, ranked by {@code strength} descending.
     */
    public record PatternFindings(
            String grain,             // "member" | "org"
            OffsetDateTime from,
            OffsetDateTime to,
            int historyDays,
            boolean lowConfidence,
            List<Finding> findings) {}
}
