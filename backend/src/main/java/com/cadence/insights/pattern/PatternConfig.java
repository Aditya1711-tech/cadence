package com.cadence.insights.pattern;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers the pattern-engine {@code cadence.pattern.*} properties as a bean. */
@Configuration
@EnableConfigurationProperties(PatternProperties.class)
public class PatternConfig {
}
