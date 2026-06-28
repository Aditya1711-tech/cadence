package com.cadence.token;

import com.cadence.common.ApiException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Translates a {@code ?range=} token into a [from,to) UTC window for the token
 * endpoints. This mirrors the query package's package-private RangeParser; it is
 * duplicated here (not reused) so P2-C stays inside its own com.cadence.token
 * package and never edits P2-A's query code. Keep the accepted tokens in sync.
 */
final class TokenRange {
    private TokenRange() {}

    record Window(OffsetDateTime from, OffsetDateTime to) {}

    static Window parse(String range) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String r = (range == null || range.isBlank()) ? "7d" : range.trim().toLowerCase();
        return switch (r) {
            case "today" -> new Window(now.toLocalDate().atStartOfDay().atOffset(ZoneOffset.UTC), now);
            case "24h" -> new Window(now.minusHours(24), now);
            case "7d", "week" -> new Window(now.minusDays(7), now);
            case "30d", "month" -> new Window(now.minusDays(30), now);
            case "90d" -> new Window(now.minusDays(90), now);
            default -> throw ApiException.badRequest(
                    "Unknown range '" + range + "'. Use today|24h|7d|30d|90d|week|month.");
        };
    }
}
