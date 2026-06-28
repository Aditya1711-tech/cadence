package com.cadence.github;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Placeholder {@link GithubStatsEnricher}. The {@code commit_messages_only}
 * default path never invokes this. {@code full_diff} is wired end-to-end except
 * for the live GitHub API call — implementing it requires minting an App
 * installation token (App JWT, RS256 via jjwt) and calling the commits/compare
 * API to read the {@code stats} block, then discarding the patch.
 *
 * <p>Until then this degrades safely: it logs once and leaves drafts as
 * messages-only (with the {@code changed_files} count the mapper already derived),
 * never dropping events.
 *
 * <p>TODO(P2-D.5 full_diff): replace with a GithubApiClient-backed implementation.
 */
@Component
public class StubGithubStatsEnricher implements GithubStatsEnricher {

    private static final Logger log = LoggerFactory.getLogger(StubGithubStatsEnricher.class);

    @Override
    public void enrich(GithubInstallation installation, List<GithubEventDraft> drafts) {
        if (drafts == null || drafts.isEmpty()) return;
        log.info("full_diff stats enrichment not yet implemented (installation={}, account={}); "
                        + "storing {} commit(s) messages-only with changed_files count.",
                installation.installationId(), installation.accountLogin(), drafts.size());
        // No-op: additions/deletions are simply absent from meta for now.
    }
}
