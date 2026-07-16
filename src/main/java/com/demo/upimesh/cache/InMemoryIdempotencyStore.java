package com.demo.upimesh.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory idempotency cache — used when the "event-driven" profile is NOT
 * active, i.e. the original zero-setup H2 + single-JVM demo mode.
 *
 * ConcurrentHashMap.putIfAbsent is the JVM-local equivalent of Redis SETNX,
 * and gives us the same atomic "first writer wins" guarantee for claim().
 * Only correct for a single instance; see RedisIdempotencyStore for the
 * distributed version used once Postgres/Redis/Kafka are wired in.
 *
 * See IdempotencyStore's javadoc for the PENDING -> SETTLED / FAILED state
 * machine this implements.
 */
@Service
@Profile("!event-driven")
public class InMemoryIdempotencyStore implements IdempotencyStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryIdempotencyStore.class);

    private static final class ClaimEntry {
        volatile ClaimState state;
        final long claimedAtMillis;

        ClaimEntry(ClaimState state, long claimedAtMillis) {
            this.state = state;
            this.claimedAtMillis = claimedAtMillis;
        }
    }

    private final Map<String, ClaimEntry> claims = new ConcurrentHashMap<>();

    @Value("${upi.mesh.idempotency-ttl-seconds:86400}")
    private long ttlSeconds;

    @Override
    public ClaimResult claim(String key) {
        ClaimEntry mine = new ClaimEntry(ClaimState.PENDING, System.currentTimeMillis());
        ClaimEntry existing = claims.putIfAbsent(key, mine);
        if (existing == null) {
            return ClaimResult.newlyClaimed();
        }
        log.debug("Idempotency cache HIT (duplicate, state={}) for {}", existing.state, key);
        return ClaimResult.duplicate(existing.state);
    }

    @Override
    public void markSettled(String key) {
        ClaimEntry entry = claims.get(key);
        if (entry != null) {
            entry.state = ClaimState.SETTLED;
        } else {
            // Shouldn't happen in practice (markSettled always follows a
            // successful claim()), but if the reaper raced us and already
            // released it, re-insert as SETTLED so the dedup record still
            // exists for the remainder of the TTL.
            claims.put(key, new ClaimEntry(ClaimState.SETTLED, System.currentTimeMillis()));
        }
    }

    @Override
    public void markFailed(String key) {
        // PENDING -> FAILED -> released, all in one step: deleting the key
        // IS the release. See IdempotencyStore javadoc for why this matters.
        ClaimEntry removed = claims.remove(key);
        if (removed != null) {
            log.info("Idempotency claim for {} released after processing failure", key);
        }
    }

    @Override
    public Optional<ClaimState> getState(String key) {
        ClaimEntry entry = claims.get(key);
        return entry == null ? Optional.empty() : Optional.of(entry.state);
    }

    @Override
    public List<ClaimSnapshot> snapshotPending() {
        return snapshotByState(ClaimState.PENDING);
    }

    @Override
    public List<ClaimSnapshot> snapshotSettled() {
        return snapshotByState(ClaimState.SETTLED);
    }

    private List<ClaimSnapshot> snapshotByState(ClaimState state) {
        return claims.entrySet().stream()
                .filter(e -> e.getValue().state == state)
                .map(e -> new ClaimSnapshot(e.getKey(), e.getValue().state, e.getValue().claimedAtMillis))
                .collect(Collectors.toList());
    }

    @Override
    public int releaseExpiredPending(Duration timeout) {
        long cutoff = System.currentTimeMillis() - timeout.toMillis();
        int[] released = {0};
        claims.entrySet().removeIf(e -> {
            boolean expired = e.getValue().state == ClaimState.PENDING
                    && e.getValue().claimedAtMillis < cutoff;
            if (expired) released[0]++;
            return expired;
        });
        return released[0];
    }

    @Override
    public void clear() {
        claims.clear();
    }

    public int size() {
        return claims.size();
    }

    /**
     * Periodically evict SETTLED entries past their TTL so the map doesn't
     * grow forever. (PENDING entries are handled separately and much more
     * aggressively by IdempotencyReaperJob — a stuck PENDING claim shouldn't
     * have to wait a full 24h TTL to be released.)
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60_000)
    public void evictExpiredSettled() {
        Instant cutoff = Instant.now().minusSeconds(ttlSeconds);
        claims.entrySet().removeIf(e ->
                e.getValue().state == ClaimState.SETTLED
                        && Instant.ofEpochMilli(e.getValue().claimedAtMillis).isBefore(cutoff));
    }
}
