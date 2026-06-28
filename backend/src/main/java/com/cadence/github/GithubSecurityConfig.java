package com.cadence.github;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * A dedicated, higher-precedence {@link SecurityFilterChain} scoped to the GitHub
 * webhook path only. The webhook authenticates by HMAC signature
 * ({@link GithubSignatureVerifier}), not by JWT, so it must be {@code permitAll}.
 *
 * <p>This is its own ordered chain (matching only {@code /api/v1/github/webhook})
 * so P2-D never edits P2-A's {@code SecurityConfig} (§9 ownership). The default
 * chain (no {@code securityMatcher}, lowest precedence) still guards everything
 * else, including the authenticated {@code POST /api/v1/github/installations}
 * admin endpoint.
 */
@Configuration
@EnableConfigurationProperties(GithubProperties.class)
public class GithubSecurityConfig {

    public static final String WEBHOOK_PATH = "/api/v1/github/webhook";

    @Bean
    @Order(1)
    public SecurityFilterChain githubWebhookChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher(WEBHOOK_PATH)
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
