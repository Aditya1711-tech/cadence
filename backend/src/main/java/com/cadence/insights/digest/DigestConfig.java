package com.cadence.insights.digest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Activates the weekly digest stack only when {@code cadence.digest.enabled=true}.
 * A dev box with no ANTHROPIC_API_KEY can still enable it — the narrator falls
 * back to a deterministic template and delivery falls back to the console
 * LogMailer, so the whole pipeline is testable with zero external services
 * (kickoff requirement). The Anthropic client is built privately by
 * {@link DigestNarrator} (not a shared bean), so it never collides with the
 * P2-F worker's client.
 */
@Configuration
@ConditionalOnProperty(prefix = "cadence.digest", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(DigestProperties.class)
@EnableScheduling
class DigestConfig {
}
