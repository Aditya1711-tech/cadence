package com.cadence.github;

import java.util.UUID;

/**
 * A GitHub App installation linked to a Cadence org. Persisted in the
 * {@code github_installations} table (V2 migration — NEEDS P2-A; see
 * backend/docs/exploration/P2-D.1 §4).
 */
public record GithubInstallation(
        UUID id,
        UUID orgId,
        long installationId,
        String accountLogin,
        GithubMode mode,
        boolean suspended) {
}
