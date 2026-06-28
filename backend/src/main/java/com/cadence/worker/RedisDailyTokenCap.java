package com.cadence.worker;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Redis-backed {@link DailyTokenCap}: a per-org, per-UTC-day counter that expires
 * after 48h. {@code allows} reads the running total; {@code record} INCRs it.
 */
@Component
@ConditionalOnProperty(prefix = "cadence.categorize", name = "enabled", havingValue = "true")
class RedisDailyTokenCap implements DailyTokenCap {

    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private final StringRedisTemplate redis;
    private final CategorizeProperties props;

    RedisDailyTokenCap(StringRedisTemplate redis, CategorizeProperties props) {
        this.redis = redis;
        this.props = props;
    }

    @Override
    public boolean allows(UUID orgId) {
        if (!props.capEnabled()) {
            return true;
        }
        String v = redis.opsForValue().get(key(orgId));
        long used = v == null ? 0L : parse(v);
        return used < props.dailyTokenCap();
    }

    @Override
    public void record(UUID orgId, long tokens) {
        if (!props.capEnabled() || tokens <= 0) {
            return;
        }
        String k = key(orgId);
        Long total = redis.opsForValue().increment(k, tokens);
        if (total != null && total == tokens) {
            // first write today — set TTL so the counter resets
            redis.expire(k, Duration.ofHours(48));
        }
    }

    private static long parse(String v) {
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private String key(UUID orgId) {
        return "cadence:cat:tokens:" + orgId + ":" + DAY.format(java.time.Instant.now());
    }
}
