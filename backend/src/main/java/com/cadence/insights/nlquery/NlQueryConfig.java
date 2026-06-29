package com.cadence.insights.nlquery;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Enables the NL-query stack (P3-C). Gated on {@code cadence.nlquery.enabled=true}
 * so a dev box without an API key / readonly role boots the rest of the backend
 * untouched and {@code ./gradlew build} stays green.
 *
 * <p>Deliberately defines NO {@code DataSource} bean: doing so would trip Spring
 * Boot's {@code DataSourceAutoConfiguration} ({@code @ConditionalOnMissingBean
 * (DataSource.class)}) and disable the app's primary datasource. The readonly
 * datasource is instead owned privately by {@link NlQueryExecutor}, so the
 * text-to-SQL connection stays fully separate from the owner/app connection
 * without disturbing primary autoconfiguration.
 */
@Configuration
@ConditionalOnProperty(prefix = "cadence.nlquery", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(NlQueryProperties.class)
class NlQueryConfig {
}
