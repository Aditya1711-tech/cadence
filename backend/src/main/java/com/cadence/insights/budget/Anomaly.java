package com.cadence.insights.budget;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A detected token-burn spike for one subject on one day, at a given severity
 * tier. This is the unit the dedupe ledger keys on and the narrator describes —
 * every number here came from SQL over {@code events_daily_tokens}.
 */
record Anomaly(
        UUID orgId,
        SubjectType subjectType,
        UUID subjectId,          // null for ORG grain
        String displayName,
        LocalDate day,
        BigDecimal todayUsd,
        BigDecimal baselineUsd,  // the rolling active-day mean
        double ratio,
        int severity,            // tier crossed (e.g. 3 / 5 / 10)
        String topModel
) {
}
