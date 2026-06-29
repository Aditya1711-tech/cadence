package com.cadence.insights.budget;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Types;

/**
 * The dedupe ledger (P3-E.1 §2.1). {@link #tryClaim} inserts the alert keyed on
 * {@code (org, subject_type, subject_id, day, severity)} with {@code ON CONFLICT
 * DO NOTHING}; it returns {@code true} only if a row was actually inserted, which
 * is the signal to deliver. This is the same idempotent pattern as event ingest
 * and is correct across restarts AND multiple instances (no double-send). Because
 * the key includes severity, a worsening spike escalates once per tier and
 * identical re-checks send nothing.
 *
 * <p>Table owned by P3-A (NEEDS P3-E→P3-A). If it is missing the query throws;
 * the monitor catches per-org and skips — we will NOT deliver without the ledger
 * (delivering un-deduped would violate the never-spam guarantee).
 */
@Component
class BudgetAlertLedger {

    private final JdbcTemplate jdbc;

    BudgetAlertLedger(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true if this (subject, day, severity) was newly claimed → deliver. */
    boolean tryClaim(Anomaly a, String channel) {
        int rows = jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO budget_alerts
                        (org_id, subject_type, subject_id, day, severity, ratio,
                         today_usd, baseline_usd, channel, delivered)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, true)
                    ON CONFLICT DO NOTHING
                    """);
            ps.setObject(1, a.orgId());
            ps.setString(2, a.subjectType().wire());
            if (a.subjectId() == null) {
                ps.setNull(3, Types.OTHER);
            } else {
                ps.setObject(3, a.subjectId());
            }
            ps.setObject(4, a.day());
            ps.setInt(5, a.severity());
            ps.setBigDecimal(6, java.math.BigDecimal.valueOf(a.ratio()));
            ps.setBigDecimal(7, a.todayUsd());
            ps.setBigDecimal(8, a.baselineUsd());
            ps.setString(9, channel);
            return ps;
        });
        return rows == 1;
    }
}
