package com.demo.upimesh.cache;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Contract for the duplicate-packet / idempotency cache.
 *
 * Two implementations exist:
 *   - InMemoryIdempotencyStore — default profile, zero setup, single instance only.
 *   - RedisIdempotencyStore    — "event-driven" profile, shared across every
 *     instance of the Settlement Service, which matters once you actually run
 *     more than one replica (the whole point of splitting into services).
 *
 * Both must guarantee: if N callers invoke claim(hash) concurrently for the
 * same hash, exactly one gets CLAIMED. That atomicity is what kills the
 * "three bridges deliver the same packet at the same instant" problem.
 *
 * --------------------------------------------------------------------------
 * STATE MACHINE (this is the important change over the original boolean API)
 * --------------------------------------------------------------------------
 *
 *   claim(key)
 *      |
 *      v
 *   PENDING  --- markSettled(key) --->  SETTLED   (terminal, kept for the TTL —
 *      |                                           this IS the durable dedup record)
 *      |
 *      +------ markFailed(key)   --->  (released — the key is deleted immediately)
 *      |
 *      +------ releaseExpiredPending() --> (released by the reaper if nobody ever
 *                                           called markSettled/markFailed at all,
 *                                           e.g. the process crashed mid-processing)
 *
 * A packet can only ever be double-spent-proof once it reaches SETTLED, because
 * SETTLED is the only state a NEW claim() call cannot override. PENDING is
 * *provisional*: it means "someone is working on this right now", not "this is
 * done". Treating PENDING as a permanent duplicate (the old boolean API's bug)
 * meant a single transient failure — a dropped DB connection, a decrypt
 * exception, a pod restart — would permanently strand that packetHash as a
 * false duplicate forever (or until the TTL, e.g. 24h, expired), even though
 * the money was never actually moved. markFailed() and the reaper both exist
 * to close that hole by giving the key back so a legitimate retry can claim it.
 */
public interface IdempotencyStore {

    enum ClaimState { PENDING, SETTLED }

    /** Result of a claim() attempt. */
    record ClaimResult(boolean claimed, ClaimState existingState) {
        public static ClaimResult newlyClaimed() {
            return new ClaimResult(true, ClaimState.PENDING);
        }
        public static ClaimResult duplicate(ClaimState existingState) {
            return new ClaimResult(false, existingState);
        }
    }

    /** A point-in-time view of one claim, used by the reaper and reconciliation job. */
    record ClaimSnapshot(String key, ClaimState state, long claimedAtMillis) {}

    /**
     * Try to claim a hash into the PENDING state.
     * Returns ClaimResult.newlyClaimed() if this caller is the first (i.e. the hash
     * was NOT already present); otherwise a duplicate result carrying whatever
     * state the existing claim is in (PENDING = someone else is processing it
     * right now, or a previous attempt hasn't been reaped yet; SETTLED = this
     * packet was already fully processed, permanently).
     */
    ClaimResult claim(String key);

    /**
     * Transition PENDING -> SETTLED. Call this ONLY after the corresponding
     * Transaction row has actually been durably written (whether SETTLED or
     * business-REJECTED — either way, that packetHash has been fully and
     * finally processed and must never be reprocessed).
     */
    void markSettled(String key);

    /**
     * Transition PENDING -> FAILED -> released. Call this when processing threw
     * before reaching a durable outcome (decrypt error, verification error, DB
     * error, etc). The claim is deleted immediately so the very next delivery
     * of the same packet (a retried upload, a gossip resend) gets a fresh
     * PENDING claim instead of being dropped as a false duplicate.
     */
    void markFailed(String key);

    /** Current state of a claim, if any exists. Used by the reconciliation job. */
    Optional<ClaimState> getState(String key);

    /** Snapshot of every claim currently sitting in PENDING. Used by the reaper. */
    List<ClaimSnapshot> snapshotPending();

    /** Snapshot of every claim currently in SETTLED. Used by the reconciliation job. */
    List<ClaimSnapshot> snapshotSettled();

    /**
     * Reaper hook: release (delete) any PENDING claim older than {@code timeout}.
     * These are claims where the owning process died (crash, OOM-kill, pod
     * eviction) between claim() and markSettled()/markFailed() and would
     * otherwise strand the packet as a false duplicate for the full TTL.
     *
     * @return number of claims released
     */
    int releaseExpiredPending(Duration timeout);

    /** Test/demo helper. */
    void clear();
}
