package com.cadence.insights.nlquery;

import com.cadence.common.ApiException;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Executes a VALIDATED SELECT against the {@code cadence_readonly} datasource
 * (P3-C, §5) — layers 1–3 of the defense model. Per query, in a single read-only
 * transaction:
 * <ul>
 *   <li>{@code SET LOCAL statement_timeout} — runaway guard;</li>
 *   <li>{@code set_config('app.current_org', …, true)} — binds RLS to the caller's
 *       org (transaction-local, can't leak across the pool);</li>
 *   <li>the SQL wrapped in {@code SELECT * FROM (…) LIMIT max+1} — the row cap is
 *       enforced regardless of any inner LIMIT/ORDER BY.</li>
 * </ul>
 * The SELECT-only role + read-only transaction make a write impossible two ways
 * over; RLS makes a cross-org read impossible. This class never touches the
 * owner/app datasource.
 */
@Component
@ConditionalOnProperty(prefix = "cadence.nlquery", name = "enabled", havingValue = "true")
class NlQueryExecutor {

    private static final Logger log = LoggerFactory.getLogger(NlQueryExecutor.class);

    /** Capped tabular result. {@code truncated} = more rows existed than the cap. */
    record ExecResult(List<String> columns, List<List<Object>> rows, boolean truncated) {}

    private final HikariDataSource dataSource;
    private final NlQueryProperties props;

    NlQueryExecutor(NlQueryProperties props) {
        this.props = props;
        // Own the readonly datasource privately (not a Spring DataSource bean — see
        // NlQueryConfig). Connects as cadence_readonly; never the owner/app role.
        HikariDataSource ds = new HikariDataSource();
        ds.setPoolName("cadence-nlquery-readonly");
        ds.setJdbcUrl(props.dbUrl());
        ds.setUsername(props.dbRole());          // cadence_readonly
        ds.setPassword(props.dbPassword());
        ds.setMaximumPoolSize(5);
        ds.setReadOnly(true);                     // belt: all connections read-only
        ds.setAutoCommit(false);                  // we manage the per-query txn
        this.dataSource = ds;
    }

    @PreDestroy
    void close() {
        dataSource.close();
    }

    ExecResult execute(String validatedSql, UUID orgId) {
        int cap = props.maxRows();
        // statement_timeout value is a plain integer (ms) — injection-safe to inline.
        String setTimeout = "SET LOCAL statement_timeout = " + props.statementTimeoutMs();
        String wrapped = "SELECT * FROM (" + validatedSql + ") AS _nlq LIMIT " + (cap + 1);

        Connection c = null;
        try {
            c = dataSource.getConnection();
            c.setReadOnly(true);
            c.setAutoCommit(false);

            try (Statement s = c.createStatement()) {
                s.execute(setTimeout);
            }
            // Bind the org for RLS (same door as cadence_app), transaction-local.
            try (PreparedStatement bind =
                         c.prepareStatement("SELECT set_config('app.current_org', ?, true)")) {
                bind.setString(1, orgId.toString());
                bind.executeQuery().close();
            }

            try (PreparedStatement ps = c.prepareStatement(wrapped)) {
                ps.setMaxRows(cap + 1);   // driver-side belt on top of the SQL LIMIT
                try (ResultSet rs = ps.executeQuery()) {
                    ResultSetMetaData md = rs.getMetaData();
                    int n = md.getColumnCount();
                    List<String> columns = new ArrayList<>(n);
                    for (int i = 1; i <= n; i++) {
                        columns.add(md.getColumnLabel(i));
                    }
                    List<List<Object>> rows = new ArrayList<>();
                    while (rs.next()) {
                        List<Object> row = new ArrayList<>(n);
                        for (int i = 1; i <= n; i++) {
                            row.add(rs.getObject(i));
                        }
                        rows.add(row);
                    }
                    boolean truncated = rows.size() > cap;
                    if (truncated) {
                        rows = new ArrayList<>(rows.subList(0, cap));
                    }
                    c.commit();
                    return new ExecResult(columns, rows, truncated);
                }
            }
        } catch (Exception e) {
            rollbackQuietly(c);
            log.warn("nlquery execution failed: {}", e.toString());
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Unprocessable Entity",
                    "The query could not be executed against the data.");
        } finally {
            closeQuietly(c);
        }
    }

    private static void rollbackQuietly(Connection c) {
        if (c != null) {
            try { c.rollback(); } catch (Exception ignored) { /* best-effort */ }
        }
    }

    private static void closeQuietly(Connection c) {
        if (c != null) {
            try { c.close(); } catch (Exception ignored) { /* best-effort */ }
        }
    }
}
