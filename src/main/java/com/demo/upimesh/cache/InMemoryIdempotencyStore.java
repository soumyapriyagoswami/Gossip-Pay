package com.demo.upimesh.cache;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory idempotency cache — used when the "event-driven" profile is NOT
 * active, i.e. the original zero-setup H2 + single-JVM demo mode.
 *
 * ConcurrentHashMap.putIfAbsent is the JVM-local equivalent of Redis SETNX.
 * Only correct for a single instance; see RedisIdempotencyStore for the
 * distributed version used once Postgres/Redis/Kafka are wired in.
 */
@Service
@Profile("!event-driven")
public class InMemoryIdempotencyStore implements IdempotencyStore {

    private final Map<String, Instant> seen = new ConcurrentHashMap<>();

    @Value("${upi.mesh.idempotency-ttl-seconds:86400}")
    private long ttlSeconds;

    @Override
    public boolean claim(String key) {
        Instant now = Instant.now();
        Instant prev = seen.putIfAbsent(key, now);
        return prev == null;
    }

    @Override
    public void clear() {
        seen.clear();
    }

    public int size() {
        return seen.size();
    }

    /** Periodically evict entries past their TTL so the map doesn't grow forever. */
    @Scheduled(fixedDelay = 60_000)
    public void evictExpired() {
        Instant cutoff = Instant.now().minusSeconds(ttlSeconds);
        seen.entrySet().removeIf(e -> e.getValue().isBefore(cutoff));
    }
}
