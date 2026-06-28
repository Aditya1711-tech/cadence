package com.cadence.github;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * JDBC {@link GithubMemberResolver}. Runs after the org context is bound, so RLS
 * already constrains visibility to the org; the explicit {@code org_id = ?}
 * filter is defense-in-depth (matching the rest of the query layer).
 */
@Component
public class JdbcGithubMemberResolver implements GithubMemberResolver {

    private final JdbcTemplate jdbc;

    public JdbcGithubMemberResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<UUID> resolveMemberId(UUID orgId, String githubLogin) {
        if (githubLogin == null || githubLogin.isBlank()) {
            return Optional.empty();
        }
        return jdbc.query("""
                SELECT id FROM members
                 WHERE org_id = ? AND github_login = ? AND status = 'active'
                 LIMIT 1
                """,
                (rs, n) -> rs.getObject("id", UUID.class),
                orgId, githubLogin).stream().findFirst();
    }
}
