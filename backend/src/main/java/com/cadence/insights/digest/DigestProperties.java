package com.cadence.insights.digest;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tuning for the weekly digest job (bound from {@code cadence.digest.*}). See
 * PHASE-3 P3-A "Variables to set" and application.yml. The whole digest stack is
 * {@code @ConditionalOnProperty(cadence.digest.enabled=true)} so a default dev
 * box boots untouched (mirrors the P2-F worker gate).
 */
@ConfigurationProperties(prefix = "cadence.digest")
public record DigestProperties(
        boolean enabled,
        String model,
        String cron,
        int minDays,
        long maxOutputTokens
) {
    public DigestProperties {
        if (model == null || model.isBlank()) model = "claude-sonnet-4-6";
        if (cron == null || cron.isBlank()) cron = "0 0 23 * * SUN";
        if (minDays < 0) minDays = 14;
        if (maxOutputTokens <= 0) maxOutputTokens = 1024;
    }
}
