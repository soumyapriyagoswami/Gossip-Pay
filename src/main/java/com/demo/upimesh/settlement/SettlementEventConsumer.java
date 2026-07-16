package com.demo.upimesh.settlement;

import com.demo.upimesh.cache.IdempotencyStore;
import com.demo.upimesh.crypto.PacketVerificationService;
import com.demo.upimesh.events.KafkaTopics;
import com.demo.upimesh.events.PacketReceivedEvent;
import com.demo.upimesh.events.SettlementCompletedEvent;
import com.demo.upimesh.model.PaymentInstruction;
import com.demo.upimesh.model.Transaction;
import com.demo.upimesh.service.SettlementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * The "Settlement Service" stage. Consumes payments.packet.received,
 * independently and asynchronously from the Gateway that produced it.
 *
 * Pipeline per event:
 *   1. Idempotency claim (Redis) — duplicate bridge uploads of the same
 *      packet are dropped here, exactly once wins even with multiple
 *      consumer instances in the "settlement-service" group. The claim
 *      starts in PENDING and is only ever finalized to SETTLED once a
 *      durable outcome exists; anything that goes wrong along the way
 *      releases the claim (markFailed) instead of leaving it stuck.
 *   2. Decrypt + freshness + SIGNATURE VERIFICATION (PacketVerificationService).
 *   3. Debit/credit via the existing SettlementService (unchanged — still a
 *      single Postgres transaction with optimistic locking on Account).
 *   4. Publish SettlementCompletedEvent for the Ledger Service to pick up.
 *
 * If this consumer were split into its own deployable tomorrow, it would only
 * need: this class, SettlementService, PacketVerificationService, crypto/*,
 * the Account/Transaction JPA entities, and a Kafka + Postgres connection.
 * It would NOT need the Gateway, Ledger, or Notification code at all.
 */
@Component
@Profile("event-driven")
public class SettlementEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(SettlementEventConsumer.class);

    @Autowired private IdempotencyStore idempotency;
    @Autowired private PacketVerificationService verification;
    @Autowired private SettlementService settlementService;
    @Autowired private KafkaTemplate<String, Object> kafkaTemplate;

    @KafkaListener(topics = KafkaTopics.PACKET_RECEIVED, groupId = "settlement-service")
    public void onPacketReceived(PacketReceivedEvent event) {
        String packetHash = event.packetHash();

        IdempotencyStore.ClaimResult claimResult = idempotency.claim(packetHash);
        if (!claimResult.claimed()) {
            log.info("DUPLICATE packet {} — dropped by Settlement Service (existing state={})",
                    shortHash(packetHash), claimResult.existingState());
            return;
        }

        try {
            PaymentInstruction instruction;
            try {
                instruction = verification.verify(event.ciphertext());
            } catch (PacketVerificationService.VerificationException e) {
                log.warn("Packet {} failed verification: {}", shortHash(packetHash), e.reasonCode());
                // Release rather than strand: a genuinely forged/tampered
                // packet will simply fail verification again if redelivered,
                // so there's no benefit to permanently blocking the hash —
                // and a transient decrypt hiccup gets a fair retry.
                idempotency.markFailed(packetHash);
                kafkaTemplate.send(KafkaTopics.SETTLEMENT_DLQ, packetHash,
                        new SettlementCompletedEvent(packetHash, null, null, null, null,
                                "INVALID", e.reasonCode(), Instant.now().toEpochMilli()));
                return;
            }

            Transaction tx = settlementService.settle(
                    instruction, packetHash, event.bridgeNodeId(), event.hopCount());

            // Durable outcome now exists (SETTLED or business-REJECTED) —
            // finalize the claim so it can never be reprocessed.
            idempotency.markSettled(packetHash);

            SettlementCompletedEvent completed = new SettlementCompletedEvent(
                    packetHash,
                    tx.getId(),
                    tx.getSenderVpa(),
                    tx.getReceiverVpa(),
                    tx.getAmount(),
                    tx.getStatus().name(),
                    tx.getStatus() == Transaction.Status.REJECTED ? "insufficient_balance" : null,
                    Instant.now().toEpochMilli()
            );

            kafkaTemplate.send(KafkaTopics.SETTLEMENT_COMPLETED, packetHash, completed);
            log.info("Settlement Service: {} packet {} ({} -> {}, ₹{}) -> published to {}",
                    tx.getStatus(), shortHash(packetHash), tx.getSenderVpa(), tx.getReceiverVpa(),
                    tx.getAmount(), KafkaTopics.SETTLEMENT_COMPLETED);

        } catch (Exception e) {
            // Unexpected failure (DB unavailable, optimistic lock exhausted,
            // etc). Release the claim so the next delivery of this same
            // packetHash — whether a Kafka redelivery or another bridge's
            // copy of the same gossip packet — gets a genuine PENDING claim
            // instead of being silently dropped as a false duplicate.
            idempotency.markFailed(packetHash);
            log.error("Settlement Service: unexpected error processing packet {}: {}",
                    shortHash(packetHash), e.getMessage(), e);
            kafkaTemplate.send(KafkaTopics.SETTLEMENT_DLQ, packetHash,
                    new SettlementCompletedEvent(packetHash, null, null, null, null,
                            "ERROR", "internal_error: " + e.getMessage(), Instant.now().toEpochMilli()));
        }
    }

    private static String shortHash(String hash) {
        return hash.length() > 12 ? hash.substring(0, 12) + "..." : hash;
    }
}
