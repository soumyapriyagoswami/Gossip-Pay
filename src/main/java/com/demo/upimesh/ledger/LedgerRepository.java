package com.demo.upimesh.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LedgerRepository extends JpaRepository<LedgerEntry, Long> {
    List<LedgerEntry> findTop50ByOrderByIdDesc();
    List<LedgerEntry> findByAccountVpaOrderByIdDesc(String accountVpa);
    List<LedgerEntry> findByPacketHash(String packetHash);
}
