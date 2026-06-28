package com.cadence.github;

import java.util.Optional;
import java.util.UUID;

/**
 * Maps a GitHub login to a Cadence member within an org (P2-D.4), via the
 * pre-provisioned {@code members.github_login} column. An interface so the
 * webhook mapping is unit-testable without a database.
 */
public interface GithubMemberResolver {
    /** The active member in {@code orgId} whose github_login matches, if any. */
    Optional<UUID> resolveMemberId(UUID orgId, String githubLogin);
}
