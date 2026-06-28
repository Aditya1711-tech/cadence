package com.cadence.worker;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;

/**
 * Caches the model's verdict for a stable app/title/url pattern so repeat combos
 * never re-hit the LLM (P2-F.4). The key is the low-cardinality signal of an
 * event, normalised so equivalent events collide. Scoped per org so one org's
 * patterns never leak a category to another.
 */
interface PatternCache {

    Optional<Category> get(java.util.UUID orgId, String key);

    void put(java.util.UUID orgId, String key, Category category);

    /**
     * Build the normalised cache key from an event's signals:
     * {@code source | app | normalised-title | url-host}. Lower-cased; the title
     * has its trailing " — project" suffix dropped (the daemon's title format) so
     * the same file across re-opens collides; the url is reduced to its host.
     */
    static String key(EventSignals s) {
        return String.join("|",
                norm(s.source()),
                norm(s.app()),
                normTitle(s.title()),
                host(s.url()));
    }

    private static String norm(String v) {
        return v == null ? "" : v.trim().toLowerCase(Locale.ROOT);
    }

    private static String normTitle(String title) {
        if (title == null) {
            return "";
        }
        String t = title;
        int dash = t.indexOf(" — ");        // daemon title = "file — project"
        if (dash >= 0) {
            t = t.substring(0, dash);
        }
        return norm(t);
    }

    private static String host(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        try {
            String h = URI.create(url.trim()).getHost();
            return h == null ? norm(url) : h.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return norm(url);
        }
    }
}
