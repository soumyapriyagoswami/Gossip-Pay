package com.demo.upimesh.notification;

import com.demo.upimesh.events.KafkaTopics;
import com.demo.upimesh.events.LedgerRecordedEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * The "Notification Service" stage — the last hop in the pipeline. Consumes
 * payments.ledger.recorded and notifies both parties. Nothing downstream
 * depends on this succeeding, which is exactly why it's decoupled via Kafka
 * instead of being a synchronous call from the Settlement Service: a flaky
 * push-notification provider should never be able to block or fail a payment.
 */
@Component
@Profile("event-driven")
public class NotificationEventConsumer {

    @Autowired private NotificationService notificationService;

    @KafkaListener(topics = KafkaTopics.LEDGER_RECORDED, groupId = "notification-service")
    public void onLedgerRecorded(LedgerRecordedEvent event) {
        notificationService.send(
                event.senderVpa(),
                String.format("You paid ₹%s to %s", event.amount(), event.receiverVpa()),
                event.packetHash());

        notificationService.send(
                event.receiverVpa(),
                String.format("You received ₹%s from %s", event.amount(), event.senderVpa()),
                event.packetHash());
    }
}
