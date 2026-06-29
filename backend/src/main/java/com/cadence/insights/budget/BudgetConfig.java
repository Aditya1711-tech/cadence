package com.cadence.insights.budget;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Wires the budget monitor. The whole stack is gated on
 * {@code cadence.budget.enabled=true}, so a dev box (no ANTHROPIC_API_KEY, no DB)
 * boots the rest of the backend untouched — mirroring the P2-F worker. No
 * Anthropic client bean is declared here on purpose: {@link BudgetAlertNarrator}
 * builds its own (from the environment) and tolerates its absence, so this
 * stream never collides with the worker's {@code AnthropicClient} bean.
 */
@Configuration
@ConditionalOnProperty(prefix = "cadence.budget", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(BudgetProperties.class)
@EnableScheduling
class BudgetConfig {
}
