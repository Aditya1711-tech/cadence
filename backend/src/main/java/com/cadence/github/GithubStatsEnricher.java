package com.cadence.github;

import java.util.List;

/**
 * Fills numeric diff <em>stats</em> (additions/deletions) into commit drafts when
 * an installation is in {@link GithubMode#FULL_DIFF}. This is the only path that
 * calls the GitHub API (requires {@code contents:read} + an App installation
 * token). It reads only the {@code stats} block and discards the patch — code is
 * never fetched into the DB (P2-D.2/.5).
 */
public interface GithubStatsEnricher {
    /**
     * Mutate {@code drafts} in place to add additions/deletions where available.
     * Implementations MUST degrade safely: on missing permission or API error,
     * leave the draft as messages-only rather than dropping the event.
     */
    void enrich(GithubInstallation installation, List<GithubEventDraft> drafts);
}
