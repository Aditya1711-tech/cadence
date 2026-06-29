package com.cadence.insights;

import com.cadence.common.ApiException;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;

/**
 * An ISO-8601 week (Mon..Sun) identified as {@code "<week-based-year>-W<ww>"},
 * e.g. {@code "2026-W26"}. The digest window is [Monday 00:00 UTC, next Monday
 * 00:00 UTC). Pure date math — no DB, fully unit-testable.
 */
record IsoWeek(int weekYear, int week) {

    /** Parses {@code "2026-W26"} (zero-padded or not). */
    static IsoWeek parse(String s) {
        if (s == null || s.isBlank()) {
            throw ApiException.badRequest("Empty week. Use the ISO form, e.g. 2026-W26.");
        }
        String t = s.trim().toUpperCase();
        int w = t.indexOf('W');
        try {
            int year = Integer.parseInt(t.substring(0, w).replace("-", ""));
            int wk = Integer.parseInt(t.substring(w + 1));
            if (wk < 1 || wk > 53) throw new NumberFormatException("week out of range");
            return new IsoWeek(year, wk);
        } catch (RuntimeException e) {
            throw ApiException.badRequest("Invalid week '" + s + "'. Use the ISO form, e.g. 2026-W26.");
        }
    }

    /** The most recent fully-completed ISO week relative to {@code now} (the week before this one). */
    static IsoWeek mostRecentCompleted(OffsetDateTime now) {
        return of(now.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .minusWeeks(1));
    }

    /** The ISO week containing {@code now} (used by the Sunday-night digest job, P3-A.5). */
    static IsoWeek current(OffsetDateTime now) {
        return of(now.toLocalDate());
    }

    private static IsoWeek of(LocalDate anyDayInWeek) {
        return new IsoWeek(
                anyDayInWeek.get(IsoFields.WEEK_BASED_YEAR),
                anyDayInWeek.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    }

    /** Monday of this week, 00:00 UTC. Jan 4 is always in ISO week 1. */
    OffsetDateTime start() {
        LocalDate week1Monday = LocalDate.of(weekYear, 1, 4)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate monday = week1Monday.plusWeeks(week - 1L);
        return monday.atStartOfDay().atOffset(ZoneOffset.UTC);
    }

    /** Exclusive end: the following Monday, 00:00 UTC. */
    OffsetDateTime end() {
        return start().plusWeeks(1);
    }

    /** Number of completed weeks of history for an entity whose first event is at {@code firstEvent}. */
    int historyWeeksSince(OffsetDateTime firstEvent) {
        if (firstEvent == null) return 0;
        LocalDate firstMonday = firstEvent.atZoneSameInstant(ZoneOffset.UTC).toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        long weeks = ChronoUnit.WEEKS.between(firstMonday, start().toLocalDate()) + 1;
        return (int) Math.max(0, weeks);
    }

    String label() {
        return "%d-W%02d".formatted(weekYear, week);
    }
}
