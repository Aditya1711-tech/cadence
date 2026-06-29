package com.cadence.insights.budget;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The effective budget config for one org: a {@code budget_alert_config} row when
 * present, otherwise the global env defaults ({@link BudgetProperties}). Per-org
 * values always win — that is what lets us calibrate one team without a redeploy.
 */
record OrgBudgetConfig(
        UUID orgId,
        boolean enabled,
        double spikeMultiplier,
        BigDecimal minAbsoluteUsd,
        int baselineDays,
        int minHistoryDays,
        int[] tiers,
        String channel,             // 'email' | 'slack' (resolved against webhook presence)
        String slackWebhookUrl,     // nullable
        String alertEmail,          // nullable → fall back to org owners/admins
        Integer quietHoursStart,    // nullable hour 0–23
        Integer quietHoursEnd,      // nullable hour 0–23
        String timezone,            // IANA tz for quiet hours
        Instant muteUntil           // nullable blanket mute
) {
    /** No DB row for this org → run on the global env defaults. */
    static OrgBudgetConfig defaults(UUID orgId, BudgetProperties p) {
        return new OrgBudgetConfig(
                orgId, true, p.spikeMultiplier(), p.minAbsoluteUsd(),
                p.baselineDays(), p.minHistoryDays(), p.tiers(),
                "email", null, null, null, null, "UTC", null);
    }

    boolean muted(Instant now) {
        return muteUntil != null && now.isBefore(muteUntil);
    }
}
