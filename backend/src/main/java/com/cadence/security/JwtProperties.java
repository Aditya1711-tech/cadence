package com.cadence.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bound from {@code cadence.jwt.*} (§3 self-issued JWT). */
@ConfigurationProperties(prefix = "cadence.jwt")
public class JwtProperties {
    /** HS256 signing secret; must be >= 32 bytes (enforced at startup). */
    private String signingSecret = "";
    /** Access-token lifetime in minutes (JWT_TTL_MINUTES, default 60). */
    private long ttlMinutes = 60;

    public String getSigningSecret() { return signingSecret; }
    public void setSigningSecret(String signingSecret) { this.signingSecret = signingSecret; }
    public long getTtlMinutes() { return ttlMinutes; }
    public void setTtlMinutes(long ttlMinutes) { this.ttlMinutes = ttlMinutes; }
}
