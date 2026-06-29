package com.cadence.insights.budget;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.UUID;

/**
 * Loads the effective {@link OrgBudgetConfig} for an org from
 * {@code budget_alert_config}, falling back to env defaults
 * ({@link BudgetProperties}) when there is no row. The table is owned by the
 * P3-A spine (NEEDS P3-E→P3-A); until the migration lands the read fails, and we
 * DEGRADE GRACEFULLY: every org runs on the env defaults (the monitor still
 * works, just without per-org overrides). We warn once, not per org per run.
 */
@Component
class BudgetConfigStore {

    private static final Logger log = LoggerFactory.getLogger(BudgetConfigStore.class);

    private final JdbcTemplate jdbc;
    private final BudgetProperties props;
    private final AtomicBoolean warnedMissing = new AtomicBoolean(false);

    BudgetConfigStore(JdbcTemplate jdbc, BudgetProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    OrgBudgetConfig load(UUID orgId) {
        try {
            return jdbc.query("""
                    SELECT enabled, spike_multiplier, min_absolute_usd, baseline_days,
                           min_history_days, tiers, channel, slack_webhook_url, alert_email,
                           quiet_hours_start, quiet_hours_end, timezone, mute_until
                    FROM budget_alert_config WHERE org_id = ?
                    """, rs -> rs.next() ? map(orgId, rs) : OrgBudgetConfig.defaults(orgId, props), orgId);
        } catch (DataAccessException e) {
            if (warnedMissing.compareAndSet(false, true)) {
                log.warn("budget_alert_config unavailable; using env defaults for all orgs "
                        + "(is the P3-E migration installed? NEEDS P3-E->P3-A): {}", e.toString());
            }
            return OrgBudgetConfig.defaults(orgId, props);
        }
    }

    private OrgBudgetConfig map(UUID orgId, ResultSet rs) throws SQLException {
        return new OrgBudgetConfig(
                orgId,
                rs.getBoolean("enabled"),
                nz(rs.getBigDecimal("spike_multiplier"), props.spikeMultiplier()),
                orDefault(rs.getBigDecimal("min_absolute_usd"), props.minAbsoluteUsd()),
                nzInt(rs.getObject("baseline_days"), props.baselineDays()),
                nzInt(rs.getObject("min_history_days"), props.minHistoryDays()),
                tiers(rs.getArray("tiers")),
                orText(rs.getString("channel"), "email"),
                rs.getString("slack_webhook_url"),
                rs.getString("alert_email"),
                (Integer) rs.getObject("quiet_hours_start"),
                (Integer) rs.getObject("quiet_hours_end"),
                orText(rs.getString("timezone"), "UTC"),
                ts(rs.getTimestamp("mute_until")));
    }

    private int[] tiers(Array arr) throws SQLException {
        if (arr == null) {
            return props.tiers();
        }
        Object raw = arr.getArray();
        if (raw instanceof Integer[] boxed && boxed.length > 0) {
            int[] out = new int[boxed.length];
            for (int i = 0; i < boxed.length; i++) {
                out[i] = boxed[i];
            }
            return out;
        }
        return props.tiers();
    }

    private static double nz(BigDecimal v, double dflt) {
        return v == null ? dflt : v.doubleValue();
    }

    private static BigDecimal orDefault(BigDecimal v, BigDecimal dflt) {
        return v == null ? dflt : v;
    }

    private static int nzInt(Object v, int dflt) {
        return v == null ? dflt : ((Number) v).intValue();
    }

    private static String orText(String v, String dflt) {
        return (v == null || v.isBlank()) ? dflt : v;
    }

    private static java.time.Instant ts(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
