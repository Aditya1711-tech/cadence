package com.cadence.insights.budget;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.*;

/** Quiet-hours window logic (defer-don't-drop), incl. midnight wrap and tz. */
class QuietHoursTest {

    private static Instant at(int hourUtc) {
        return ZonedDateTime.of(LocalDate.parse("2026-06-29"),
                LocalTime.of(hourUtc, 0), ZoneId.of("UTC")).toInstant();
    }

    @Test
    void disabledWhenEitherBoundNull() {
        assertFalse(QuietHours.isQuiet(at(3), null, 8, "UTC"));
        assertFalse(QuietHours.isQuiet(at(3), 22, null, "UTC"));
        assertFalse(QuietHours.isQuiet(at(3), 8, 8, "UTC"));     // empty window
    }

    @Test
    void sameDayWindow() {
        assertTrue(QuietHours.isQuiet(at(2), 1, 5, "UTC"));
        assertFalse(QuietHours.isQuiet(at(5), 1, 5, "UTC"));     // end is exclusive
        assertFalse(QuietHours.isQuiet(at(0), 1, 5, "UTC"));
    }

    @Test
    void wrapsMidnight() {
        // 22:00 → 08:00 quiet window
        assertTrue(QuietHours.isQuiet(at(23), 22, 8, "UTC"));
        assertTrue(QuietHours.isQuiet(at(2), 22, 8, "UTC"));
        assertFalse(QuietHours.isQuiet(at(8), 22, 8, "UTC"));    // end exclusive
        assertFalse(QuietHours.isQuiet(at(12), 22, 8, "UTC"));
    }

    @Test
    void honoursTimezone() {
        // 03:00 UTC is 08:30 IST — outside a 22–08 IST quiet window.
        assertFalse(QuietHours.isQuiet(at(3), 22, 8, "Asia/Kolkata"));
        // 20:00 UTC is 01:30 IST next day — inside it.
        assertTrue(QuietHours.isQuiet(at(20), 22, 8, "Asia/Kolkata"));
    }

    @Test
    void badTimezoneFallsBackToUtc() {
        assertTrue(QuietHours.isQuiet(at(2), 1, 5, "Not/AZone"));
    }
}
