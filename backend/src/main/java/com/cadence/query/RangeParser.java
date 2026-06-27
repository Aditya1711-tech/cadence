package com.cadence.query;

import com.cadence.common.ApiException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** Translates a {@code ?range=} token (§6 summary endpoints) into a [from,to) window (UTC). */
final class RangeParser {
    private RangeParser() {}

    record Window(OffsetDateTime from, OffsetDateTime to) {}

    static Window parse(String range) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String r = (range == null || range.isBlank()) ? "7d" : range.trim().toLowerCase();
        return switch (r) {
            case "today" -> new Window(now.toLocalDate().atStartOfDay().atOffset(ZoneOffset.UTC), now);
            case "24h"   -> new Window(now.minusHours(24), now);
            case "7d", "week"  -> new Window(now.minusDays(7), now);
            case "30d", "month" -> new Window(now.minusDays(30), now);
            case "90d"   -> new Window(now.minusDays(90), now);
            default -> throw ApiException.badRequest(
                    "Unknown range '" + range + "'. Use today|24h|7d|30d|90d|week|month.");
        };
    }
}
