package com.demo.upimesh.events;

import java.math.BigDecimal;

/**
 * Emitted by the Settlement Service after it has decrypted, verified the
 * digital signature, and applied (or rejected) the debit/credit.
 *
 * The Ledger Service consumes this to write the permanent audit trail. It
 * never sees the ciphertext or the private key — by the time it gets involved
 * the money movement has already happened, it's just recording it.
 */
public record SettlementCompletedEvent(
        String packetHash,
        Long transactionId,
        String senderVpa,
        String receiverVpa,
        BigDecimal amount,
        String status,     // "SETTLED" | "REJECTED"
        String reason,     // null when SETTLED, e.g. "insufficient_balance" when REJECTED
        long settledAt
) {}
