package com.cadence.query;

import com.cadence.event.EventDto;

/**
 * Org privacy levels (§8) — enforced on READ (store-raw decision, P2-A.1 §4).
 * This is the authoritative server-side enforcement point (P2-A.7).
 */
public enum PrivacyLevel {
    FULL,             // app names, titles, URLs, projects
    CATEGORIES_ONLY,  // categories + durations + projects; no app/title/url
    AGGREGATE_ONLY;   // daily category totals only; no per-event detail

    public static PrivacyLevel of(String raw) {
        return switch (raw == null ? "" : raw) {
            case "full" -> FULL;
            case "aggregate_only" -> AGGREGATE_ONLY;
            default -> CATEGORIES_ONLY;
        };
    }

    public String wire() {
        return switch (this) {
            case FULL -> "full";
            case CATEGORIES_ONLY -> "categories_only";
            case AGGREGATE_ONLY -> "aggregate_only";
        };
    }

    /**
     * Redact a per-event row for ADMIN reads of another member's data. Under
     * categories_only, strip app/title/url. (aggregate_only never returns events.)
     * Personal /me reads are NOT redacted — §8 governs what the admin sees.
     */
    public EventDto redactForAdmin(EventDto e) {
        if (this == FULL) return e;
        return new EventDto(e.eventId(), e.schemaVer(), e.source(), e.memberId(),
                e.tsStart(), e.tsEnd(), e.durationMs(),
                null, null, null,          // app, title, url stripped
                e.project(), e.category(), e.isIdle(), e.meta());
    }
}
