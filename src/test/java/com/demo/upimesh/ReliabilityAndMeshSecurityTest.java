package com.demo.upimesh;

import com.demo.upimesh.cache.IdempotencyStore;
import com.demo.upimesh.mesh.BlackHoleSuspicion;
import com.demo.upimesh.mesh.DeviceReputationService;
import com.demo.upimesh.mesh.RelayReceipt;
import com.demo.upimesh.mesh.RelayReceiptLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the two feature areas added on top of the original demo:
 *   1. Idempotency reliability: the PENDING -> SETTLED / FAILED state machine
 *      and the reaper that releases stuck PENDING claims.
 *   2. Mesh-layer security: signed relay receipts, black-hole detection, and
 *      device reputation scoring.
 */
@SpringBootTest
class ReliabilityAndMeshSecurityTest {

    @Autowired private IdempotencyStore idempotency;
    @Autowired private RelayReceiptLedger relayReceipts;
    @Autowired private DeviceReputationService reputation;

    @BeforeEach
    void clear() {
        idempotency.clear();
        relayReceipts.clear();
        reputation.clear();
    }

    // ---------------------------------------------------- idempotency state machine

    @Test
    void secondClaimWhileFirstIsPendingIsADuplicate() {
        IdempotencyStore.ClaimResult first = idempotency.claim("hash-1");
        IdempotencyStore.ClaimResult second = idempotency.claim("hash-1");

        assertTrue(first.claimed());
        assertFalse(second.claimed());
        assertEquals(IdempotencyStore.ClaimState.PENDING, second.existingState());
    }

    @Test
    void markFailedReleasesTheClaimSoARetryCanSucceed() {
        assertTrue(idempotency.claim("hash-2").claimed());

        // Simulate a transient failure (decrypt error, DB blip, etc).
        idempotency.markFailed("hash-2");

        // The old boolean claim() API would have permanently stranded this
        // hash as a false duplicate. The new state machine must instead let
        // the very next attempt claim it fresh.
        IdempotencyStore.ClaimResult retry = idempotency.claim("hash-2");
        assertTrue(retry.claimed(), "a released claim must be re-claimable");
    }

    @Test
    void markSettledIsPermanentAndSurvivesFurtherClaimAttempts() {
        assertTrue(idempotency.claim("hash-3").claimed());
        idempotency.markSettled("hash-3");

        IdempotencyStore.ClaimResult duplicate = idempotency.claim("hash-3");
        assertFalse(duplicate.claimed());
        assertEquals(IdempotencyStore.ClaimState.SETTLED, duplicate.existingState());
    }

    @Test
    void reaperReleasesClaimsStuckInPendingPastTheTimeout() throws InterruptedException {
        idempotency.claim("hash-4"); // never settled or failed — simulates a crashed worker
        Thread.sleep(5);

        int released = idempotency.releaseExpiredPending(Duration.ZERO);
        assertEquals(1, released);

        IdempotencyStore.ClaimResult afterReap = idempotency.claim("hash-4");
        assertTrue(afterReap.claimed(), "reaper must fully release the stuck claim");
    }

    @Test
    void reaperLeavesFreshPendingClaimsAlone() {
        idempotency.claim("hash-5");
        int released = idempotency.releaseExpiredPending(Duration.ofHours(1));
        assertEquals(0, released, "a claim younger than the timeout must not be reaped");
    }

    // -------------------------------------------------------- mesh-layer security

    @Test
    void relayReceiptIsSignedAndVerifiable() {
        RelayReceipt receipt = relayReceipts.recordHop("pkt-1", "phone-alice", "phone-stranger1", 3);
        assertNotNull(receipt);
        assertTrue(relayReceipts.verify(receipt), "a genuine receipt must verify against the signer's mesh key");
    }

    @Test
    void tamperedReceiptFailsVerification() {
        RelayReceipt receipt = relayReceipts.recordHop("pkt-2", "phone-alice", "phone-stranger1", 3);
        RelayReceipt tampered = new RelayReceipt(
                receipt.packetId(), receipt.fromDeviceId(), "phone-bridge" /* changed recipient */,
                receipt.ttlAfterHop(), receipt.timestampMillis(), receipt.signatureBase64());
        assertFalse(relayReceipts.verify(tampered), "changing a signed field must invalidate the signature");
    }

    @Test
    void deviceThatReceivesButNeverForwardsIsFlaggedAsABlackHole() {
        // phone-stranger1 receives the packet with 2 hops left...
        relayReceipts.recordHop("pkt-3", "phone-alice", "phone-stranger1", 2);
        // ...but never forwards it to anyone (no further recordHop call).

        List<BlackHoleSuspicion> suspicions = relayReceipts.detectBlackHoles(Set.of());

        assertEquals(1, suspicions.size());
        assertEquals("phone-stranger1", suspicions.get(0).suspectDeviceId());
        assertEquals(2, suspicions.get(0).ttlAtReceipt());

        // A suspected drop must measurably lower the device's trust score.
        assertTrue(reputation.getScore("phone-stranger1") < 1.0);
    }

    @Test
    void deviceThatForwardsIsNotFlagged() {
        relayReceipts.recordHop("pkt-4", "phone-alice", "phone-stranger1", 2);
        relayReceipts.recordHop("pkt-4", "phone-stranger1", "phone-stranger2", 1);

        List<BlackHoleSuspicion> suspicions = relayReceipts.detectBlackHoles(Set.of());

        assertTrue(suspicions.stream().noneMatch(s -> s.suspectDeviceId().equals("phone-stranger1")));
        assertEquals(1.0, reputation.getScore("phone-stranger1"));
    }

    @Test
    void bridgeNodeIsNotFlaggedEvenWithoutForwarding() {
        // phone-bridge receives with hops left but is expected to upload to
        // the backend instead of forwarding further via gossip.
        relayReceipts.recordHop("pkt-5", "phone-alice", "phone-bridge", 2);

        List<BlackHoleSuspicion> suspicions = relayReceipts.detectBlackHoles(Set.of("phone-bridge"));

        assertTrue(suspicions.isEmpty());
    }

    @Test
    void deviceThatReceivesAtFinalHopIsNotFlagged() {
        // ttlAfterHop == 0 means this was legitimately the last hop —
        // no obligation to forward further.
        relayReceipts.recordHop("pkt-6", "phone-alice", "phone-stranger1", 0);

        List<BlackHoleSuspicion> suspicions = relayReceipts.detectBlackHoles(Set.of());

        assertTrue(suspicions.isEmpty());
    }
}
