package com.cadence.insights.budget;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The scheduled budget loop (P3-E.2). On each tick it spans all tenants: list
 * orgs with recent token spend → load each org's config → compute today's burn vs
 * the rolling baseline per member and for the org → on a spike, defer if in quiet
 * hours, dedupe via the ledger, narrate (Haiku), and dispatch (email default /
 * Slack when configured). Every org is isolated in its own try/catch so one bad
 * org never aborts the run. Gated on {@code cadence.budget.enabled=true}.
 *
 * <p>Reads only the {@code events_daily_tokens} CAGG, whose refresh policy lags
 * the live edge by ~1h — fine for an hourly check (P3-E.1 §0).
 */
@Component
@ConditionalOnProperty(prefix = "cadence.budget", name = "enabled", havingValue = "true")
class BudgetMonitor {

    private static final Logger log = LoggerFactory.getLogger(BudgetMonitor.class);

    private final BudgetConfigStore configStore;
    private final BudgetMetricsStore metrics;
    private final BudgetAnomalyDetector detector;
    private final BudgetAlertLedger ledger;
    private final BudgetAlertNarrator narrator;
    private final BudgetAlertDispatcher dispatcher;
    private final BudgetProperties props;

    BudgetMonitor(BudgetConfigStore configStore, BudgetMetricsStore metrics,
                  BudgetAnomalyDetector detector, BudgetAlertLedger ledger,
                  BudgetAlertNarrator narrator, BudgetAlertDispatcher dispatcher,
                  BudgetProperties props) {
        this.configStore = configStore;
        this.metrics = metrics;
        this.detector = detector;
        this.ledger = ledger;
        this.narrator = narrator;
        this.dispatcher = dispatcher;
        this.props = props;
    }

    @Scheduled(cron = "${cadence.budget.check-cron}")
    void check() {
        Instant now = Instant.now();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate scanFrom = today.minusDays(props.baselineDays());
        LocalDate toExclusive = today.plusDays(1);

        List<UUID> orgs;
        try {
            orgs = metrics.activeOrgIds(scanFrom, toExclusive);
        } catch (Exception e) {
            log.warn("budget run skipped — could not list active orgs "
                    + "(is events_daily_tokens present?): {}", e.toString());
            return;
        }

        int alerted = 0;
        for (UUID orgId : orgs) {
            try {
                alerted += checkOrg(orgId, now, today);
            } catch (Exception e) {
                log.warn("budget check failed for org {}: {}", orgId, e.toString());
            }
        }
        if (alerted > 0) {
            log.info("budget monitor: {} alert(s) dispatched across {} org(s)", alerted, orgs.size());
        }
    }

    private int checkOrg(UUID orgId, Instant now, LocalDate today) {
        OrgBudgetConfig cfg = configStore.load(orgId);
        if (!cfg.enabled() || cfg.muted(now)) {
            return 0;
        }
        LocalDate from = today.minusDays(cfg.baselineDays());
        List<TokenCell> cells = metrics.cells(orgId, from, today.plusDays(1));
        if (cells.isEmpty()) {
            return 0;
        }
        List<SubjectBurn> subjects = BudgetWindowAssembler.assemble(cells, today, metrics.orgName(orgId));

        int alerted = 0;
        for (SubjectBurn s : subjects) {
            Optional<Anomaly> hit = detector.evaluate(orgId, today, s, cfg);
            if (hit.isEmpty()) {
                continue;
            }
            Anomaly a = hit.get();
            // Quiet hours: defer — the next non-quiet check re-evaluates & delivers.
            if (QuietHours.isQuiet(now, cfg.quietHoursStart(), cfg.quietHoursEnd(), cfg.timezone())) {
                continue;
            }
            String channel = dispatcher.resolvedChannel(cfg);
            // Dedupe: claim the (subject, day, severity) slot; deliver only if newly claimed.
            if (!ledger.tryClaim(a, channel)) {
                continue;
            }
            String body = narrator.narrate(a);
            dispatcher.dispatch(cfg, a, narrator.subjectLine(a), body);
            alerted++;
        }
        return alerted;
    }
}
