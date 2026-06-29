package com.cadence.insights.pattern;

import com.cadence.common.ApiException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a {@code ?range=} token into a [from,to) UTC window for the pattern
 * engine. Patterns need a multi-week span to be meaningful, so the default is
 * <b>4 weeks (28d)</b> — wider than the summary endpoints' 7d default (their
 * {@code RangeParser} is package-private to {@code com.cadence.query}, so this is
 * a deliberate, pattern-local parser rather than a cross-package reach-in).
 *
 * <p>Accepts {@code N d} or {@code N w} (e.g. {@code 14d}, {@code 4w}); capped at
 * 365 days so a window can't blow up the rollup queries.
 */
final class PatternRange {
    private PatternRange() {}

    private static final Pattern TOKEN = Pattern.compile("^(\\d{1,3})(d|w)$");
    private static final int MAX_DAYS = 365;

    record Window(OffsetDateTime from, OffsetDateTime to) {}

    static Window parse(String range) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (range == null || range.isBlank()) {
            return new Window(now.minusDays(28), now);   // default 4 weeks
        }
        Matcher m = TOKEN.matcher(range.trim().toLowerCase());
        if (!m.matches()) {
            throw ApiException.badRequest(
                    "Unknown range '" + range + "'. Use <N>d or <N>w, e.g. 14d, 4w.");
        }
        int n = Integer.parseInt(m.group(1));
        int days = "w".equals(m.group(2)) ? n * 7 : n;
        if (days <= 0 || days > MAX_DAYS) {
            throw ApiException.badRequest("Range out of bounds (1..365 days): " + range);
        }
        return new Window(now.minusDays(days), now);
    }
}
