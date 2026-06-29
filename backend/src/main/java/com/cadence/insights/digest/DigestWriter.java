package com.cadence.insights.digest;

import com.cadence.insights.IsoWeek;
import com.cadence.insights.digest.DigestNarrator.Narration;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Persists a computed digest: upserts the aggregated-fact row into {@code
 * insights} and the narrated/rendered row into {@code digests} (P3-A.5). Idempotent
 * on (org, member/grain, iso_week) via the partial unique indexes — a re-run of
 * the same week overwrites in place. Callers run these inside a tenancy-bound
 * transaction.
 */
@Component
@ConditionalOnProperty(prefix = "cadence.digest", name = "enabled", havingValue = "true")
class DigestWriter {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    DigestWriter(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** Upsert facts + the rendered (not-yet-delivered) digest. {@code memberId} null ⇒ org grain. */
    void writeRendered(String grain, UUID orgId, UUID memberId, IsoWeek week,
                       String factsJson, DigestHeadline h, Narration nar, String cardSvg) {
        Timestamp start = Timestamp.from(week.start().toInstant());
        Timestamp end = Timestamp.from(week.end().toInstant());

        String insightConflict = "member".equals(grain)
                ? "(org_id, member_id, iso_week) WHERE grain = 'member'"
                : "(org_id, iso_week) WHERE grain = 'org'";
        UUID insightId = jdbc.queryForObject("""
                INSERT INTO insights (org_id, member_id, grain, iso_week, period_start, period_end,
                                      deep_work_h, meeting_h, token_cost_usd, commits,
                                      fragmentation_index, facts)
                VALUES (?,?,?,?,?,?,?,?,?,?,?, ?::jsonb)
                ON CONFLICT """ + insightConflict + """
                DO UPDATE SET period_start=EXCLUDED.period_start, period_end=EXCLUDED.period_end,
                              deep_work_h=EXCLUDED.deep_work_h, meeting_h=EXCLUDED.meeting_h,
                              token_cost_usd=EXCLUDED.token_cost_usd, commits=EXCLUDED.commits,
                              fragmentation_index=EXCLUDED.fragmentation_index, facts=EXCLUDED.facts
                RETURNING id
                """, UUID.class,
                orgId, memberId, grain, week.label(), start, end,
                h.deepWorkH(), h.meetingH(), h.tokenCostUsd(), h.commits(),
                h.fragmentationIndex(), factsJson);

        String digestConflict = "member".equals(grain)
                ? "(org_id, member_id, iso_week) WHERE grain = 'member'"
                : "(org_id, iso_week) WHERE grain = 'org'";
        jdbc.update("""
                INSERT INTO digests (org_id, member_id, insight_id, grain, iso_week,
                                     narrative, spotted, card_svg, channel, status)
                VALUES (?,?,?,?,?,?, ?::jsonb, ?, 'email', 'rendered')
                ON CONFLICT """ + digestConflict + """
                DO UPDATE SET insight_id=EXCLUDED.insight_id, narrative=EXCLUDED.narrative,
                              spotted=EXCLUDED.spotted, card_svg=EXCLUDED.card_svg,
                              channel=EXCLUDED.channel, status='rendered', sent_at=NULL
                """,
                orgId, memberId, insightId, grain, week.label(),
                nar.narrative(), toJson(nar), cardSvg);
    }

    /** Flip a rendered digest to sent/failed after the delivery attempt. */
    void markDelivered(String grain, UUID orgId, UUID memberId, IsoWeek week, boolean ok) {
        String memberFilter = memberId == null ? "member_id IS NULL" : "member_id = ?";
        String sql = "UPDATE digests SET status = ?, sent_at = ? "
                + "WHERE org_id = ? AND iso_week = ? AND grain = ? AND " + memberFilter;
        OffsetDateTime now = OffsetDateTime.now();
        if (memberId == null) {
            jdbc.update(sql, ok ? "sent" : "failed", ok ? Timestamp.from(now.toInstant()) : null,
                    orgId, week.label(), grain);
        } else {
            jdbc.update(sql, ok ? "sent" : "failed", ok ? Timestamp.from(now.toInstant()) : null,
                    orgId, week.label(), grain, memberId);
        }
    }

    private String toJson(Narration nar) {
        try {
            return mapper.writeValueAsString(nar.spotted() == null ? java.util.List.of() : nar.spotted());
        } catch (Exception e) {
            return "[]";
        }
    }
}
