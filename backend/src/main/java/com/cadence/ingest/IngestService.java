package com.cadence.ingest;

import com.cadence.common.ApiException;
import com.cadence.event.EventDto;
import com.cadence.security.AuthPrincipal;
import com.cadence.tenancy.Tenancy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;
import java.util.Set;

/**
 * Stores ingested events idempotently and enqueues categorisation jobs for
 * events that arrive without a category (consumed by P2-F).
 *
 * Privacy decision (P2-A.1 §4): events are stored RAW; redaction happens on read
 * (P2-A.7), not here.
 */
@Service
public class IngestService {

    private static final Set<String> SOURCES = Set.of("os", "vscode", "chrome", "token", "github");

    private final JdbcTemplate jdbc;
    private final Tenancy tenancy;
    private final ObjectMapper mapper;

    public IngestService(JdbcTemplate jdbc, Tenancy tenancy, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.tenancy = tenancy;
        this.mapper = mapper;
    }

    @Transactional
    public IngestResult ingest(AuthPrincipal principal, List<EventDto> events) {
        tenancy.bind(principal);

        int stored = 0;
        for (EventDto e : events) {
            if (!SOURCES.contains(e.source())) {
                throw ApiException.badRequest("Unknown event source: " + e.source());
            }
            String metaJson = writeMeta(e.meta());
            // org_id + member_id stamped from the JWT (never the body).
            int rows = jdbc.update("""
                    INSERT INTO events (event_id, org_id, member_id, schema_ver, source,
                                        ts_start, ts_end, duration_ms, app, title, url,
                                        project, category, is_idle, meta)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                    ON CONFLICT (event_id, ts_start) DO NOTHING
                    """,
                    e.eventId(), principal.orgId(), principal.memberId(), e.schemaVer(), e.source(),
                    Timestamp.from(e.tsStart().toInstant()), Timestamp.from(e.tsEnd().toInstant()),
                    e.durationMs(), e.app(), e.title(), e.url(), e.project(), e.category(),
                    e.isIdle(), metaJson);

            if (rows > 0) {
                stored++;
                if (e.category() == null) {
                    enqueueCategorize(principal, e);
                }
            }
        }
        return new IngestResult(events.size(), stored, events.size() - stored);
    }

    /** job_queue payload shape for P2-F: {"event_id","ts_start"} (see Coordination). */
    private void enqueueCategorize(AuthPrincipal principal, EventDto e) {
        String payload = """
                {"event_id":"%s","ts_start":"%s"}""".formatted(e.eventId(), e.tsStart());
        jdbc.update("""
                INSERT INTO job_queue (org_id, kind, payload)
                VALUES (?, 'categorize', ?::jsonb)
                """, principal.orgId(), payload);
    }

    private String writeMeta(JsonNode meta) {
        try {
            return (meta == null || meta.isNull()) ? "{}" : mapper.writeValueAsString(meta);
        } catch (Exception ex) {
            throw ApiException.badRequest("Invalid meta payload.");
        }
    }
}
