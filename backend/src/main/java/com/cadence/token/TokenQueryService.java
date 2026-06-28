package com.cadence.token;

import com.cadence.common.ApiException;
import com.cadence.query.PrivacyLevel;
import com.cadence.security.AuthPrincipal;
import com.cadence.tenancy.Tenancy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * P2-C.5 — AI token-cost aggregation for the dashboards. Reads the
 * events_daily_tokens continuous aggregate (per org/member/model/day), which
 * P2-A's schema defines but no endpoint consumed; /me/summary & /org/summary
 * keep their own per-model TokenSummary, so this is additive, not a duplicate.
 *
 * Every query filters org_id explicitly: the CAGG is materialized into a
 * separate hypertable that the base-table RLS policy does not cover, so the
 * explicit filter is the real tenant guard (RLS on `events` is the backstop) —
 * matching the schema note on the continuous aggregates.
 */
@Service
public class TokenQueryService {

    private final JdbcTemplate jdbc;
    private final Tenancy tenancy;

    public TokenQueryService(JdbcTemplate jdbc, Tenancy tenancy) {
        this.jdbc = jdbc;
        this.tenancy = tenancy;
    }

    /** The caller's own token spend over the range, by model and by day. */
    @Transactional(readOnly = true)
    public TokenDtos.MeTokens me(AuthPrincipal p, String range) {
        tenancy.bind(p);
        TokenRange.Window w = TokenRange.parse(range);
        Timestamp f = Timestamp.from(w.from().toInstant());
        Timestamp t = Timestamp.from(w.to().toInstant());

        List<TokenDtos.DayModel> byDay = jdbc.query("""
                SELECT bucket::date AS day, model,
                       sum(cost_usd)   AS cost_usd,
                       sum(tokens_in)  AS tokens_in,
                       sum(tokens_out) AS tokens_out
                FROM events_daily_tokens
                WHERE org_id = ? AND member_id = ? AND bucket >= ? AND bucket < ?
                GROUP BY 1, 2 ORDER BY 1, 2
                """, TokenQueryService::mapDayModel, p.orgId(), p.memberId(), f, t);

        return new TokenDtos.MeTokens(w.from(), w.to(), rollupByModel(byDay), byDay, totalOf(byDay));
    }

    /** Team token spend (admin). Per-member breakdown is withheld under aggregate_only. */
    @Transactional(readOnly = true)
    public TokenDtos.OrgTokens org(AuthPrincipal p, String range, String teamId) {
        if (!p.isAdmin()) throw ApiException.forbidden("Admin role required.");
        tenancy.bind(p);
        TokenRange.Window w = TokenRange.parse(range);
        Timestamp f = Timestamp.from(w.from().toInstant());
        Timestamp t = Timestamp.from(w.to().toInstant());
        PrivacyLevel level = PrivacyLevel.of(
                jdbc.queryForObject("SELECT privacy_level FROM orgs WHERE id = ?", String.class, p.orgId()));

        UUID team = parseUuid(teamId);
        String teamFilter = team == null ? "" :
                " AND member_id IN (SELECT member_id FROM team_members WHERE team_id = ?) ";

        // Org-wide totals — returned at every privacy level (token aggregates are
        // not per-event detail; aggregate_only still surfaces daily token spend).
        List<Object> dayArgs = new ArrayList<>(List.of(p.orgId(), f, t));
        if (team != null) dayArgs.add(team);
        List<TokenDtos.DayModel> orgByDay = jdbc.query("""
                SELECT bucket::date AS day, model,
                       sum(cost_usd)   AS cost_usd,
                       sum(tokens_in)  AS tokens_in,
                       sum(tokens_out) AS tokens_out
                FROM events_daily_tokens
                WHERE org_id = ? AND bucket >= ? AND bucket < ? """ + teamFilter + """
                GROUP BY 1, 2 ORDER BY 1, 2
                """, TokenQueryService::mapDayModel, dayArgs.toArray());

        TokenDtos.Totals orgTotals = totalOf(orgByDay);
        List<TokenDtos.ModelTotal> orgByModel = rollupByModel(orgByDay);

        if (level == PrivacyLevel.AGGREGATE_ONLY) {
            return new TokenDtos.OrgTokens(w.from(), w.to(), teamId, level.wire(),
                    orgByModel, orgByDay, orgTotals, null);
        }

        // Per-member breakdown (full / categories_only).
        List<Object> memArgs = new ArrayList<>(List.of(p.orgId(), f, t));
        if (team != null) memArgs.add(team);
        var members = new LinkedHashMap<UUID, List<TokenDtos.ModelTotal>>();
        var names = new LinkedHashMap<UUID, String>();
        jdbc.query("""
                SELECT t.member_id, m.display_name, t.model,
                       sum(t.cost_usd)   AS cost_usd,
                       sum(t.tokens_in)  AS tokens_in,
                       sum(t.tokens_out) AS tokens_out
                FROM events_daily_tokens t JOIN members m ON m.id = t.member_id
                WHERE t.org_id = ? AND t.bucket >= ? AND t.bucket < ? """ +
                (team == null ? "" : " AND t.member_id IN (SELECT member_id FROM team_members WHERE team_id = ?) ") + """
                GROUP BY t.member_id, m.display_name, t.model
                ORDER BY t.member_id, t.model
                """, (ResultSet rs) -> {
            UUID mid = rs.getObject("member_id", UUID.class);
            names.putIfAbsent(mid, rs.getString("display_name"));
            members.computeIfAbsent(mid, k -> new ArrayList<>()).add(new TokenDtos.ModelTotal(
                    rs.getString("model"), nz(rs.getBigDecimal("cost_usd")),
                    rs.getLong("tokens_in"), rs.getLong("tokens_out")));
        }, memArgs.toArray());

        List<TokenDtos.MemberTokens> byMember = new ArrayList<>();
        for (Map.Entry<UUID, List<TokenDtos.ModelTotal>> e : members.entrySet()) {
            byMember.add(new TokenDtos.MemberTokens(
                    e.getKey(), names.get(e.getKey()), e.getValue(), totalOfModels(e.getValue())));
        }

        return new TokenDtos.OrgTokens(w.from(), w.to(), teamId, level.wire(),
                orgByModel, orgByDay, orgTotals, byMember);
    }

    // --- helpers ---

    private static TokenDtos.DayModel mapDayModel(ResultSet rs, int i) throws SQLException {
        return new TokenDtos.DayModel(
                rs.getObject("day", java.time.LocalDate.class),
                rs.getString("model"),
                nz(rs.getBigDecimal("cost_usd")),
                rs.getLong("tokens_in"),
                rs.getLong("tokens_out"));
    }

    /** Collapse (day, model) cells into per-model totals across the window. */
    private static List<TokenDtos.ModelTotal> rollupByModel(List<TokenDtos.DayModel> cells) {
        var acc = new LinkedHashMap<String, long[]>();           // model -> [in, out]
        var cost = new LinkedHashMap<String, BigDecimal>();      // model -> cost
        for (TokenDtos.DayModel c : cells) {
            acc.computeIfAbsent(c.model(), k -> new long[2]);
            acc.get(c.model())[0] += c.tokensIn();
            acc.get(c.model())[1] += c.tokensOut();
            cost.merge(c.model(), c.costUsd(), BigDecimal::add);
        }
        List<TokenDtos.ModelTotal> out = new ArrayList<>();
        for (var e : acc.entrySet()) {
            out.add(new TokenDtos.ModelTotal(e.getKey(), cost.get(e.getKey()), e.getValue()[0], e.getValue()[1]));
        }
        return out;
    }

    private static TokenDtos.Totals totalOf(List<TokenDtos.DayModel> cells) {
        BigDecimal cost = BigDecimal.ZERO;
        long in = 0, out = 0;
        for (TokenDtos.DayModel c : cells) {
            cost = cost.add(c.costUsd());
            in += c.tokensIn();
            out += c.tokensOut();
        }
        return new TokenDtos.Totals(cost, in, out);
    }

    private static TokenDtos.Totals totalOfModels(List<TokenDtos.ModelTotal> models) {
        BigDecimal cost = BigDecimal.ZERO;
        long in = 0, out = 0;
        for (TokenDtos.ModelTotal m : models) {
            cost = cost.add(m.costUsd());
            in += m.tokensIn();
            out += m.tokensOut();
        }
        return new TokenDtos.Totals(cost, in, out);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return UUID.fromString(s);
        } catch (Exception e) {
            throw ApiException.badRequest("Invalid id: " + s);
        }
    }
}
