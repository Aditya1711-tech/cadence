package com.cadence.insights.pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tuning for the pattern engine (bound from {@code cadence.pattern.*}).
 * See PHASE-3 P3-B "Variables to set" and the P3-B.1 exploration doc.
 *
 * <p>{@code minDays} is the hard history floor (P3-B.4): a member/org with fewer
 * than this many distinct active days gets ZERO findings — no noisy claims. The
 * remaining knobs are per-finding evidence bars; a finding is surfaced only when
 * it clears its bar, so the threshold can be tightened without a code change.
 */
@ConfigurationProperties(prefix = "cadence.pattern")
public record PatternProperties(
        int minDays,               // CADENCE_PATTERN_MIN_DAYS — history floor
        double peakConcentration,  // peak hour-of-week ≥ N× the average active hour
        double minCorrelation,     // |Pearson r| floor for findings 2 & 3
        double minEffect           // min fractional output delta on the high/low split
) {
    public PatternProperties {
        if (minDays <= 0) {
            minDays = 14;
        }
        if (peakConcentration <= 1.0) {
            peakConcentration = 1.5;
        }
        if (minCorrelation <= 0.0) {
            minCorrelation = 0.4;
        }
        if (minEffect <= 0.0) {
            minEffect = 0.15;
        }
    }
}
