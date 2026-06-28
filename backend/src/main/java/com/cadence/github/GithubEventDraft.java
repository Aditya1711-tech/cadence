package com.cadence.github;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A GitHub webhook mapped to an Event-Contract-shaped row, <em>before</em> the
 * author's github login is resolved to a member (P2-D.4). Produced purely by
 * {@link GithubEventMapper} (no DB) so mapping is fully unit-testable.
 *
 * <p>GitHub events are point-in-time: {@code tsEnd == tsStart}, {@code durationMs
 * == 0}, so they never distort time-by-category rollups (which sum duration).
 * {@code source} is always {@code "github"}; {@code url} is always null (the §5
 * url field is chrome-only). {@code authorGithubLogin} is the actor whose
 * {@code members.github_login} we look up.
 */
public record GithubEventDraft(
        UUID eventId,
        OffsetDateTime tsStart,
        String app,
        String title,
        String project,
        String category,            // null for commits; "code_review" for PR activity
        Map<String, Object> meta,
        String authorGithubLogin) {

    /** Convenience: meta as a mutable copy (for full_diff stats enrichment). */
    public Map<String, Object> mutableMeta() {
        return new LinkedHashMap<>(meta);
    }
}
