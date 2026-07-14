package com.demo.upimesh.ledger;

import com.demo.upimesh.events.KafkaTopics;
import com.demo.upimesh.events.LedgerRecordedEvent;
import com.demo.upimesh.events.SettlementCompletedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * The "Ledger Service" stage. Consumes payments.settlement.completed.
 *
 * Only records money movement for SETTLED transactions — a REJECTED
 * transaction (e.g. insufficient balance) never touched any balances, so
 * there's nothing to book.
 */
@Component
@Profile("event-driven")
public class LedgerEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(LedgerEventConsumer.class);

    @Autowired private LedgerService ledgerService;
    @Autowired private KafkaTemplate<String, Object> kafkaTemplate;

    @KafkaListener(topics = KafkaTopics.SETTLEMENT_COMPLETED, groupId = "ledger-service")
    public void onSettlementCompleted(SettlementCompletedEvent event) {
        if (!"SETTLED".equals(event.status())) {
            log.info("Ledger Service: skipping {} transaction {} (status={})",
                    event.packetHash(), event.transactionId(), event.status());
            return;
        }

        LedgerEntry[] entries = ledgerService.recordSettlement(
                event.packetHash(), event.transactionId(),
                event.senderVpa(), event.receiverVpa(), event.amount());

        LedgerRecordedEvent recorded = new LedgerRecordedEvent(
                event.packetHash(),
                event.transactionId(),
                entries[0].getId(),
                entries[1].getId(),
                event.senderVpa(),
                event.receiverVpa(),
                event.amount(),
                Instant.now().toEpochMilli()
        );

        kafkaTemplate.send(KafkaTopics.LEDGER_RECORDED, event.packetHash(), recorded);
    }
}
