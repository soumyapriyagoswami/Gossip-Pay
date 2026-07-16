package com.demo.upimesh.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Background job that finds idempotency claims stuck in PENDING past a
 * timeout and releases them.
 *
 * Why this is needed: claim() moves a packetHash to PENDING the instant one
 * caller wins the race. Under normal operation that caller quickly calls
 * either markSettled() or markFailed(). But if the process crashes, is
 * OOM-killed, or the pod is evicted between those two points, the claim is
 * left dangling in PENDING forever (or until the full idempotency TTL, e.g.
 * 24h) — during which every legitimate retry of that same packet (a bridge
 * phone resending after a timeout, another mesh route delivering the same
 * ciphertext) is dropped as a "duplicate" even though the payment was never
 * actually settled. That's a silent money-loss bug, not just an annoyance.
 *
 * The reaper closes that hole: anything still PENDING after
 * {@code upi.mesh.idempotency-pending-timeout-seconds} is assumed abandoned
 * and is released, so the next delivery attempt gets a clean PENDING claim
 * and a real chance to settle.
 *
 * Runs in both profiles — InMemoryIdempotencyStore and RedisIdempotencyStore
 * both implement releaseExpiredPending(), so this class doesn't need to know
 * which one is active.
 */
@Component
public class IdempotencyReaperJob {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyReaperJob.class);

    @Autowired private IdempotencyStore idempotency;

    /**
     * How long a claim is allowed to sit in PENDING before we assume the
     * owning process died and release it. Should comfortably exceed the
     * worst-case processing time (decrypt + verify + DB write) so a merely
     * slow — but still alive — request isn't reaped out from under itself.
     */
    @Value("${upi.mesh.idempotency-pending-timeout-seconds:120}")
    private long pendingTimeoutSeconds;

    @Scheduled(fixedDelayString = "${upi.mesh.idempotency-reaper-interval-ms:30000}")
    public void reapStuckClaims() {
        int released = idempotency.releaseExpiredPending(Duration.ofSeconds(pendingTimeoutSeconds));
        if (released > 0) {
            log.warn("Idempotency reaper released {} claim(s) stuck in PENDING for over {}s",
                    released, pendingTimeoutSeconds);
        } else {
            log.debug("Idempotency reaper: no stuck PENDING claims found");
        }
    }
}
