package com.cadence.insights.nlquery;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tuning for the NL-query (text-to-SQL) path, bound from {@code cadence.nlquery.*}
 * (P3-C). See PHASE-3 P3-C "Variables to set" and application.yml.
 *
 * <p>The {@code db*} fields configure a SEPARATE datasource that connects as the
 * SELECT-only, non-owner, RLS-enforced {@code cadence_readonly} role — the
 * text-to-SQL path never rides the owner/app connection (kickoff hard rule). The
 * whole stack is gated on {@code enabled} (default false) so a dev box without an
 * API key / readonly role boots untouched.
 */
@ConfigurationProperties(prefix = "cadence.nlquery")
public record NlQueryProperties(
        boolean enabled,
        String model,
        int maxRows,
        long statementTimeoutMs,
        long maxOutputTokens,
        String dbUrl,
        String dbRole,
        String dbPassword
) {
    public NlQueryProperties {
        if (model == null || model.isBlank()) {
            model = "claude-sonnet-4-6";
        }
        if (maxRows <= 0) {
            maxRows = 5000;
        }
        if (statementTimeoutMs <= 0) {
            statementTimeoutMs = 5000;
        }
        if (maxOutputTokens <= 0) {
            maxOutputTokens = 1024;
        }
        if (dbRole == null || dbRole.isBlank()) {
            dbRole = "cadence_readonly";
        }
    }
}
