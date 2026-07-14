package com.demo.upimesh.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Redis-backed idempotency / duplicate-packet cache, active under the
 * "event-driven" profile. Shared by every replica of the Settlement Service,
 * so no matter which pod handles which of the 3 simultaneous bridge uploads,
 * exactly one wins.
 *
 * Uses SET key value NX PX ttl — Redis's atomic "set if not exists with
 * expiry" — which is precisely the SETNX + TTL pattern the original in-memory
 * implementation's comments described as the production target.
 */
@Service
@Profile("event-driven")
public class RedisIdempotencyStore implements IdempotencyStore {

    private static final Logger log = LoggerFactory.getLogger(RedisIdempotencyStore.class);
    private static final String KEY_PREFIX = "idempotency:packet:";

    @Autowired private StringRedisTemplate redis;

    @Value("${upi.mesh.idempotency-ttl-seconds:86400}")
    private long ttlSeconds;

    @Override
    public boolean claim(String key) {
        Boolean firstClaim = redis.opsForValue()
                .setIfAbsent(KEY_PREFIX + key, currentTimestamp(), Duration.ofSeconds(ttlSeconds));
        boolean claimed = Boolean.TRUE.equals(firstClaim);
        if (!claimed) {
            log.debug("Idempotency cache HIT (duplicate) for {}", key);
        }
        return claimed;
    }

    @Override
    public void clear() {
        // Demo/test helper only — never do a KEYS scan like this in real production Redis.
        var keys = redis.keys(KEY_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    private static String currentTimestamp() {
        return String.valueOf(System.currentTimeMillis());
    }
}
