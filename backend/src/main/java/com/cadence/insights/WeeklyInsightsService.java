package com.cadence.insights;

import com.cadence.insights.WeeklyInsightsResponse.Section;
import com.cadence.insights.WeeklyInsightsResponse.Spotted;
import com.cadence.security.AuthPrincipal;
import com.cadence.tenancy.Tenancy;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Serves {@code GET /api/v1/insights/weekly} (P3-A.4). Computes the structured
 * facts live from SQL ({@link InsightsAggregationService}) and attaches the
 * persisted narrative/card from {@code digests} when the weekly job (P3-A.5) has
 * produced one. The LLM is never invoked here — this is a pure read path, so it
 * is NOT gated on the (optional) digest stack.
 */
@Service
public class WeeklyInsightsService {

    private static final Logger log = LoggerFactory.getLogger(WeeklyInsightsService.class);

    private final JdbcTemplate jdbc;
    private final Tenancy tenancy;
    private final InsightsAggregationService aggregation;
    private final ObjectMapper mapper;

    public WeeklyInsightsService(JdbcTemplate jdbc, Tenancy tenancy,
                                 InsightsAggregationService aggregation, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.tenancy = tenancy;
        this.aggregation = aggregation;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public WeeklyInsightsResponse weekly(AuthPrincipal p, String weekParam) {
        tenancy.bind(p);
        IsoWeek week = (weekParam == null || weekParam.isBlank())
                ? IsoWeek.mostRecentCompleted(OffsetDateTime.now(ZoneOffset.UTC))
                : IsoWeek.parse(weekParam);

        // Member section — the caller's own digest.
        String displayName = jdbc.query(
                "SELECT display_name FROM members WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : null, p.memberId());
        var memberFacts = aggregation.buildMember(p.orgId(), p.memberId(), displayName, week);
        Section member = section(memberFacts, readDigest(p.orgId(), p.memberId(), week.label(), "member"));

        // Org section — admins only; honors privacy_level (no per-member detail under aggregate_only).
        Section org = null;
        if (p.isAdmin()) {
            String privacy = jdbc.queryForObject(
                    "SELECT privacy_level FROM orgs WHERE id = ?", String.class, p.orgId());
            boolean includeMembers = !"aggregate_only".equals(privacy);
            var orgFacts = aggregation.buildOrg(p.orgId(), includeMembers, week);
            org = section(orgFacts, readDigest(p.orgId(), null, week.label(), "org"));
        }

        return new WeeklyInsightsResponse(week.label(), member, org);
    }

    private Section section(Object facts, DigestRow d) {
        if (d == null) {
            return new Section(facts, null, List.of(), null, null, null);
        }
        return new Section(facts, d.narrative(), parseSpotted(d.spottedJson()),
                d.cardSvg(), d.createdAt(), d.status());
    }

    private DigestRow readDigest(UUID orgId, UUID memberId, String isoWeek, String grain) {
        String memberFilter = memberId == null ? "member_id IS NULL" : "member_id = ?";
        String sql = "SELECT narrative, spotted, card_svg, created_at, status FROM digests "
                + "WHERE org_id = ? AND iso_week = ? AND grain = ? AND " + memberFilter;
        Object[] args = memberId == null
                ? new Object[]{orgId, isoWeek, grain}
                : new Object[]{orgId, isoWeek, grain, memberId};
        try {
            return jdbc.queryForObject(sql, (rs, i) -> new DigestRow(
                    rs.getString("narrative"), rs.getString("spotted"), rs.getString("card_svg"),
                    rs.getObject("created_at", OffsetDateTime.class), rs.getString("status")), args);
        } catch (EmptyResultDataAccessException none) {
            return null;   // no digest generated for this week yet — facts-only
        }
    }

    private List<Spotted> parseSpotted(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return mapper.readValue(json, new TypeReference<List<Spotted>>() {});
        } catch (Exception e) {
            log.warn("could not parse stored spotted insights: {}", e.toString());
            return List.of();
        }
    }

    private record DigestRow(String narrative, String spottedJson, String cardSvg,
                             OffsetDateTime createdAt, String status) {}
}
