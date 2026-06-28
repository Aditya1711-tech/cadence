package com.cadence.github;

import com.cadence.common.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.UUID;

/**
 * JDBC {@link GithubEventStore}. Writes Event-Contract rows for {@code
 * source='github'} using the same idempotent insert shape as the ingest path
 * (P2-A.4): zero-duration, {@code url} null, {@code schema_ver = 1}. The org
 * context must already be bound by the caller's transaction.
 */
@Repository
public class JdbcGithubEventStore implements GithubEventStore {

    /** Mirrors the Event Contract SchemaVersion (§5, currently 1). */
    private static final int SCHEMA_VER = 1;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcGithubEventStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public int insert(UUID orgId, UUID memberId, GithubEventDraft d) {
        Timestamp ts = Timestamp.from(d.tsStart().toInstant());
        return jdbc.update("""
                INSERT INTO events (event_id, org_id, member_id, schema_ver, source,
                                    ts_start, ts_end, duration_ms, app, title, url,
                                    project, category, is_idle, meta)
                VALUES (?, ?, ?, ?, 'github', ?, ?, 0, ?, ?, NULL, ?, ?, false, ?::jsonb)
                ON CONFLICT (event_id, ts_start) DO NOTHING
                """,
                d.eventId(), orgId, memberId, SCHEMA_VER,
                ts, ts, d.app(), d.title(), d.project(), d.category(),
                writeMeta(d.meta()));
    }

    private String writeMeta(Object meta) {
        try {
            return meta == null ? "{}" : mapper.writeValueAsString(meta);
        } catch (Exception e) {
            throw ApiException.badRequest("Invalid github event meta.");
        }
    }
}
