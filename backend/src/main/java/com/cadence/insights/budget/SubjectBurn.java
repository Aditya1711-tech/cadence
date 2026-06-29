package com.cadence.insights.budget;

import java.util.List;
import java.util.UUID;

/**
 * One subject's burn picture for the day under evaluation: today's spend, the
 * trailing series of ACTIVE-day spends (each &gt; 0) that forms the rolling
 * baseline, and today's top model by spend. Built by {@link BudgetWindowAssembler}
 * from {@code events_daily_tokens} rows — pure data, no DB.
 */
record SubjectBurn(
        SubjectType subjectType,
        UUID subjectId,                 // null for ORG grain
        String displayName,
        double todayUsd,
        List<Double> baselineActiveDayBurns,
        String topModelToday
) {
}
