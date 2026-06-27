package com.cadence.query;

import com.cadence.common.ApiException;
import com.cadence.security.AuthPrincipal;
import com.cadence.tenancy.Tenancy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Admin read endpoints (P2-A.5): /org/members, /org/summary. Privacy-aware (§8). */
@Service
public class OrgQueryService {

    private final JdbcTemplate jdbc;
    private final Tenancy tenancy;

    public OrgQueryService(JdbcTemplate jdbc, Tenancy tenancy) {
        this.jdbc = jdbc;
        this.tenancy = tenancy;
    }

    @Transactional(readOnly = true)
    public Summaries.MembersResponse members(AuthPrincipal p, String cursor, int limit) {
        requireAdmin(p);
        tenancy.bind(p);
        int lim = Math.min(Math.max(limit, 1), 1000);

        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT m.id, m.email, m.display_name, m.role, m.status,
                       coalesce(array_agg(t.name) FILTER (WHERE t.name IS NOT NULL), '{}') AS teams
                FROM members m
                LEFT JOIN team_members tm ON tm.member_id = m.id
                LEFT JOIN teams t ON t.id = tm.team_id
                WHERE m.org_id = ?
                """);
        args.add(p.orgId());
        UUID after = parseUuid(cursor);
        if (after != null) { sql.append(" AND m.id > ? "); args.add(after); }
        sql.append(" GROUP BY m.id ORDER BY m.id LIMIT ?");
        args.add(lim + 1);

        List<Summaries.MemberSummary> rows = jdbc.query(sql.toString(), (rs, i) -> {
            String[] teams = (String[]) rs.getArray("teams").getArray();
            return new Summaries.MemberSummary(
                    rs.getObject("id", UUID.class), rs.getString("email"),
                    rs.getString("display_name"), rs.getString("role"),
                    rs.getString("status"), List.of(teams));
        }, args.toArray());

        String next = null;
        if (rows.size() > lim) {
            next = rows.get(lim - 1).memberId().toString();
            rows = rows.subList(0, lim);
        }
        return new Summaries.MembersResponse(rows, next);
    }

    @Transactional(readOnly = true)
    public Summaries.OrgSummary summary(AuthPrincipal p, String range, String teamId) {
        requireAdmin(p);
        tenancy.bind(p);
        RangeParser.Window w = RangeParser.parse(range);
        Timestamp f = Timestamp.from(w.from().toInstant());
        Timestamp t = Timestamp.from(w.to().toInstant());
        PrivacyLevel level = PrivacyLevel.of(
                jdbc.queryForObject("SELECT privacy_level FROM orgs WHERE id = ?",
                        String.class, p.orgId()));

        UUID team = parseUuid(teamId);
        String teamFilter = team == null ? "" :
                " AND member_id IN (SELECT member_id FROM team_members WHERE team_id = ?) ";

        // Org-wide totals (always returned, all levels).
        List<Object> catArgs = new ArrayList<>(List.of(p.orgId(), f, t));
        if (team != null) catArgs.add(team);
        List<Summaries.CategoryBucket> orgByCat = jdbc.query("""
                SELECT coalesce(category,'uncategorized') AS category,
                       sum(duration_ms) AS total_ms, count(*) AS n
                FROM events WHERE org_id = ? AND ts_start >= ? AND ts_start < ? """ + teamFilter + """
                GROUP BY 1 ORDER BY total_ms DESC
                """, (rs, i) -> new Summaries.CategoryBucket(
                        rs.getString("category"), rs.getLong("total_ms"), rs.getLong("n")),
                catArgs.toArray());

        List<Object> dayArgs = new ArrayList<>(List.of(p.orgId(), f, t));
        if (team != null) dayArgs.add(team);
        List<Summaries.DayBucket> orgByDay = QuerySupport.byDay(jdbc, """
                SELECT (ts_start AT TIME ZONE 'UTC')::date AS day,
                       coalesce(category,'uncategorized') AS category,
                       sum(duration_ms) AS total_ms, count(*) AS n
                FROM events WHERE org_id = ? AND ts_start >= ? AND ts_start < ? """ + teamFilter + """
                GROUP BY 1,2 ORDER BY 1
                """, dayArgs.toArray());

        // aggregate_only: org-level daily totals only, no per-member breakdown.
        if (level == PrivacyLevel.AGGREGATE_ONLY) {
            return new Summaries.OrgSummary(w.from(), w.to(), teamId, level.wire(),
                    orgByCat, orgByDay, null);
        }

        // full / categories_only: per-member category + token rollups.
        List<Object> memArgs = new ArrayList<>(List.of(p.orgId(), f, t));
        if (team != null) memArgs.add(team);
        var members = new java.util.LinkedHashMap<UUID, List<Summaries.CategoryBucket>>();
        var names = new java.util.HashMap<UUID, String>();
        jdbc.query("""
                SELECT e.member_id, m.display_name,
                       coalesce(e.category,'uncategorized') AS category,
                       sum(e.duration_ms) AS total_ms, count(*) AS n
                FROM events e JOIN members m ON m.id = e.member_id
                WHERE e.org_id = ? AND e.ts_start >= ? AND e.ts_start < ? """ +
                (team == null ? "" : " AND e.member_id IN (SELECT member_id FROM team_members WHERE team_id = ?) ") + """
                GROUP BY e.member_id, m.display_name, 3
                ORDER BY e.member_id
                """, rs -> {
            UUID mid = rs.getObject("member_id", UUID.class);
            names.putIfAbsent(mid, rs.getString("display_name"));
            members.computeIfAbsent(mid, k -> new ArrayList<>()).add(new Summaries.CategoryBucket(
                    rs.getString("category"), rs.getLong("total_ms"), rs.getLong("n")));
        }, memArgs.toArray());

        List<Summaries.MemberRollup> byMember = new ArrayList<>();
        for (var entry : members.entrySet()) {
            UUID mid = entry.getKey();
            Summaries.TokenSummary tokens = QuerySupport.tokenSummary(jdbc, """
                    SELECT meta->>'model' AS model,
                           sum((meta->>'cost_usd')::numeric)   AS cost_usd,
                           sum((meta->>'tokens_in')::numeric)  AS tokens_in,
                           sum((meta->>'tokens_out')::numeric) AS tokens_out
                    FROM events WHERE source='token' AND org_id = ? AND member_id = ?
                      AND ts_start >= ? AND ts_start < ?
                    GROUP BY 1
                    """, p.orgId(), mid, f, t);
            byMember.add(new Summaries.MemberRollup(mid, names.get(mid), entry.getValue(), tokens));
        }

        return new Summaries.OrgSummary(w.from(), w.to(), teamId, level.wire(),
                orgByCat, orgByDay, byMember);
    }

    private void requireAdmin(AuthPrincipal p) {
        if (!p.isAdmin()) throw ApiException.forbidden("Admin role required.");
    }

    private static UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); } catch (Exception e) {
            throw ApiException.badRequest("Invalid id: " + s);
        }
    }
}
