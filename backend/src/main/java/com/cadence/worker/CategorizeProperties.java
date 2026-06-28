package com.cadence.worker;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tuning for the categorisation worker (bound from {@code cadence.categorize.*}).
 * See PHASE-2 P2-F "Variables to set" and application.yml.
 */
@ConfigurationProperties(prefix = "cadence.categorize")
public record CategorizeProperties(
        boolean enabled,
        String model,
        long dailyTokenCap,     // per org per day; 0 = unlimited
        long pollIntervalMs,
        int batchSize,
        int maxAttempts,
        long maxOutputTokens,
        int cacheTtlDays
) {
    public CategorizeProperties {
        if (model == null || model.isBlank()) {
            model = "claude-haiku-4-5";
        }
        if (pollIntervalMs <= 0) {
            pollIntervalMs = 2000;
        }
        if (batchSize <= 0) {
            batchSize = 20;
        }
        if (maxAttempts <= 0) {
            maxAttempts = 5;
        }
        if (maxOutputTokens <= 0) {
            maxOutputTokens = 256;
        }
        if (cacheTtlDays <= 0) {
            cacheTtlDays = 30;
        }
    }

    public boolean capEnabled() {
        return dailyTokenCap > 0;
    }
}
