package com.cadence.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * All DB access for the categorisation worker. Each method that touches an
 * org-scoped table runs in its own transaction and binds {@code app.current_org}
 * first, so Postgres RLS applies; every query also filters by org_id explicitly
 * (defence-in-depth, matching the query layer). The LLM call deliberately happens
 * <em>between</em> these short transactions (in {@link JobProcessor}) so a pooled
 * connection is never held across the network round-trip.
 *
 * <p><b>Claiming is cross-org</b> and therefore cannot run under the RLS-scoped
 * app role with a single org bound. It calls {@code claim_categorize_jobs(...)},
 * a SECURITY DEFINER function owned by the schema owner (requested from P2-A via a
 * NEEDS line — see PROGRESS Coordination).
 */
@Repository
@ConditionalOnProperty(prefix = "cadence.categorize", name = "enabled", havingValue = "true")
class CategorizeJobStore implements JobStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    CategorizeJobStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /**
     * Atomically claim up to {@code limit} pending (or stale-running) categorize
     * jobs across all orgs via the SECURITY DEFINER function. Not transactional —
     * the function performs one atomic UPDATE ... RETURNING.
     */
    @Override
    public List<ClaimedJob> claimBatch(String lockedBy, int limit) {
        return jdbc.query(
                "SELECT id, org_id, payload, attempts FROM claim_categorize_jobs(?, ?)",
                (rs, i) -> {
                    JsonNode p = readPayload(rs.getString("payload"));
                    return new ClaimedJob(
                            UUID.fromString(rs.getString("id")),
                            UUID.fromString(rs.getString("org_id")),
                            UUID.fromString(p.get("event_id").asText()),
                            OffsetDateTime.parse(p.get("ts_start").asText()),
                            rs.getInt("attempts"));
                },
                lockedBy, limit);
    }

    @Override
    @Transactional
    public Optional<EventRow> loadSignals(UUID eventId, OffsetDateTime tsStart, UUID orgId) {
        bindOrg(orgId);
        List<EventRow> rows = jdbc.query("""
                        SELECT source, app, title, url, project, is_idle, duration_ms, category
                        FROM events WHERE event_id = ? AND ts_start = ? AND org_id = ?
                        """,
                (rs, i) -> new EventRow(
                        new EventSignals(
                                rs.getString("source"),
                                rs.getString("app"),
                                rs.getString("title"),
                                rs.getString("url"),
                                rs.getString("project"),
                                rs.getBoolean("is_idle"),
                                rs.getLong("duration_ms")),
                        rs.getString("category")),
                eventId, ts(tsStart), orgId);
        return rows.stream().findFirst();
    }

    /** Write the category (only if still null/other) and mark the job done, in one txn. */
    @Override
    @Transactional
    public void writeAndComplete(UUID jobId, UUID eventId, OffsetDateTime tsStart, UUID orgId, Category category) {
        bindOrg(orgId);
        jdbc.update("""
                        UPDATE events SET category = ?
                        WHERE event_id = ? AND ts_start = ? AND org_id = ?
                          AND (category IS NULL OR category = 'other')
                        """,
                category.name(), eventId, ts(tsStart), orgId);
        markDoneInTx(jobId, orgId);
    }

    @Override
    @Transactional
    public void complete(UUID jobId, UUID orgId) {
        bindOrg(orgId);
        markDoneInTx(jobId, orgId);
    }

    @Override
    @Transactional
    public void defer(UUID jobId, UUID orgId, long backoffSeconds) {
        bindOrg(orgId);
        jdbc.update("""
                        UPDATE job_queue
                        SET status = 'pending', locked_by = NULL, locked_at = NULL,
                            run_after = now() + (? * interval '1 second')
                        WHERE id = ? AND org_id = ?
                        """,
                backoffSeconds, jobId, orgId);
    }

    @Override
    @Transactional
    public void fail(UUID jobId, UUID orgId) {
        bindOrg(orgId);
        jdbc.update("UPDATE job_queue SET status = 'failed' WHERE id = ? AND org_id = ?", jobId, orgId);
    }

    private void markDoneInTx(UUID jobId, UUID orgId) {
        jdbc.update("UPDATE job_queue SET status = 'done' WHERE id = ? AND org_id = ?", jobId, orgId);
    }

    private void bindOrg(UUID orgId) {
        // set_config(..., is_local=true) → scoped to this transaction's connection.
        jdbc.queryForObject("SELECT set_config('app.current_org', ?, true)", String.class, orgId.toString());
    }

    private JsonNode readPayload(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("unparseable job payload: " + json, e);
        }
    }

    private static Timestamp ts(OffsetDateTime t) {
        return Timestamp.from(t.toInstant());
    }
}
