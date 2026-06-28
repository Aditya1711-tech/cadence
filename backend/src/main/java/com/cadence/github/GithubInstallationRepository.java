package com.cadence.github;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for the installation→org mapping. An interface so the webhook /
 * mapping logic is unit-testable without a database (the backing
 * {@code github_installations} table ships in a P2-A V2 migration — NEEDS line).
 */
public interface GithubInstallationRepository {

    /**
     * Resolve a GitHub installation id to its Cadence org mapping.
     * <p><b>Cross-org lookup:</b> a webhook arrives keyed only by
     * {@code installation_id}, so this runs <em>before</em> any org RLS context
     * is bound — the same cross-org-door pattern the public auth flows use
     * (relies on owner bypass in dev; a privileged datasource is the P2-A.8
     * hardening, see P2-D.1 §4).
     */
    Optional<GithubInstallation> findByInstallationId(long installationId);

    /** Link an installation to {@code orgId} (called under the admin's org context). */
    GithubInstallation link(UUID orgId, long installationId, String accountLogin, GithubMode mode);

    /** Update the privacy mode for an org's installation (admin, org-scoped). */
    void setMode(UUID orgId, long installationId, GithubMode mode);

    /** Mark an installation suspended/unsuspended (from installation webhooks). */
    void setSuspended(long installationId, boolean suspended);

    /** List the calling org's installations (admin). */
    List<GithubInstallation> listForOrg(UUID orgId);
}
