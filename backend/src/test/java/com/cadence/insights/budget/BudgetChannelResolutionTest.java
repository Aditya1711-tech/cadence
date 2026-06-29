package com.cadence.insights.budget;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Channel gating is PURELY config presence: Slack only when an org opts into it
 * AND a webhook is resolvable (per-org or the env local-test default); otherwise
 * email (the default). This is what lets Slack flip on later with zero code change.
 */
class BudgetChannelResolutionTest {

    private final UUID org = UUID.randomUUID();

    private static BudgetProperties props(String envWebhook) {
        return new BudgetProperties(true, "claude-haiku-4-5", 3.0, new BigDecimal("10.00"),
                14, 7, new int[] {3, 5, 10}, 200L, envWebhook);
    }

    private OrgBudgetConfig cfg(String channel, String webhook) {
        return new OrgBudgetConfig(org, true, 3.0, new BigDecimal("10.00"), 14, 7,
                new int[] {3, 5, 10}, channel, webhook, null, null, null, "UTC", null);
    }

    private BudgetAlertDispatcher dispatcher(String envWebhook) {
        // mailer/slack/recipients unused by resolvedChannel — null is fine here.
        return new BudgetAlertDispatcher(null, null, null, props(envWebhook));
    }

    @Test
    void emailIsDefault() {
        assertEquals("email", dispatcher("").resolvedChannel(cfg("email", null)));
        // even if a webhook is present, channel=email means email.
        assertEquals("email", dispatcher("").resolvedChannel(cfg("email", "https://hooks/x")));
    }

    @Test
    void slackWhenOptedInWithPerOrgWebhook() {
        assertEquals("slack", dispatcher("").resolvedChannel(cfg("slack", "https://hooks/org")));
    }

    @Test
    void slackUsesEnvDefaultWhenNoPerOrgWebhook() {
        assertEquals("slack", dispatcher("https://hooks/env").resolvedChannel(cfg("slack", null)));
    }

    @Test
    void fallsBackToEmailWhenSlackOptedInButNoWebhookAnywhere() {
        assertEquals("email", dispatcher("").resolvedChannel(cfg("slack", null)));
        assertEquals("email", dispatcher("").resolvedChannel(cfg("slack", "  ")));
    }
}
