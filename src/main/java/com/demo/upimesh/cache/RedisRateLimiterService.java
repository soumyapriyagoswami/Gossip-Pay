package com.demo.upimesh.cache;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Simple fixed-window rate limiter backed by Redis INCR, applied at the
 * Gateway before a packet is even published to Kafka. Protects the pipeline
 * from a single bridge phone (or malicious client) flooding it.
 *
 * Not a token bucket / sliding window — deliberately simple (INCR + EXPIRE NX)
 * because for a gateway-level abuse guard that's more than sufficient, and it
 * only costs 1-2 Redis round trips per request.
 */
@Service
@Profile("event-driven")
public class RedisRateLimiterService {

    private static final String KEY_PREFIX = "ratelimit:";

    @Autowired private StringRedisTemplate redis;

    @Value("${upi.mesh.rate-limit.max-per-minute:10}")
    private int maxPerMinute;

    /**
     * @param bucketKey e.g. the bridge node id or sender VPA
     * @return true if the request is allowed, false if the caller is over the limit
     */
    public boolean allow(String bucketKey) {
        String key = KEY_PREFIX + bucketKey;
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            // first hit in this window — start the 60s clock
            redis.expire(key, Duration.ofMinutes(1));
        }
        return count != null && count <= maxPerMinute;
    }

    public int getMaxPerMinute() {
        return maxPerMinute;
    }
}
