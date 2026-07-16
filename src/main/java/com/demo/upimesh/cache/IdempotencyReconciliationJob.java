package com.demo.upimesh.cache;

import com.demo.upimesh.model.Transaction;
import com.demo.upimesh.model.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Periodically cross-checks the idempotency cache (Redis, or the in-memory
 * map under the default profile) against the source of truth — the
 * `transactions` table — to catch drift between the two.
 *
 * The idempotency store is a *cache*: fast, but not the durable record of
 * what actually happened. The Transaction table (with its unique index on
 * packetHash — see Transaction.java) is the durable record. In a correctly
 * functioning system the two should always agree once the reaper has had a
 * chance to run. Two kinds of drift are worth catching:
 *
 *   1. SETTLED in the cache, but NO Transaction row exists.
 *      This should be structurally impossible given the current code (we
 *      only call markSettled() after settlementService.settle() has
 *      returned a saved Transaction), but that invariant lives in
 *      application code, not the database, so a future refactor, a bug, or
 *      a non-atomic failure between the DB commit and the markSettled()
 *      call could violate it silently. Catching it here means we find out
 *      from a scheduled report instead of from an angry customer.
 *
 *   2. A Transaction row exists (settled recently), but the idempotency
 *      cache has NO claim for that packetHash at all.
 *      This is the more realistic one: it means the dedup cache lost or
 *      never held that entry (e.g. a Redis eviction, a restart between
 *      profiles, a bug in claim() bypassed under test). It doesn't cause
 *      incorrect behavior by itself (the DB's unique index on packetHash is
 *      still there as the defense-in-depth backstop mentioned in
 *      Transaction's javadoc), but it means the FAST in-memory/Redis path
 *      is no longer providing protection for that packet, and a duplicate
 *      delivery would fall all the way through to a DB constraint
 *      violation instead of being caught cheaply upstream.
 *
 * This job only reports drift (via WARN logs); it deliberately does not try
 * to "fix" anything automatically — reconciliation output is a signal for a
 * human/alert to look at, not something that should silently mutate
 * financial state.
 */
@Component
public class IdempotencyReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyReconciliationJob.class);

    @Autowired private IdempotencyStore idempotency;
    @Autowired private TransactionRepository transactions;

    /** How far back to look for "Transaction exists but no cache entry" drift.
     *  Should be at least as long as the idempotency TTL, otherwise every
     *  legitimately-expired old claim would be reported as false drift. */
    @Value("${upi.mesh.reconciliation-lookback-seconds:3600}")
    private long lookbackSeconds;

    private final AtomicReference<Report> lastReport = new AtomicReference<>(Report.empty());

    @Scheduled(fixedDelayString = "${upi.mesh.reconciliation-interval-ms:60000}")
    public void reconcile() {
        List<String> claimedButNoTransaction = findClaimedButNoTransaction();
        List<String> settledButNoClaim = findSettledButNoClaim();

        Report report = new Report(Instant.now(), claimedButNoTransaction, settledButNoClaim);
        lastReport.set(report);

        if (!claimedButNoTransaction.isEmpty()) {
            log.warn("Idempotency drift: {} packetHash(es) marked SETTLED in the idempotency " +
                            "cache with NO matching Transaction row: {}",
                    claimedButNoTransaction.size(), preview(claimedButNoTransaction));
        }
        if (!settledButNoClaim.isEmpty()) {
            log.warn("Idempotency drift: {} recent Transaction row(s) have NO matching claim in " +
                            "the idempotency cache (dedup protection for these packetHashes has " +
                            "effectively lapsed): {}",
                    settledButNoClaim.size(), preview(settledButNoClaim));
        }
        if (claimedButNoTransaction.isEmpty() && settledButNoClaim.isEmpty()) {
            log.debug("Idempotency reconciliation: no drift detected");
        }
    }

    /** Cache says SETTLED, but the Transaction table disagrees. */
    private List<String> findClaimedButNoTransaction() {
        List<IdempotencyStore.ClaimSnapshot> settledClaims = idempotency.snapshotSettled();
        List<String> drift = new ArrayList<>();
        for (IdempotencyStore.ClaimSnapshot claim : settledClaims) {
            if (!transactions.existsByPacketHash(claim.key())) {
                drift.add(claim.key());
            }
        }
        return drift;
    }

    /** Transaction table has a recent row, but the cache has forgotten it entirely. */
    private List<String> findSettledButNoClaim() {
        Instant since = Instant.now().minus(Duration.ofSeconds(lookbackSeconds));
        List<Transaction> recent = transactions.findBySettledAtAfter(since);
        List<String> drift = new ArrayList<>();
        for (Transaction tx : recent) {
            Optional<IdempotencyStore.ClaimState> state = idempotency.getState(tx.getPacketHash());
            if (state.isEmpty()) {
                drift.add(tx.getPacketHash());
            }
        }
        return drift;
    }

    private static String preview(List<String> hashes) {
        return hashes.stream()
                .limit(5)
                .map(h -> h.length() > 12 ? h.substring(0, 12) + "..." : h)
                .collect(Collectors.joining(", "));
    }

    public Report getLastReport() {
        return lastReport.get();
    }

    public record Report(Instant ranAt, List<String> settledInCacheButNoTransaction,
                          List<String> transactionButNoCacheEntry) {
        static Report empty() {
            return new Report(null, Collections.emptyList(), Collections.emptyList());
        }
    }
}
