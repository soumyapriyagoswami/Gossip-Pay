package com.demo.upimesh.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Redis-backed idempotency / duplicate-packet cache, active under the
 * "event-driven" profile. Shared by every replica of the Settlement Service,
 * so no matter which pod handles which of the 3 simultaneous bridge uploads,
 * exactly one wins.
 *
 * Storage layout:
 *   idempotency:packet:{hash}   -> "PENDING:{claimedAtMillis}" or "SETTLED:{claimedAtMillis}"
 *                                  (a plain string value, TTL applied on write)
 *   idempotency:pending:index  -> ZSET, member={hash}, score={claimedAtMillis}
 *                                  Lets the reaper find stuck PENDING claims
 *                                  with ZRANGEBYSCORE instead of a KEYS/SCAN
 *                                  sweep over the whole keyspace.
 *
 * claim() uses SET key value NX PX ttl — Redis's atomic "set if not exists
 * with expiry" — for the same SETNX + TTL guarantee the original in-memory
 * implementation's comments described as the production target. markSettled
 * and markFailed then transition (or delete) that value.
 */
@Service
@Profile("event-driven")
public class RedisIdempotencyStore implements IdempotencyStore {

    private static final Logger log = LoggerFactory.getLogger(RedisIdempotencyStore.class);
    private static final String KEY_PREFIX = "idempotency:packet:";
    private static final String PENDING_INDEX_KEY = "idempotency:pending:index";

    @Autowired private StringRedisTemplate redis;

    @Value("${upi.mesh.idempotency-ttl-seconds:86400}")
    private long ttlSeconds;

    @Override
    public ClaimResult claim(String key) {
        long now = System.currentTimeMillis();
        Boolean firstClaim = redis.opsForValue()
                .setIfAbsent(dataKey(key), encode(ClaimState.PENDING, now), Duration.ofSeconds(ttlSeconds));

        if (Boolean.TRUE.equals(firstClaim)) {
            // Track it in the pending index so the reaper can find it later
            // without ever having to scan the whole keyspace.
            redis.opsForZSet().add(PENDING_INDEX_KEY, key, now);
            return ClaimResult.newlyClaimed();
        }

        String existingRaw = redis.opsForValue().get(dataKey(key));
        ClaimState existingState = existingRaw == null ? ClaimState.PENDING : decodeState(existingRaw);
        log.debug("Idempotency cache HIT (duplicate, state={}) for {}", existingState, key);
        return ClaimResult.duplicate(existingState);
    }

    @Override
    public void markSettled(String key) {
        long now = System.currentTimeMillis();
        // KEEPTTL-equivalent: re-write with the same TTL so the SETTLED
        // dedup record survives for the configured retention window.
        redis.opsForValue().set(dataKey(key), encode(ClaimState.SETTLED, now), Duration.ofSeconds(ttlSeconds));
        redis.opsForZSet().remove(PENDING_INDEX_KEY, key);
    }

    @Override
    public void markFailed(String key) {
        // PENDING -> FAILED -> released, all in one step: deleting the key
        // IS the release. See IdempotencyStore javadoc for why this matters.
        Boolean deleted = redis.delete(dataKey(key));
        redis.opsForZSet().remove(PENDING_INDEX_KEY, key);
        if (Boolean.TRUE.equals(deleted)) {
            log.info("Idempotency claim for {} released after processing failure", key);
        }
    }

    @Override
    public Optional<ClaimState> getState(String key) {
        String raw = redis.opsForValue().get(dataKey(key));
        return raw == null ? Optional.empty() : Optional.of(decodeState(raw));
    }

    @Override
    public List<ClaimSnapshot> snapshotPending() {
        Set<ZSetOperations.TypedTuple<String>> members =
                redis.opsForZSet().rangeWithScores(PENDING_INDEX_KEY, 0, -1);
        if (members == null) return List.of();
        return members.stream()
                .map(t -> new ClaimSnapshot(t.getValue(), ClaimState.PENDING,
                        t.getScore() == null ? 0L : t.getScore().longValue()))
                .collect(Collectors.toList());
    }

    @Override
    public List<ClaimSnapshot> snapshotSettled() {
        // Demo-scale only: a full KEYS scan. In real production Redis this
        // would instead be a cursor-based SCAN (see clear()'s comment below)
        // or, better, its own secondary index the way PENDING has one —
        // omitted here because SETTLED claims are numerous and short-lived
        // reconciliation runs are the only reader, so the cost is acceptable
        // for a demo of this size.
        Set<String> keys = redis.keys(KEY_PREFIX + "*");
        if (keys == null || keys.isEmpty()) return List.of();

        return keys.stream()
                .map(k -> {
                    String raw = redis.opsForValue().get(k);
                    if (raw == null) return null;
                    ClaimState state = decodeState(raw);
                    if (state != ClaimState.SETTLED) return null;
                    return new ClaimSnapshot(stripPrefix(k), state, decodeTimestamp(raw));
                })
                .filter(s -> s != null)
                .collect(Collectors.toList());
    }

    @Override
    public int releaseExpiredPending(Duration timeout) {
        long cutoff = System.currentTimeMillis() - timeout.toMillis();
        Set<String> candidates = redis.opsForZSet().rangeByScore(PENDING_INDEX_KEY, 0, cutoff);
        if (candidates == null || candidates.isEmpty()) return 0;

        int released = 0;
        for (String key : candidates) {
            // Re-check the actual value before deleting: it may have been
            // settled/failed by the owner between our ZRANGEBYSCORE read and
            // now, which would make this a harmless no-op race rather than a
            // correctness bug.
            String raw = redis.opsForValue().get(dataKey(key));
            if (raw != null && decodeState(raw) == ClaimState.PENDING) {
                redis.delete(dataKey(key));
                released++;
            }
            redis.opsForZSet().remove(PENDING_INDEX_KEY, key);
        }
        if (released > 0) {
            log.warn("Reaper released {} claim(s) stuck in PENDING past {}s", released, timeout.getSeconds());
        }
        return released;
    }

    @Override
    public void clear() {
        // Demo/test helper only — never do a KEYS scan like this in real production Redis.
        Set<String> keys = redis.keys(KEY_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
        redis.delete(PENDING_INDEX_KEY);
    }

    // -------------------------------------------------------------- helpers

    private static String dataKey(String hash) {
        return KEY_PREFIX + hash;
    }

    private static String stripPrefix(String fullKey) {
        return fullKey.startsWith(KEY_PREFIX) ? fullKey.substring(KEY_PREFIX.length()) : fullKey;
    }

    private static String encode(ClaimState state, long timestampMillis) {
        return state.name() + ":" + timestampMillis;
    }

    private static ClaimState decodeState(String raw) {
        String statePart = raw.contains(":") ? raw.substring(0, raw.indexOf(':')) : raw;
        try {
            return ClaimState.valueOf(statePart);
        } catch (IllegalArgumentException e) {
            // Legacy value written before this state-machine existed (a bare
            // timestamp) — treat it as PENDING so old data doesn't crash us.
            return ClaimState.PENDING;
        }
    }

    private static long decodeTimestamp(String raw) {
        int idx = raw.indexOf(':');
        if (idx < 0 || idx == raw.length() - 1) return 0L;
        try {
            return Long.parseLong(raw.substring(idx + 1));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
