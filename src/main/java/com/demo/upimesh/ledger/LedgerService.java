package com.demo.upimesh.ledger;

import com.demo.upimesh.model.Account;
import com.demo.upimesh.model.AccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the immutable double-entry rows. Reads the CURRENT account balance
 * (already updated by the Settlement Service by the time this runs) purely
 * to snapshot "balance after this movement" for audit purposes — it does not
 * itself move any money.
 */
@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    @Autowired private LedgerRepository ledgerRepo;
    @Autowired private AccountRepository accountRepo;

    @Transactional
    public LedgerEntry[] recordSettlement(String packetHash, Long transactionId,
                                           String senderVpa, String receiverVpa,
                                           java.math.BigDecimal amount) {
        Account sender = accountRepo.findById(senderVpa).orElseThrow();
        Account receiver = accountRepo.findById(receiverVpa).orElseThrow();

        LedgerEntry debit = ledgerRepo.save(new LedgerEntry(
                packetHash, transactionId, senderVpa,
                LedgerEntry.EntryType.DEBIT, amount, sender.getBalance()));

        LedgerEntry credit = ledgerRepo.save(new LedgerEntry(
                packetHash, transactionId, receiverVpa,
                LedgerEntry.EntryType.CREDIT, amount, receiver.getBalance()));

        log.info("Ledger Service: recorded DEBIT #{} ({}) and CREDIT #{} ({}) for txn {}",
                debit.getId(), senderVpa, credit.getId(), receiverVpa, transactionId);

        return new LedgerEntry[] { debit, credit };
    }
}
