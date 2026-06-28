package com.cadence.github;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC-backed {@link GithubInstallationRepository} over the {@code github_installations}
 * table (V2 migration, NEEDS P2-A). Follows the plain-JdbcTemplate style of the
 * rest of the backend (no ORM).
 */
@Repository
public class JdbcGithubInstallationRepository implements GithubInstallationRepository {

    private static final RowMapper<GithubInstallation> MAPPER = (rs, n) -> new GithubInstallation(
            rs.getObject("id", UUID.class),
            rs.getObject("org_id", UUID.class),
            rs.getLong("installation_id"),
            rs.getString("account_login"),
            GithubMode.fromWire(rs.getString("mode")),
            rs.getObject("suspended_at") != null);

    private final JdbcTemplate jdbc;

    public JdbcGithubInstallationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<GithubInstallation> findByInstallationId(long installationId) {
        // Cross-org read (no org bound yet) — see interface javadoc.
        return jdbc.query("""
                SELECT id, org_id, installation_id, account_login, mode, suspended_at
                  FROM github_installations
                 WHERE installation_id = ?
                """, MAPPER, installationId).stream().findFirst();
    }

    @Override
    public GithubInstallation link(UUID orgId, long installationId, String accountLogin, GithubMode mode) {
        // Runs under the admin's bound org context; RLS WITH CHECK requires
        // org_id = current_org, satisfied by the caller's org.
        jdbc.update("""
                INSERT INTO github_installations (org_id, installation_id, account_login, mode)
                VALUES (?, ?, ?, ?)
                """, orgId, installationId, accountLogin, mode.wire());
        return findByInstallationId(installationId).orElseThrow();
    }

    @Override
    public void setMode(UUID orgId, long installationId, GithubMode mode) {
        jdbc.update("""
                UPDATE github_installations SET mode = ?
                 WHERE org_id = ? AND installation_id = ?
                """, mode.wire(), orgId, installationId);
    }

    @Override
    public void setSuspended(long installationId, boolean suspended) {
        // Cross-org write keyed by installation_id (from the installation webhook).
        jdbc.update("""
                UPDATE github_installations
                   SET suspended_at = CASE WHEN ? THEN now() ELSE NULL END
                 WHERE installation_id = ?
                """, suspended, installationId);
    }

    @Override
    public List<GithubInstallation> listForOrg(UUID orgId) {
        return jdbc.query("""
                SELECT id, org_id, installation_id, account_login, mode, suspended_at
                  FROM github_installations
                 WHERE org_id = ?
                 ORDER BY created_at
                """, MAPPER, orgId);
    }
}
