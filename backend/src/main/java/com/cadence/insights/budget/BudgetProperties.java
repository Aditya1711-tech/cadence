package com.cadence.insights.budget;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * Global defaults for the budget monitor (bound from {@code cadence.budget.*};
 * see PHASE-3 P3-E "Variables to set" + application.yml). Per-org overrides live
 * in the {@code budget_alert_config} table and win over these (see
 * {@link OrgBudgetConfig}). Thresholds are PROVISIONAL until ≥2 weeks of live
 * token data calibrate them — see the P3-E coordination note in PROGRESS.md.
 */
@ConfigurationProperties(prefix = "cadence.budget")
public record BudgetProperties(
        boolean enabled,
        String model,
        double spikeMultiplier,
        BigDecimal minAbsoluteUsd,
        int baselineDays,
        int minHistoryDays,
        int[] tiers,
        long maxOutputTokens,
        String slackWebhookUrl          // LOCAL-TEST default only; real = per-org DB
) {
    public BudgetProperties {
        if (model == null || model.isBlank()) {
            model = "claude-haiku-4-5";
        }
        if (spikeMultiplier <= 0) {
            spikeMultiplier = 3.0;
        }
        if (minAbsoluteUsd == null || minAbsoluteUsd.signum() < 0) {
            // PROVISIONAL: ≈ a normal heavy active-dev day; retune post-deploy.
            minAbsoluteUsd = new BigDecimal("10.00");
        }
        if (baselineDays <= 0) {
            baselineDays = 14;
        }
        if (minHistoryDays <= 0) {
            minHistoryDays = 7;
        }
        if (tiers == null || tiers.length == 0) {
            tiers = new int[] {3, 5, 10};
        }
        if (maxOutputTokens <= 0) {
            maxOutputTokens = 200;
        }
    }
}
