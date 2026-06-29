package com.cadence.insights.budget;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Quiet-hours window check (P3-E.1 §2.2). When "now" (in the org's timezone) is
 * inside the window we detect but DEFER delivery — the next non-quiet check
 * re-evaluates and delivers if the spike persists, so a 2 a.m. spike that is
 * gone by morning never pages anyone, with zero extra queue/state. Pure logic.
 */
final class QuietHours {

    private QuietHours() {
    }

    /**
     * @param start inclusive start hour 0–23, or null = no quiet hours
     * @param end   exclusive end hour 0–23, or null = no quiet hours
     */
    static boolean isQuiet(Instant now, Integer start, Integer end, String timezone) {
        if (start == null || end == null || start.intValue() == end.intValue()) {
            return false;                                  // window disabled / empty
        }
        ZoneId zone = zoneOf(timezone);
        int hour = ZonedDateTime.ofInstant(now, zone).getHour();
        if (start < end) {
            return hour >= start && hour < end;            // same-day window, e.g. 1–5
        }
        return hour >= start || hour < end;                // wraps midnight, e.g. 22–8
    }

    private static ZoneId zoneOf(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return ZoneId.of("UTC");
        }
        try {
            return ZoneId.of(timezone);
        } catch (Exception e) {
            return ZoneId.of("UTC");
        }
    }
}
