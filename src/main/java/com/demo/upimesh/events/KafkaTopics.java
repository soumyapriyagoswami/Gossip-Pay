package com.demo.upimesh.events;

/**
 * Central registry of Kafka topic names for the event-driven pipeline:
 *
 *   Phone -> Gateway -> [PACKET_RECEIVED] -> Settlement Service
 *                                                 |
 *                                                 v
 *                                    [SETTLEMENT_COMPLETED] -> Ledger Service
 *                                                                    |
 *                                                                    v
 *                                                      [LEDGER_RECORDED] -> Notification Service
 *
 * Each stage only knows about the topic it consumes and the topic it produces —
 * it never calls another service's code directly. That's what makes this
 * event-driven rather than just "a bunch of services calling each other".
 */
public final class KafkaTopics {

    private KafkaTopics() {}

    /** Published by the Gateway the instant a packet arrives from a bridge phone. */
    public static final String PACKET_RECEIVED = "payments.packet.received";

    /** Published by the Settlement Service once debit/credit has been applied. */
    public static final String SETTLEMENT_COMPLETED = "payments.settlement.completed";

    /** Published by the Ledger Service once immutable double-entry rows are written. */
    public static final String LEDGER_RECORDED = "payments.ledger.recorded";

    /** Anything that fails processing after too many retries lands here instead of being lost. */
    public static final String SETTLEMENT_DLQ = "payments.settlement.dlq";
}
