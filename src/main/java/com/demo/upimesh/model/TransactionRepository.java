package com.demo.upimesh.model;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    List<Transaction> findTop20ByOrderByIdDesc();
    boolean existsByPacketHash(String packetHash);
    Optional<Transaction> findByPacketHash(String packetHash);

    /** Used by the idempotency reconciliation job to find recently-settled
     * rows that should still have a matching claim in the idempotency store. */
    List<Transaction> findBySettledAtAfter(Instant instant);
}
