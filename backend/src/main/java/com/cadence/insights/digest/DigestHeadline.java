package com.cadence.insights.digest;

import com.cadence.insights.InsightFacts.PeakBlock;

import java.math.BigDecimal;

/**
 * The handful of headline numbers the narrator template and the shareable card
 * render from — extracted from the full {@code MemberWeekFacts}/{@code
 * OrgWeekFacts} by the digest job. Every value here came from SQL; the card and
 * the template never compute anything new.
 */
record DigestHeadline(
        String title,            // "Octo Dev" / "Your team"
        String isoWeek,
        double deepWorkH,
        double meetingH,
        BigDecimal tokenCostUsd,
        long commits,
        Integer fragmentationIndex,
        PeakBlock peak,
        boolean lowConfidence) {

    /** 0..100, higher = more focused (the card's hero metric). */
    int focusScore() {
        return fragmentationIndex == null ? 100 : Math.max(0, 100 - fragmentationIndex);
    }

    String peakLabel() {
        return peak == null ? "no clear peak block"
                : "%s around %02d:00".formatted(peak.dow(), peak.hour());
    }
}
