package com.cadence.insights;

import com.cadence.insights.InsightFacts.Deltas;
import com.cadence.insights.InsightFacts.Fragmentation;
import com.cadence.insights.InsightFacts.MemberWeekFacts;
import com.cadence.insights.InsightFacts.OrgWeekFacts;
import com.cadence.insights.InsightFacts.PeakBlock;
import com.cadence.insights.InsightFacts.Period;
import com.cadence.insights.InsightFacts.TopContributor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The pre-aggregation query layer (P3-A.3/.4): builds the frozen
 * {@link InsightFacts} shapes from SQL over the Phase-2 warehouse. EVERY number
 * the digest cites originates here — the LLM never sees raw events.
 *
 * <p>Reads raw {@code events} directly (same as MeQueryService/OrgQueryService),
 * not the continuous aggregates, so facts are correct on a fresh box where the
 * CAGGs may not have materialized yet; the CAGGs remain available to P3-B for
 * heavier time-series work. Callers bind the org context (RLS) before invoking.
 */
@Service
public class InsightsAggregationService {

    /** ms per hour, for the ms→hours conversion used across the fact shape. */
    private static final double MS_PER_HOUR = 3_600_000.0;

    private final JdbcTemplate jdbc;
    /** ≥SATURATION project switches per focused hour ⇒ fragmentation_index = 100 (P3-A.1 §3.3). */
    private final double saturation;

    public InsightsAggregationService(
            JdbcTemplate jdbc,
            @Value("${cadence.insights.fragmentation-saturation:4.0}") double saturation) {
        this.jdbc = jdbc;
        this.saturation = saturation <= 0 ? 4.0 : saturation;
    }

    // ── Member grain ─────────────────────────────────────────────────────────

    public MemberWeekFacts buildMember(UUID orgId, UUID memberId, String displayName, IsoWeek week) {
        Timestamp f = ts(week.start());
        Timestamp t = ts(week.end());

        Map<String, Double> byCat = byCategoryHours(memberScope(orgId, memberId), f, t);
        Tokens tok = tokens(memberScope(orgId, memberId), f, t);
        long commits = commits(memberScope(orgId, memberId), f, t);
        FragRaw frag = fragmentation(memberScope(orgId, memberId), f, t);
        PeakBlock peak = peakBlock(memberScope(orgId, memberId), f, t);

        int historyWeeks = week.historyWeeksSince(firstEvent(memberScope(orgId, memberId)));
        Deltas deltas = historyWeeks < 4 ? null : memberDeltas(orgId, memberId, week, byCat, tok, commits, frag);

        return new MemberWeekFacts(
                orgId, memberId, displayName, "member",
                new Period(week.start(), week.end(), week.label()),
                byCat.getOrDefault("deep_work", 0.0), byCat.getOrDefault("meetings", 0.0),
                tok.cost(), commits, frag.index(),
                byCat, tok.in(), tok.out(),
                frag.toFragmentation(), peak,
                deltas, historyWeeks, historyWeeks < 4);
    }

    private Deltas memberDeltas(UUID orgId, UUID memberId, IsoWeek week, Map<String, Double> cur,
                                Tokens curTok, long curCommits, FragRaw curFrag) {
        Timestamp pf = ts(week.start().minusWeeks(4));
        Timestamp pt = ts(week.start());
        Scope s = memberScope(orgId, memberId);
        return deltasOver(cur, curTok, curCommits, curFrag, s, pf, pt);
    }

    // ── Org grain ────────────────────────────────────────────────────────────

    public OrgWeekFacts buildOrg(UUID orgId, boolean includeMembers, IsoWeek week) {
        Timestamp f = ts(week.start());
        Timestamp t = ts(week.end());
        Scope s = orgScope(orgId);

        Map<String, Double> byCat = byCategoryHours(s, f, t);
        Tokens tok = tokens(s, f, t);
        long commits = commits(s, f, t);
        FragRaw frag = orgFragmentation(orgId, f, t);
        PeakBlock peak = peakBlock(s, f, t);
        int activeMembers = jdbc.queryForObject(
                "SELECT count(DISTINCT member_id) FROM events WHERE org_id=? AND ts_start>=? AND ts_start<?",
                Integer.class, orgId, f, t);

        List<TopContributor> top = includeMembers ? topContributors(orgId, f, t) : null;

        int historyWeeks = week.historyWeeksSince(firstEvent(s));
        Deltas deltas = historyWeeks < 4 ? null :
                deltasOver(byCat, tok, commits, frag, s, ts(week.start().minusWeeks(4)), ts(week.start()),
                        true, orgId);

        return new OrgWeekFacts(
                orgId, "org", new Period(week.start(), week.end(), week.label()), activeMembers,
                byCat.getOrDefault("deep_work", 0.0), byCat.getOrDefault("meetings", 0.0),
                tok.cost(), commits, frag.index(),
                byCat, tok.in(), tok.out(), top, peak, deltas);
    }

    private List<TopContributor> topContributors(UUID orgId, Timestamp f, Timestamp t) {
        return jdbc.query("""
                SELECT e.member_id, m.display_name,
                       count(*) FILTER (WHERE e.source='github' AND e.meta->>'commit_sha' IS NOT NULL) AS commits,
                       coalesce(sum(e.duration_ms) FILTER (
                           WHERE e.category='deep_work' AND e.is_idle = false), 0) AS deep_ms
                FROM events e JOIN members m ON m.id = e.member_id
                WHERE e.org_id=? AND e.ts_start>=? AND e.ts_start<?
                GROUP BY e.member_id, m.display_name
                ORDER BY commits DESC, deep_ms DESC
                LIMIT 5
                """, (rs, i) -> new TopContributor(
                        rs.getObject("member_id", UUID.class), rs.getString("display_name"),
                        rs.getLong("commits"), round1(rs.getLong("deep_ms") / MS_PER_HOUR)),
                orgId, f, t);
    }

    // ── Shared scalar queries (member- or org-scoped) ─────────────────────────

    /** Hours by category; the 8 canonical keys are always present (0 if absent). null category folds into other. */
    private Map<String, Double> byCategoryHours(Scope s, Timestamp f, Timestamp t) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (String c : InsightFacts.CATEGORIES) out.put(c, 0.0);
        jdbc.query("SELECT coalesce(category,'other') AS category, sum(duration_ms) AS ms "
                        + "FROM events WHERE " + s.where + " AND ts_start>=? AND ts_start<? GROUP BY 1",
                rs -> {
                    String c = rs.getString("category");
                    String key = out.containsKey(c) ? c : "other";
                    out.merge(key, round1(rs.getLong("ms") / MS_PER_HOUR), Double::sum);
                }, s.args(f, t));
        return out;
    }

    private Tokens tokens(Scope s, Timestamp f, Timestamp t) {
        return jdbc.queryForObject("""
                SELECT coalesce(sum((meta->>'cost_usd')::numeric),0)   AS cost,
                       coalesce(sum((meta->>'tokens_in')::numeric),0)  AS tin,
                       coalesce(sum((meta->>'tokens_out')::numeric),0) AS tout
                FROM events WHERE source='token' AND """ + s.where + " AND ts_start>=? AND ts_start<?",
                (rs, i) -> new Tokens(
                        rs.getBigDecimal("cost").setScale(2, RoundingMode.HALF_UP),
                        rs.getLong("tin"), rs.getLong("tout")),
                s.args(f, t));
    }

    private long commits(Scope s, Timestamp f, Timestamp t) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM events WHERE " + s.where
                        + " AND source='github' AND meta->>'commit_sha' IS NOT NULL "
                        + "AND ts_start>=? AND ts_start<?",
                Long.class, s.args(f, t));
        return n == null ? 0 : n;
    }

    private OffsetDateTime firstEvent(Scope s) {
        return jdbc.queryForObject("SELECT min(ts_start) FROM events WHERE " + s.where,
                (rs, i) -> rs.getObject("min", OffsetDateTime.class), s.args());
    }

    /** Single-member fragmentation (P3-A.1 §3.2). */
    private FragRaw fragmentation(Scope s, Timestamp f, Timestamp t) {
        return jdbc.queryForObject("""
                WITH focus AS (
                  SELECT ts_start, ts_end, duration_ms, project,
                         lag(project) OVER w AS prev_project,
                         lag(ts_end)  OVER w AS prev_end
                  FROM events
                  WHERE """ + s.where + """
                     AND ts_start>=? AND ts_start<? AND is_idle = false
                     AND category IN ('deep_work','code_review','ai_assisted','research')
                  WINDOW w AS (ORDER BY ts_start)
                )
                SELECT
                  count(*) FILTER (WHERE prev_end IS NOT NULL
                                     AND project IS DISTINCT FROM prev_project
                                     AND ts_start - prev_end <= interval '30 minutes') AS switches,
                  coalesce(sum(duration_ms),0) AS focus_ms,
                  count(*) FILTER (WHERE prev_end IS NULL
                                     OR ts_start - prev_end > interval '30 minutes')   AS sessions
                FROM focus
                """, (rs, i) -> frag(rs.getLong("switches"), rs.getLong("focus_ms"), rs.getLong("sessions")),
                s.args(f, t));
    }

    /** Org fragmentation = focus-hour-weighted mean of per-member indices (P3-A.1 §3.4). */
    private FragRaw orgFragmentation(UUID orgId, Timestamp f, Timestamp t) {
        List<long[]> perMember = jdbc.query("""
                WITH focus AS (
                  SELECT member_id, ts_start, ts_end, duration_ms, project,
                         lag(project) OVER w AS prev_project,
                         lag(ts_end)  OVER w AS prev_end
                  FROM events
                  WHERE org_id=? AND ts_start>=? AND ts_start<? AND is_idle = false
                    AND category IN ('deep_work','code_review','ai_assisted','research')
                  WINDOW w AS (PARTITION BY member_id ORDER BY ts_start)
                )
                SELECT member_id,
                  count(*) FILTER (WHERE prev_end IS NOT NULL
                                     AND project IS DISTINCT FROM prev_project
                                     AND ts_start - prev_end <= interval '30 minutes') AS switches,
                  coalesce(sum(duration_ms),0) AS focus_ms
                FROM focus GROUP BY member_id
                """, (rs, i) -> new long[]{rs.getLong("switches"), rs.getLong("focus_ms")}, orgId, f, t);

        double weightedSum = 0, weight = 0, totalSwitches = 0, totalFocusMs = 0, totalSessions = 0;
        for (long[] r : perMember) {
            long switches = r[0];
            double focusH = r[1] / MS_PER_HOUR;
            if (focusH <= 0) continue;
            double idx = indexFrom(switches / focusH, saturation);
            weightedSum += idx * focusH;
            weight += focusH;
            totalSwitches += switches;
            totalFocusMs += r[1];
        }
        Integer index = weight <= 0 ? null : (int) Math.round(weightedSum / weight);
        double switchesPerH = totalFocusMs > 0 ? totalSwitches / (totalFocusMs / MS_PER_HOUR) : 0;
        return new FragRaw(index, (long) totalSwitches, round2(switchesPerH), 0);
    }

    private PeakBlock peakBlock(Scope s, Timestamp f, Timestamp t) {
        List<PeakBlock> top = jdbc.query("""
                SELECT extract(isodow from (ts_start AT TIME ZONE 'UTC'))::int AS dow,
                       extract(hour   from (ts_start AT TIME ZONE 'UTC'))::int AS hour,
                       category, sum(duration_ms) AS ms
                FROM events
                WHERE """ + s.where + """
                   AND ts_start>=? AND ts_start<? AND is_idle = false
                   AND category IN ('deep_work','code_review','ai_assisted','research')
                GROUP BY 1,2,3 ORDER BY ms DESC LIMIT 1
                """, (rs, i) -> new PeakBlock(
                        DayOfWeek.of(rs.getInt("dow")).getDisplayName(
                                java.time.format.TextStyle.SHORT, java.util.Locale.US),
                        rs.getInt("hour"), rs.getString("category"), rs.getLong("ms")),
                s.args(f, t));
        return top.isEmpty() ? null : top.get(0);
    }

    // ── Deltas ─────────────────────────────────────────────────────────────

    private Deltas deltasOver(Map<String, Double> cur, Tokens curTok, long curCommits, FragRaw curFrag,
                              Scope s, Timestamp pf, Timestamp pt) {
        return deltasOver(cur, curTok, curCommits, curFrag, s, pf, pt, false, null);
    }

    /** delta = current − (trailing 28-day total / 4). Fragmentation delta is current index − prior-window index. */
    private Deltas deltasOver(Map<String, Double> cur, Tokens curTok, long curCommits, FragRaw curFrag,
                              Scope s, Timestamp pf, Timestamp pt, boolean org, UUID orgId) {
        Map<String, Double> prior = byCategoryHours(s, pf, pt);
        Tokens priorTok = tokens(s, pf, pt);
        long priorCommits = commits(s, pf, pt);
        FragRaw priorFrag = org ? orgFragmentation(orgId, pf, pt) : fragmentation(s, pf, pt);

        double curIdx = curFrag.index() == null ? 0 : curFrag.index();
        double priorIdx = priorFrag.index() == null ? 0 : priorFrag.index();
        return new Deltas(
                round1(cur.getOrDefault("deep_work", 0.0) - prior.getOrDefault("deep_work", 0.0) / 4),
                round1(cur.getOrDefault("meetings", 0.0) - prior.getOrDefault("meetings", 0.0) / 4),
                curTok.cost().subtract(priorTok.cost().divide(BigDecimal.valueOf(4), 2, RoundingMode.HALF_UP)),
                curCommits - Math.round(priorCommits / 4.0),
                (int) Math.round(curIdx - priorIdx));
    }

    // ── helpers / small carriers ─────────────────────────────────────────────

    /**
     * The fragmentation index curve (P3-A.1 §3.3): 0..100, saturating at
     * {@code saturation} project switches per focused hour. Package-static so the
     * shape of the curve is unit-testable without a database.
     */
    static double indexFrom(double switchesPerFocusH, double saturation) {
        return Math.min(1.0, switchesPerFocusH / saturation) * 100.0;
    }

    private FragRaw frag(long switches, long focusMs, long sessions) {
        double focusH = focusMs / MS_PER_HOUR;
        double switchesPerH = focusH > 0 ? switches / focusH : 0;
        double meanSessionMin = sessions > 0 ? (focusMs / 60_000.0) / sessions : 0;
        Integer index = focusMs > 0 ? (int) Math.round(indexFrom(switchesPerH, saturation)) : null;
        return new FragRaw(index, switches, round2(switchesPerH), round2(meanSessionMin));
    }

    private static Timestamp ts(OffsetDateTime odt) {
        return Timestamp.from(odt.toInstant());
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** A scoped WHERE fragment + its leading bind args (org-only or org+member). */
    private record Scope(String where, Object[] lead) {
        Object[] args(Object... rest) {
            Object[] a = new Object[lead.length + rest.length];
            System.arraycopy(lead, 0, a, 0, lead.length);
            System.arraycopy(rest, 0, a, lead.length, rest.length);
            return a;
        }
    }

    private static Scope memberScope(UUID orgId, UUID memberId) {
        return new Scope("org_id=? AND member_id=?", new Object[]{orgId, memberId});
    }

    private static Scope orgScope(UUID orgId) {
        return new Scope("org_id=?", new Object[]{orgId});
    }

    private record Tokens(BigDecimal cost, long in, long out) {}

    /** Raw fragmentation result + the derived index; converts to the wire {@link Fragmentation}. */
    private record FragRaw(Integer index, long switches, double switchesPerFocusH, double meanSessionMin) {
        Fragmentation toFragmentation() {
            return new Fragmentation(switches, switchesPerFocusH, meanSessionMin);
        }
    }
}
