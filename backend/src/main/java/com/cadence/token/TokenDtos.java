package com.cadence.token;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Wire shapes for the P2-C.5 token-spend endpoints. Records serialize to
 * snake_case via the app's global Jackson config (cost_usd, tokens_in, …),
 * matching the rest of the read API (§6). cost_usd is the value computed by the
 * collector and aggregated by the events_daily_tokens continuous aggregate.
 */
public final class TokenDtos {
    private TokenDtos() {}

    /** Rolled-up spend across a window. */
    public record Totals(BigDecimal costUsd, long tokensIn, long tokensOut) {}

    /** Spend for one model across the window. */
    public record ModelTotal(String model, BigDecimal costUsd, long tokensIn, long tokensOut) {}

    /** Spend for one (day, model) cell — the heatmap/trend granularity. */
    public record DayModel(LocalDate day, String model, BigDecimal costUsd, long tokensIn, long tokensOut) {}

    /** One member's spend (admin/org view), privacy permitting. */
    public record MemberTokens(UUID memberId, String displayName, List<ModelTotal> byModel, Totals totals) {}

    /** GET /api/v1/me/tokens — the caller's own token spend. */
    public record MeTokens(OffsetDateTime from, OffsetDateTime to,
                           List<ModelTotal> byModel, List<DayModel> byDay, Totals totals) {}

    /** GET /api/v1/org/tokens — team spend (admin). by_member is null under aggregate_only. */
    public record OrgTokens(OffsetDateTime from, OffsetDateTime to, String team, String privacy,
                            List<ModelTotal> orgByModel, List<DayModel> orgByDay, Totals orgTotals,
                            List<MemberTokens> byMember) {}
}
