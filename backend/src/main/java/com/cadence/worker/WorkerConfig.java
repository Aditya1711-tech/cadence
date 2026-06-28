package com.cadence.worker;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Wires the categorisation worker. The whole stack is gated on
 * {@code cadence.categorize.enabled=true}, so a dev box (no ANTHROPIC_API_KEY, no
 * Redis) boots the rest of the backend untouched. The Anthropic client reads its
 * key from the environment (ANTHROPIC_API_KEY).
 */
@Configuration
@ConditionalOnProperty(prefix = "cadence.categorize", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(CategorizeProperties.class)
@EnableScheduling
class WorkerConfig {

    @Bean
    AnthropicClient anthropicClient() {
        return AnthropicOkHttpClient.fromEnv();
    }
}
