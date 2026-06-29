package com.cadence.insights.budget;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Reads token-burn facts from the {@code events_daily_tokens} continuous
 * aggregate (the frozen token fact — §6/§7). Like {@code TokenQueryService}, the
 * CAGG is a separate hypertable not covered by base-table RLS, so the explicit
 * {@code org_id} filter is the real tenant guard. The org-listing scan is
 * deliberately CROSS-ORG (the monitor spans tenants, like the P2-F worker), which
 * is why it does not bind a single org.
 */
@Component
class BudgetMetricsStore {

    private final JdbcTemplate jdbc;

    BudgetMetricsStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Orgs with any token spend in [from, toExclusive) — the work-list for a run. */
    @Transactional(readOnly = true)
    List<UUID> activeOrgIds(LocalDate from, LocalDate toExclusive) {
        return jdbc.query("""
                SELECT DISTINCT org_id FROM events_daily_tokens
                WHERE bucket >= ? AND bucket < ?
                """, (rs, i) -> rs.getObject("org_id", UUID.class), day(from), day(toExclusive));
    }

    String orgName(UUID orgId) {
        List<String> n = jdbc.query("SELECT name FROM orgs WHERE id = ?",
                (rs, i) -> rs.getString("name"), orgId);
        return n.isEmpty() ? "Your org" : n.get(0);
    }

    /** All (member, day, model) burn cells for one org over the window. */
    @Transactional(readOnly = true)
    List<TokenCell> cells(UUID orgId, LocalDate from, LocalDate toExclusive) {
        return jdbc.query("""
                SELECT t.member_id, m.display_name, t.bucket::date AS day, t.model,
                       sum(t.cost_usd) AS cost_usd
                FROM events_daily_tokens t
                JOIN members m ON m.id = t.member_id
                WHERE t.org_id = ? AND t.bucket >= ? AND t.bucket < ?
                GROUP BY t.member_id, m.display_name, t.bucket::date, t.model
                """, BudgetMetricsStore::mapCell, orgId, day(from), day(toExclusive));
    }

    private static TokenCell mapCell(ResultSet rs, int i) throws SQLException {
        var cost = rs.getBigDecimal("cost_usd");
        String name = rs.getString("display_name");
        return new TokenCell(
                rs.getObject("member_id", UUID.class),
                name == null ? "A teammate" : name,
                rs.getObject("day", LocalDate.class),
                rs.getString("model"),
                cost == null ? 0.0 : cost.doubleValue());
    }

    private static Timestamp day(LocalDate d) {
        return Timestamp.from(OffsetDateTime.of(d.atStartOfDay(), ZoneOffset.UTC).toInstant());
    }
}
