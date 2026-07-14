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
 *   2. Try to claim that hash via the idempotency cache.
 *      - If already claimed: this is a duplicate. Drop it.
 *   3. Decrypt + verify freshness + verify digital signature (shared logic).
 *      - If any check fails: reject with the specific reason.
 *   4. Hand off to SettlementService for the actual debit/credit.
 */
@Service
public class BridgeIngestionService {

    private static final Logger log = LoggerFactory.getLogger(BridgeIngestionService.class);

    @Autowired private HybridCryptoService crypto;
    @Autowired private IdempotencyStore idempotency;
    @Autowired private PacketVerificationService verification;
    @Autowired private SettlementService settlement;

    public IngestResult ingest(MeshPacket packet, String bridgeNodeId, int hopCount) {
        try {
            String packetHash = crypto.hashCiphertext(packet.getCiphertext());

            // ---- Idempotency gate ----
            if (!idempotency.claim(packetHash)) {
                log.info("DUPLICATE packet {} from bridge {} — dropped",
                        packetHash.substring(0, 12) + "...", bridgeNodeId);
                return IngestResult.duplicate(packetHash);
            }

            // ---- Decrypt + freshness + signature verification ----
            PaymentInstruction instruction;
            try {
                instruction = verification.verify(packet.getCiphertext());
            } catch (PacketVerificationService.VerificationException e) {
                log.warn("Packet {} rejected: {}", packetHash.substring(0, 12) + "...", e.reasonCode());
                return IngestResult.invalid(packetHash, e.reasonCode());
            }

            // ---- Settle ----
            Transaction tx = settlement.settle(instruction, packetHash, bridgeNodeId, hopCount);
            return IngestResult.settled(packetHash, tx);

        } catch (Exception e) {
            log.error("Ingestion error: {}", e.getMessage(), e);
            return IngestResult.invalid("?", "internal_error: " + e.getMessage());
        }
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
