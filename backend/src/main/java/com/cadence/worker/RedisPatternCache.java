package com.cadence.worker;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/** Redis-backed {@link PatternCache} (P2-F.4). Keys expire after the configured TTL. */
@Component
@ConditionalOnProperty(prefix = "cadence.categorize", name = "enabled", havingValue = "true")
class RedisPatternCache implements PatternCache {

    private final StringRedisTemplate redis;
    private final Duration ttl;

    RedisPatternCache(StringRedisTemplate redis, CategorizeProperties props) {
        this.redis = redis;
        this.ttl = Duration.ofDays(props.cacheTtlDays());
    }

    @Override
    public Optional<Category> get(UUID orgId, String key) {
        String v = redis.opsForValue().get(redisKey(orgId, key));
        return Category.fromWire(v);
    }

    @Override
    public void put(UUID orgId, String key, Category category) {
        redis.opsForValue().set(redisKey(orgId, key), category.name(), ttl);
    }

    private static String redisKey(UUID orgId, String key) {
        return "cadence:cat:" + orgId + ":" + Integer.toHexString(key.hashCode()) + ":" + key;
    }
}
