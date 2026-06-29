package com.cadence.insights.nlquery;

import com.cadence.common.ApiException;
import com.cadence.query.PrivacyLevel;
import com.cadence.security.AuthPrincipal;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the NL-query path (P3-C.3): admin gate → privacy-bounded allowlist
 * → LLM generates SQL → {@link SqlValidator} (fail-closed) → execute as
 * {@code cadence_readonly} with org bound + row cap → LLM caption. The LLM never
 * sees raw rows; the generated SQL is never trusted (validated before execution).
 *
 * <p>Admin-only for v1 (same audience as {@code /org/summary}): RLS is org-scoped,
 * not member-scoped, so a non-admin could otherwise read colleagues' category/
 * token data via the readonly role. A member-scoped self-serve mode (adds a hard
 * {@code member_id} predicate) is a deliberate later addition.
 */
@Service
@ConditionalOnProperty(prefix = "cadence.nlquery", name = "enabled", havingValue = "true")
class NlQueryService {

    private final JdbcTemplate jdbc;          // owner connection — trusted internal reads only
    private final NlSqlPlanner planner;
    private final NlQueryExecutor executor;
    private final SqlValidator validator = new SqlValidator();

    NlQueryService(JdbcTemplate jdbc, NlSqlPlanner planner, NlQueryExecutor executor) {
        this.jdbc = jdbc;
        this.planner = planner;
        this.executor = executor;
    }

    NlQueryDtos.NlQueryResponse answer(AuthPrincipal p, String question) {
        if (!p.isAdmin()) {
            throw ApiException.forbidden("Admin role required.");
        }
        if (question == null || question.isBlank()) {
            throw ApiException.badRequest("A question is required.");
        }
        if (question.length() > 1000) {
            throw ApiException.badRequest("Question is too long.");
        }

        // Trusted, parameterized internal read (NOT the user's SQL): the owner
        // connection bypasses RLS, so no org bind is needed for this fixed lookup.
        PrivacyLevel level = PrivacyLevel.of(jdbc.queryForObject(
                "SELECT privacy_level FROM orgs WHERE id = ?", String.class, p.orgId()));
        SqlAllowlist allowlist = SqlAllowlist.forPrivacy(level);

        // LLM proposes SQL from schema metadata only; we never trust it.
        String candidate = planner.generateSql(question.strip(), allowlist);
        String validated = validator.validate(candidate, allowlist);

        // Execute as cadence_readonly, org-bound, row-capped (DB-hard layers 1–3).
        NlQueryExecutor.ExecResult result = executor.execute(validated, p.orgId());

        // Caption from the capped result only (best-effort; never fails the query).
        String caption = planner.caption(question.strip(), result.columns(), result.rows());

        return new NlQueryDtos.NlQueryResponse(
                question.strip(), validated, result.columns(), result.rows(),
                result.rows().size(), result.truncated(), caption);
    }
}
