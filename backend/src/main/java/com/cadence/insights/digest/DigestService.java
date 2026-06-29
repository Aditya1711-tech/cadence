package com.cadence.insights.digest;

import com.cadence.insights.InsightFacts.MemberWeekFacts;
import com.cadence.insights.InsightFacts.OrgWeekFacts;
import com.cadence.insights.InsightsAggregationService;
import com.cadence.insights.IsoWeek;
import com.cadence.insights.digest.DigestNarrator.Narration;
import com.cadence.mail.Mailer;
import com.cadence.tenancy.Tenancy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * The weekly digest job (P3-A.5): compute facts (SQL) → narrate (LLM, grounded)
 * → render the shareable card → store → deliver over SMTP (or log fallback).
 * Gated on {@code cadence.digest.enabled=true}.
 *
 * <p>The LLM call and the email send happen BETWEEN short transactions (never
 * while holding a DB connection), mirroring the P2-F worker. Idempotent per week:
 * a re-run overwrites the same {@code insights}/{@code digests} rows.
 */
@Service
@ConditionalOnProperty(prefix = "cadence.digest", name = "enabled", havingValue = "true")
public class DigestService {

    private static final Logger log = LoggerFactory.getLogger(DigestService.class);

    private final JdbcTemplate jdbc;
    private final Tenancy tenancy;
    private final InsightsAggregationService agg;
    private final DigestNarrator narrator;
    private final DigestWriter writer;
    private final Mailer mailer;
    private final ObjectMapper mapper;
    private final DigestProperties props;
    private final TransactionTemplate tx;

    public DigestService(JdbcTemplate jdbc, Tenancy tenancy, InsightsAggregationService agg,
                         DigestNarrator narrator, DigestWriter writer, Mailer mailer,
                         ObjectMapper mapper, DigestProperties props, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.tenancy = tenancy;
        this.agg = agg;
        this.narrator = narrator;
        this.writer = writer;
        this.mailer = mailer;
        this.mapper = mapper;
        this.props = props;
        this.tx = new TransactionTemplate(txm);
    }

    /** Scheduled weekly. Narrates the ISO week that is just completing (Sunday night). */
    @Scheduled(cron = "${cadence.digest.cron:0 0 23 * * SUN}")
    public void scheduledRun() {
        IsoWeek week = IsoWeek.current(OffsetDateTime.now(ZoneOffset.UTC));
        int n = runForAllOrgs(week);
        log.info("weekly digest run for {} produced {} digest(s)", week.label(), n);
    }

    /** All orgs (the scheduled, cross-org path — runs on the owner connection). */
    public int runForAllOrgs(IsoWeek week) {
        int count = 0;
        for (UUID orgId : jdbc.queryForList("SELECT id FROM orgs", UUID.class)) {
            count += runForOrg(orgId, week);
        }
        return count;
    }

    /** One org — also the manual/admin dogfood trigger. */
    public int runForOrg(UUID orgId, IsoWeek week) {
        int count = 0;
        List<Member> members = jdbc.query(
                "SELECT id, display_name, email FROM members WHERE org_id = ? AND status = 'active'",
                (rs, i) -> new Member(rs.getObject("id", UUID.class),
                        rs.getString("display_name"), rs.getString("email")),
                orgId);
        for (Member m : members) {
            try {
                if (processMember(orgId, m, week)) count++;
            } catch (Exception e) {
                log.warn("member digest failed for {} in {}: {}", m.id(), week.label(), e.toString());
            }
        }
        try {
            if (processOrg(orgId, week)) count++;
        } catch (Exception e) {
            log.warn("org digest failed for {} in {}: {}", orgId, week.label(), e.toString());
        }
        return count;
    }

    // ── per-entity pipeline ────────────────────────────────────────────────────

    private boolean processMember(UUID orgId, Member m, IsoWeek week) {
        MemberWeekFacts facts = tx.execute(s -> {
            tenancy.bind(orgId, m.id(), "member");
            OffsetDateTime first = firstEvent(orgId, m.id());
            if (first == null || first.plusDays(props.minDays()).isAfter(week.end())) {
                return null;   // not enough history yet — skip quietly
            }
            return agg.buildMember(orgId, m.id(), m.displayName(), week);
        });
        if (facts == null) return false;

        String name = m.displayName() == null || m.displayName().isBlank() ? "You" : m.displayName();
        DigestHeadline h = new DigestHeadline(name, facts.period().isoWeek(),
                facts.deepWorkH(), facts.meetingH(), facts.tokenCostUsd(), facts.commits(),
                facts.fragmentationIndex(), facts.peakBlock(), facts.lowConfidence());
        String factsJson = json(facts);
        Narration nar = narrator.narrate(name, factsJson, h);
        String card = DigestCard.render(h);

        tx.executeWithoutResult(s -> {
            tenancy.bind(orgId, m.id(), "member");
            writer.writeRendered("member", orgId, m.id(), week, factsJson, h, nar, card);
        });

        boolean ok = deliver(List.of(m.email()), "Your Cadence week — " + week.label(), body(nar));
        tx.executeWithoutResult(s -> {
            tenancy.bind(orgId, m.id(), "member");
            writer.markDelivered("member", orgId, m.id(), week, ok);
        });
        return true;
    }

    private boolean processOrg(UUID orgId, IsoWeek week) {
        String privacy = jdbc.queryForObject("SELECT privacy_level FROM orgs WHERE id = ?",
                String.class, orgId);
        boolean includeMembers = !"aggregate_only".equals(privacy);

        OrgWeekFacts facts = tx.execute(s -> {
            tenancy.bind(orgId, systemMember(), "admin");
            return agg.buildOrg(orgId, includeMembers, week);
        });
        if (facts == null || facts.activeMembers() == 0) return false;   // nothing to narrate

        DigestHeadline h = new DigestHeadline("Your team", facts.period().isoWeek(),
                facts.deepWorkH(), facts.meetingH(), facts.tokenCostUsd(), facts.commits(),
                facts.fragmentationIndex(), facts.peakBlock(), facts.deltasVs4wkAvg() == null);
        String factsJson = json(facts);
        Narration nar = narrator.narrate("the team", factsJson, h);
        String card = DigestCard.render(h);

        tx.executeWithoutResult(s -> {
            tenancy.bind(orgId, systemMember(), "admin");
            writer.writeRendered("org", orgId, null, week, factsJson, h, nar, card);
        });

        List<String> admins = jdbc.queryForList(
                "SELECT email FROM members WHERE org_id = ? AND role IN ('admin','owner') "
                        + "AND status = 'active' AND email IS NOT NULL", String.class, orgId);
        boolean ok = deliver(admins, "Your team's Cadence week — " + week.label(), body(nar));
        tx.executeWithoutResult(s -> {
            tenancy.bind(orgId, systemMember(), "admin");
            writer.markDelivered("org", orgId, null, week, ok);
        });
        return true;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private OffsetDateTime firstEvent(UUID orgId, UUID memberId) {
        return jdbc.queryForObject("SELECT min(ts_start) FROM events WHERE org_id=? AND member_id=?",
                (rs, i) -> rs.getObject("min", OffsetDateTime.class), orgId, memberId);
    }

    /** Send to every non-blank recipient; returns true only if at least one send succeeded. */
    private boolean deliver(List<String> recipients, String subject, String body) {
        boolean any = false;
        for (String to : recipients) {
            if (to == null || to.isBlank()) continue;
            try {
                mailer.send(to, subject, body);
                any = true;
            } catch (Exception e) {
                log.warn("digest email to {} failed: {}", to, e.toString());
            }
        }
        return any;
    }

    private String body(Narration nar) {
        StringBuilder sb = new StringBuilder(nar.narrative()).append("\n\nSpotted this week:\n");
        nar.spotted().forEach(s -> sb.append("• ").append(s.title()).append(" — ").append(s.detail()).append('\n'));
        sb.append("\nSee your shareable card and full digest in Cadence.\n");
        return sb.toString();
    }

    private String json(Object facts) {
        try {
            return mapper.writeValueAsString(facts);
        } catch (Exception e) {
            throw new IllegalStateException("could not serialize facts", e);
        }
    }

    /** A stable non-null member UUID for org-grain tenancy binding (RLS keys on org, not member). */
    private static UUID systemMember() {
        return new UUID(0L, 0L);
    }

    private record Member(UUID id, String displayName, String email) {}
}
