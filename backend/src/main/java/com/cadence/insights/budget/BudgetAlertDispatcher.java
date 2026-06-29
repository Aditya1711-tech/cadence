package com.cadence.insights.budget;

import com.cadence.mail.Mailer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Picks the delivery channel and sends (P3-E.3). Email is the DEFAULT; Slack is
 * used only when the org has a usable incoming-webhook URL AND its channel is set
 * to {@code slack}. The gate is PURELY config presence — Slack flips on later
 * with zero code change. A Slack failure falls back to email. Reuses
 * {@link Mailer} (SMTP when configured, console {@code LogMailer} otherwise).
 */
@Component
class BudgetAlertDispatcher {

    private static final Logger log = LoggerFactory.getLogger(BudgetAlertDispatcher.class);

    private final Mailer mailer;
    private final SlackNotifier slack;
    private final AlertRecipientStore recipients;
    private final BudgetProperties props;

    BudgetAlertDispatcher(Mailer mailer, SlackNotifier slack,
                          AlertRecipientStore recipients, BudgetProperties props) {
        this.mailer = mailer;
        this.slack = slack;
        this.recipients = recipients;
        this.props = props;
    }

    /** Resolved channel name recorded in the ledger ('slack' only when deliverable). */
    String resolvedChannel(OrgBudgetConfig cfg) {
        return ("slack".equals(cfg.channel()) && webhook(cfg) != null) ? "slack" : "email";
    }

    void dispatch(OrgBudgetConfig cfg, Anomaly a, String subject, String body) {
        if ("slack".equals(resolvedChannel(cfg))) {
            if (slack.send(webhook(cfg), body)) {
                return;
            }
            log.warn("Slack delivery failed for org {}; falling back to email", cfg.orgId());
        }
        email(cfg, a, subject, body);
    }

    private void email(OrgBudgetConfig cfg, Anomaly a, String subject, String body) {
        List<String> to = recipients.recipients(cfg.orgId(), cfg);
        if (to.isEmpty()) {
            log.warn("no email recipients for org {} budget alert ({}); not delivered",
                    cfg.orgId(), a.displayName());
            return;
        }
        for (String addr : to) {
            mailer.send(addr, subject, body);
        }
    }

    /** Per-org webhook, or the env local-test default; null/blank → not usable. */
    private String webhook(OrgBudgetConfig cfg) {
        String url = cfg.slackWebhookUrl();
        if (url == null || url.isBlank()) {
            url = props.slackWebhookUrl();
        }
        return (url == null || url.isBlank()) ? null : url;
    }
}
