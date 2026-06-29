package com.cadence.insights.pattern;

import com.cadence.common.ApiException;
import com.cadence.security.AuthPrincipal;
import com.cadence.tenancy.Tenancy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * P3-B.2/.3 — the pattern engine's data access. Rolls up the existing V1
 * continuous aggregates (and, for per-day context switches, raw {@code events})
 * into the series {@link PatternAnalysis} consumes, then returns high-confidence
 * {@link Findings.PatternFindings}.
 *
 * <p>Grounding (kickoff): build on the frozen facts + existing CAGGs; read raw
 * {@code events} ONLY where the facts/CAGGs lack the grain — here, exactly one
 * place: per-day project-switch detection, which reuses P3-A §3.2's fragmentation
 * rules (focus set, 30-min session boundary, {@code project IS DISTINCT FROM}).
 *
 * <p>Every query filters {@code org_id} explicitly — the CAGGs are separate
 * hypertables the base-table RLS doesn't cover, so the explicit filter is the
 * real tenant guard (RLS is the backstop), matching {@link com.cadence.token}.
 */
@Service
public class PatternService {

    private final JdbcTemplate jdbc;
    private final Tenancy tenancy;
    private final PatternProperties cfg;

    /** Focus categories as a SQL literal list (fixed safe constant — no user input). */
    private static final String FOCUS_IN = PatternAnalysis.FOCUS_CATEGORIES.stream()
            .map(c -> "'" + c + "'").collect(Collectors.joining(","));

    private static final double MS_PER_HOUR = 3_600_000.0;

    public PatternService(JdbcTemplate jdbc, Tenancy tenancy, PatternProperties cfg) {
        this.jdbc = jdbc;
        this.tenancy = tenancy;
        this.cfg = cfg;
    }

    /** The caller's own pattern findings over the range. */
    @Transactional(readOnly = true)
    public Findings.PatternFindings forMember(AuthPrincipal p, String range) {
        tenancy.bind(p);
        PatternRange.Window w = PatternRange.parse(range);
        return compute("member", p.orgId(), p.memberId(), w);
    }

    /** Team-wide pattern findings (admin). Findings are aggregate — they name no member. */
    @Transactional(readOnly = true)
    public Findings.PatternFindings forOrg(AuthPrincipal p, String range) {
        if (!p.isAdmin()) {
            throw ApiException.forbidden("Admin role required.");
        }
        tenancy.bind(p);
        PatternRange.Window w = PatternRange.parse(range);
        return compute("org", p.orgId(), null, w);
    }

    // ── core ────────────────────────────────────────────────────────────────

    private Findings.PatternFindings compute(String grain, UUID orgId, UUID memberId,
                                             PatternRange.Window w) {
        Timestamp f = Timestamp.from(w.from().toInstant());
        Timestamp t = Timestamp.from(w.to().toInstant());

        int historyDays = historyDays(orgId, memberId, f, t);
        // Short-circuit the queries for low-data callers — analyze() returns empty,
        // but skipping the heavier reads keeps this cheap for brand-new members.
        if (historyDays < cfg.minDays()) {
            return new Findings.PatternFindings(grain, w.from(), w.to(), historyDays, true, List.of());
        }
        List<PatternAnalysis.FocusCell> grid = focusGrid(orgId, memberId, f, t);
        List<PatternAnalysis.DayMetrics> days = dayMetrics(orgId, memberId, f, t);
        return PatternAnalysis.analyze(grain, w.from(), w.to(), historyDays, grid, days, cfg);
    }

    /** Distinct active days in the window (any category) — the history-floor signal. */
    private int historyDays(UUID orgId, UUID memberId, Timestamp f, Timestamp t) {
        List<Object> args = baseArgs(orgId, memberId, f, t);
        Integer n = jdbc.queryForObject("""
                SELECT count(DISTINCT (bucket AT TIME ZONE 'UTC')::date)
                FROM events_daily_by_category
                WHERE org_id = ? """ + memberFilter(memberId) + """
                  AND bucket >= ? AND bucket < ?
                """, Integer.class, args.toArray());
        return n == null ? 0 : n;
    }

    /** 7×24 focus-ms grid from the hourly CAGG (Finding 1). */
    private List<PatternAnalysis.FocusCell> focusGrid(UUID orgId, UUID memberId, Timestamp f, Timestamp t) {
        List<Object> args = baseArgs(orgId, memberId, f, t);
        return jdbc.query("""
                SELECT extract(isodow from (bucket AT TIME ZONE 'UTC'))::int AS dow,
                       extract(hour   from (bucket AT TIME ZONE 'UTC'))::int AS hour,
                       sum(total_ms) AS focus_ms
                FROM events_hourly_by_category
                WHERE org_id = ? """ + memberFilter(memberId) + """
                  AND bucket >= ? AND bucket < ?
                  AND category IN (""" + FOCUS_IN + """
                )
                GROUP BY 1, 2
                """, (rs, i) -> new PatternAnalysis.FocusCell(
                        rs.getInt("dow"), rs.getInt("hour"), rs.getLong("focus_ms")),
                args.toArray());
    }

    /**
     * Per-day {meeting_h, deep_work_h, switches, focus_h}, merging the daily CAGG
     * (meeting/deep) with the raw-events switch query (Findings 2 & 3).
     */
    private List<PatternAnalysis.DayMetrics> dayMetrics(UUID orgId, UUID memberId, Timestamp f, Timestamp t) {
        // meeting_h / deep_work_h from the daily CAGG.
        Map<LocalDate, double[]> byDay = new LinkedHashMap<>();   // day -> [meetingH, deepWorkH]
        List<Object> catArgs = baseArgs(orgId, memberId, f, t);
        jdbc.query("""
                SELECT (bucket AT TIME ZONE 'UTC')::date AS day, category, sum(total_ms) AS total_ms
                FROM events_daily_by_category
                WHERE org_id = ? """ + memberFilter(memberId) + """
                  AND bucket >= ? AND bucket < ?
                  AND category IN ('meetings','deep_work')
                GROUP BY 1, 2
                """, rs -> {
            LocalDate day = rs.getObject("day", LocalDate.class);
            double hours = rs.getLong("total_ms") / MS_PER_HOUR;
            double[] cell = byDay.computeIfAbsent(day, k -> new double[2]);
            if ("meetings".equals(rs.getString("category"))) {
                cell[0] += hours;
            } else {
                cell[1] += hours;
            }
        }, catArgs.toArray());

        // switches / focus_ms per day from raw events (the one raw read — P3-A §3.2).
        Map<LocalDate, long[]> switchByDay = new LinkedHashMap<>();   // day -> [switches, focusMs]
        List<Object> swArgs = baseArgs(orgId, memberId, f, t);
        jdbc.query("""
                WITH focus AS (
                  SELECT member_id, ts_start, ts_end, duration_ms, project,
                         (ts_start AT TIME ZONE 'UTC')::date AS day,
                         lag(project) OVER w AS prev_project,
                         lag(ts_end)  OVER w AS prev_end
                  FROM events
                  WHERE org_id = ? """ + memberFilter(memberId) + """
                    AND ts_start >= ? AND ts_start < ?
                    AND is_idle = false
                    AND category IN (""" + FOCUS_IN + """
                  )
                  WINDOW w AS (PARTITION BY member_id ORDER BY ts_start)
                )
                SELECT day,
                       count(*) FILTER (
                         WHERE prev_end IS NOT NULL
                           AND project IS DISTINCT FROM prev_project
                           AND ts_start - prev_end <= interval '30 minutes'
                       ) AS switches,
                       sum(duration_ms) AS focus_ms
                FROM focus GROUP BY day
                """, rs -> {
            LocalDate day = rs.getObject("day", LocalDate.class);
            switchByDay.put(day, new long[]{rs.getLong("switches"), rs.getLong("focus_ms")});
        }, swArgs.toArray());

        // Union of days from both sources.
        java.util.Set<LocalDate> allDays = new java.util.TreeSet<>(byDay.keySet());
        allDays.addAll(switchByDay.keySet());
        List<PatternAnalysis.DayMetrics> out = new ArrayList<>(allDays.size());
        for (LocalDate day : allDays) {
            double[] cat = byDay.getOrDefault(day, new double[2]);
            long[] sw = switchByDay.getOrDefault(day, new long[2]);
            out.add(new PatternAnalysis.DayMetrics(
                    cat[0], cat[1], (int) sw[0], sw[1] / MS_PER_HOUR));
        }
        return out;
    }

    // ── arg/filter helpers (member filter is optional → org grain drops it) ──

    private static String memberFilter(UUID memberId) {
        return memberId == null ? "" : " AND member_id = ? ";
    }

    private static List<Object> baseArgs(UUID orgId, UUID memberId, Timestamp f, Timestamp t) {
        List<Object> args = new ArrayList<>(4);
        args.add(orgId);
        if (memberId != null) {
            args.add(memberId);
        }
        args.add(f);
        args.add(t);
        return args;
    }
}
