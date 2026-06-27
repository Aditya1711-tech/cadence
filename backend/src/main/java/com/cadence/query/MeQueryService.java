package com.cadence.query;

import com.cadence.event.EventDto;
import com.cadence.security.AuthPrincipal;
import com.cadence.tenancy.Tenancy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

/** Personal read endpoints (P2-A.5): /me/timeline, /me/summary. */
@Service
public class MeQueryService {

    private final JdbcTemplate jdbc;
    private final Tenancy tenancy;
    private final EventRowMapper eventMapper;

    public MeQueryService(JdbcTemplate jdbc, Tenancy tenancy, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.tenancy = tenancy;
        this.eventMapper = new EventRowMapper(mapper);
    }

    @Transactional(readOnly = true)
    public Summaries.TimelineResponse timeline(AuthPrincipal p, OffsetDateTime from,
                                               OffsetDateTime to, String cursor, int limit) {
        tenancy.bind(p);
        int lim = Math.min(Math.max(limit, 1), 1000);

        StringBuilder sql = new StringBuilder("""
                SELECT event_id, schema_ver, source, member_id, ts_start, ts_end,
                       duration_ms, app, title, url, project, category, is_idle, meta
                FROM events
                WHERE org_id = ? AND member_id = ? AND ts_start >= ? AND ts_start < ?
                """);
        List<Object> args = new ArrayList<>(List.of(
                p.orgId(), p.memberId(),
                Timestamp.from(from.toInstant()), Timestamp.from(to.toInstant())));
        Cursor c = Cursor.decode(cursor);
        if (c != null) {
            sql.append(" AND (ts_start < ? OR (ts_start = ? AND event_id < ?))");
            args.add(Timestamp.from(c.ts())); args.add(Timestamp.from(c.ts())); args.add(c.id());
        }
        sql.append(" ORDER BY ts_start DESC, event_id DESC LIMIT ?");
        args.add(lim + 1);

        List<EventDto> rows = jdbc.query(sql.toString(), eventMapper, args.toArray());
        String next = null;
        if (rows.size() > lim) {
            EventDto last = rows.get(lim - 1);
            rows = rows.subList(0, lim);
            next = new Cursor(last.tsStart().toInstant(), last.eventId()).encode();
        }
        return new Summaries.TimelineResponse(rows, next);
    }

    @Transactional(readOnly = true)
    public Summaries.Summary summary(AuthPrincipal p, OffsetDateTime from, OffsetDateTime to) {
        tenancy.bind(p);
        Timestamp f = Timestamp.from(from.toInstant());
        Timestamp t = Timestamp.from(to.toInstant());

        List<Summaries.CategoryBucket> byCat = jdbc.query("""
                SELECT coalesce(category,'uncategorized') AS category,
                       sum(duration_ms) AS total_ms, count(*) AS n
                FROM events
                WHERE org_id = ? AND member_id = ? AND ts_start >= ? AND ts_start < ?
                GROUP BY 1 ORDER BY total_ms DESC
                """, (rs, i) -> new Summaries.CategoryBucket(
                        rs.getString("category"), rs.getLong("total_ms"), rs.getLong("n")),
                p.orgId(), p.memberId(), f, t);

        List<Summaries.DayBucket> byDay = QuerySupport.byDay(jdbc, """
                SELECT (ts_start AT TIME ZONE 'UTC')::date AS day,
                       coalesce(category,'uncategorized') AS category,
                       sum(duration_ms) AS total_ms, count(*) AS n
                FROM events
                WHERE org_id = ? AND member_id = ? AND ts_start >= ? AND ts_start < ?
                GROUP BY 1,2 ORDER BY 1
                """, p.orgId(), p.memberId(), f, t);

        Summaries.TokenSummary tokens = QuerySupport.tokenSummary(jdbc, """
                SELECT meta->>'model' AS model,
                       sum((meta->>'cost_usd')::numeric)   AS cost_usd,
                       sum((meta->>'tokens_in')::numeric)  AS tokens_in,
                       sum((meta->>'tokens_out')::numeric) AS tokens_out
                FROM events
                WHERE source='token' AND org_id = ? AND member_id = ? AND ts_start >= ? AND ts_start < ?
                GROUP BY 1
                """, p.orgId(), p.memberId(), f, t);

        return new Summaries.Summary(from, to, byCat, byDay, tokens);
    }

    /** Opaque keyset cursor: base64("epochMillis|uuid"). */
    record Cursor(java.time.Instant ts, UUID id) {
        String encode() {
            String raw = ts.toEpochMilli() + "|" + id;
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }
        static Cursor decode(String cursor) {
            if (cursor == null || cursor.isBlank()) return null;
            try {
                String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
                int bar = raw.indexOf('|');
                return new Cursor(java.time.Instant.ofEpochMilli(Long.parseLong(raw.substring(0, bar))),
                        UUID.fromString(raw.substring(bar + 1)));
            } catch (Exception e) {
                return null;
            }
        }
    }
}
