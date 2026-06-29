package com.cadence.insights.budget;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The anomaly rule (P3-E.1), pure and DB-free so it is exhaustively unit-tested.
 *
 * <p>Baseline = mean over the subject's ACTIVE days (spend &gt; 0) in the trailing
 * window. A spike fires only when BOTH hold:
 * <ol>
 *   <li>{@code today / mean >= spikeMultiplier} (default 3×), and</li>
 *   <li>{@code today >= minAbsoluteUsd}      (a real-money floor, default $10).</li>
 * </ol>
 * Subjects with fewer than {@code minHistoryDays} active days get nothing (no
 * noise for new/low-data members). Severity is the highest configured tier ≤
 * ratio; it drives escalation-only dedupe in the ledger.
 */
@Component
class BudgetAnomalyDetector {

    Optional<Anomaly> evaluate(UUID orgId, LocalDate day, SubjectBurn s, OrgBudgetConfig cfg) {
        List<Double> active = s.baselineActiveDayBurns();
        if (active.size() < cfg.minHistoryDays()) {
            return Optional.empty();                       // not enough history → silent
        }
        double mean = active.stream().mapToDouble(Double::doubleValue).sum() / active.size();
        if (mean <= 0) {
            return Optional.empty();
        }
        double today = s.todayUsd();
        if (today < cfg.minAbsoluteUsd().doubleValue()) {
            return Optional.empty();                       // below the real-money floor
        }
        double ratio = today / mean;
        if (ratio < cfg.spikeMultiplier()) {
            return Optional.empty();                       // not a spike
        }
        int severity = severityFor(ratio, cfg.tiers());
        if (severity <= 0) {
            return Optional.empty();                       // ratio below the lowest tier
        }
        return Optional.of(new Anomaly(
                orgId, s.subjectType(), s.subjectId(), s.displayName(), day,
                usd(today), usd(mean), round1(ratio), severity, s.topModelToday()));
    }

    /** Highest tier ≤ ratio (tiers need not be sorted); 0 if ratio is below all. */
    static int severityFor(double ratio, int[] tiers) {
        int best = 0;
        for (int t : tiers) {
            if (ratio >= t && t > best) {
                best = t;
            }
        }
        return best;
    }

    private static BigDecimal usd(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
