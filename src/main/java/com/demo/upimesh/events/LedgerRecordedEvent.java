package com.demo.upimesh.events;

import java.math.BigDecimal;

/**
 * Emitted by the Ledger Service after the immutable debit/credit rows have
 * been persisted. The Notification Service consumes this to tell the sender
 * and receiver their payment went through — it's the last hop in the chain.
 */
public record LedgerRecordedEvent(
        String packetHash,
        Long transactionId,
        Long debitEntryId,
        Long creditEntryId,
        String senderVpa,
        String receiverVpa,
        BigDecimal amount,
        long recordedAt
) {}
