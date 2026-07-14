package com.demo.upimesh.ledger;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Immutable double-entry ledger row. For every SETTLED transaction, exactly
 * two rows are written in the same DB transaction: one DEBIT (sender) and one
 * CREDIT (receiver), both referencing the same transactionId so they can be
 * reconciled as a pair.
 *
 * This table is intentionally separate from `transactions`:
 *   - `transactions` (Transaction.java) is the settlement engine's working
 *     record — one row per packet, SETTLED or REJECTED.
 *   - `ledger_entries` is the accountant's audit trail — one row per money
 *     movement, append-only, never updated or deleted. In a real bank this
 *     table (or its equivalent) is what gets reconciled against the core
 *     banking ledger at end of day.
 */
@Entity
@Table(name = "ledger_entries", indexes = {
        @Index(name = "idx_ledger_packet_hash", columnList = "packetHash"),
        @Index(name = "idx_ledger_account_vpa", columnList = "accountVpa")
})
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String packetHash;

    @Column(nullable = false)
    private Long transactionId;

    @Column(nullable = false)
    private String accountVpa;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private EntryType entryType;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balanceAfter;

    @Column(nullable = false)
    private Instant recordedAt;

    public enum EntryType { DEBIT, CREDIT }

    public LedgerEntry() {}

    public LedgerEntry(String packetHash, Long transactionId, String accountVpa,
                        EntryType entryType, BigDecimal amount, BigDecimal balanceAfter) {
        this.packetHash = packetHash;
        this.transactionId = transactionId;
        this.accountVpa = accountVpa;
        this.entryType = entryType;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.recordedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getPacketHash() { return packetHash; }
    public Long getTransactionId() { return transactionId; }
    public String getAccountVpa() { return accountVpa; }
    public EntryType getEntryType() { return entryType; }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getBalanceAfter() { return balanceAfter; }
    public Instant getRecordedAt() { return recordedAt; }
}
