package com.demo.upimesh.service;

import com.demo.upimesh.cache.IdempotencyStore;
import com.demo.upimesh.crypto.HybridCryptoService;
import com.demo.upimesh.crypto.PacketVerificationService;
import com.demo.upimesh.model.MeshPacket;
import com.demo.upimesh.model.PaymentInstruction;
import com.demo.upimesh.model.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Legacy SYNCHRONOUS pipeline for one inbound packet from a bridge node.
 * Active under the default profile (no infra required — H2 + in-memory cache).
 *
 * Under the "event-driven" profile, ApiController instead routes through
 * gateway.PacketGatewayService, which publishes to Kafka, and the equivalent
 * work happens asynchronously in settlement.SettlementEventConsumer. Both
 * paths share the exact same security checks via PacketVerificationService
 * and the exact same debit/credit logic via SettlementService — only the
 * transport (direct call vs. Kafka topic) and the idempotency store
 * (in-memory vs. Redis) differ.
 *
 *   1. Hash the ciphertext.
 *   2. Try to claim that hash via the idempotency cache (PENDING).
 *      - If already claimed: this is a duplicate. Drop it.
 *   3. Decrypt + verify freshness + verify digital signature (shared logic).
 *      - If any check fails: release the claim (markFailed) and reject with
 *        the specific reason.
 *   4. Hand off to SettlementService for the actual debit/credit.
 *      - On success (even a business-REJECTED outcome like insufficient
 *        balance, which is still a *durable, final* outcome): markSettled.
 *      - On an unexpected exception (DB down, etc): markFailed, so a retry
 *        of this same packet isn't permanently stuck behind a transient
 *        error.
 */
@Service
public class BridgeIngestionService {

    private static final Logger log = LoggerFactory.getLogger(BridgeIngestionService.class);

    @Autowired private HybridCryptoService crypto;
    @Autowired private IdempotencyStore idempotency;
    @Autowired private PacketVerificationService verification;
    @Autowired private SettlementService settlement;

    public IngestResult ingest(MeshPacket packet, String bridgeNodeId, int hopCount) {
        String packetHash;
        try {
            packetHash = crypto.hashCiphertext(packet.getCiphertext());
        } catch (Exception e) {
            log.error("Failed to hash inbound packet: {}", e.getMessage(), e);
            return IngestResult.invalid("?", "malformed_packet");
        }

        // ---- Idempotency gate: PENDING claim ----
        IdempotencyStore.ClaimResult claimResult = idempotency.claim(packetHash);
        if (!claimResult.claimed()) {
            log.info("DUPLICATE packet {} from bridge {} — dropped (existing state={})",
                    shortHash(packetHash), bridgeNodeId, claimResult.existingState());
            return IngestResult.duplicate(packetHash);
        }

        try {
            // ---- Decrypt + freshness + signature verification ----
            PaymentInstruction instruction;
            try {
                instruction = verification.verify(packet.getCiphertext());
            } catch (PacketVerificationService.VerificationException e) {
                log.warn("Packet {} rejected: {}", shortHash(packetHash), e.reasonCode());
                // Verification failures aren't a durable outcome to remember —
                // release the claim rather than permanently stranding this
                // hash (e.g. a decrypt hiccup vs. a genuinely forged packet
                // both look the same here; releasing costs us nothing extra
                // since a truly bad packet will simply fail verification
                // again on the next attempt).
                idempotency.markFailed(packetHash);
                return IngestResult.invalid(packetHash, e.reasonCode());
            }

            // ---- Settle ----
            Transaction tx = settlement.settle(instruction, packetHash, bridgeNodeId, hopCount);
            // A Transaction row now durably exists (SETTLED or business-
            // REJECTED) — either way this packetHash is finally and fully
            // processed, so move the claim to its terminal SETTLED state.
            idempotency.markSettled(packetHash);
            return IngestResult.settled(packetHash, tx);

        } catch (Exception e) {
            // Anything unexpected (DB connectivity, optimistic lock failure,
            // etc.) — release the claim so a retry gets a real chance rather
            // than being dropped as a false duplicate for the rest of the TTL.
            idempotency.markFailed(packetHash);
            log.error("Ingestion error for packet {}: {}", shortHash(packetHash), e.getMessage(), e);
            return IngestResult.invalid(packetHash, "internal_error: " + e.getMessage());
        }
    }

    private static String shortHash(String hash) {
        return hash != null && hash.length() > 12 ? hash.substring(0, 12) + "..." : hash;
    }

    public record IngestResult(String outcome, String packetHash, String reason, Long transactionId) {
        public static IngestResult settled(String hash, Transaction tx) {
            return new IngestResult("SETTLED", hash, null, tx.getId());
        }
        public static IngestResult duplicate(String hash) {
            return new IngestResult("DUPLICATE_DROPPED", hash, null, null);
        }
        public static IngestResult invalid(String hash, String reason) {
            return new IngestResult("INVALID", hash, reason, null);
        }
    }
}
