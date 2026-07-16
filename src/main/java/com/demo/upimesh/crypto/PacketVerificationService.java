package com.demo.upimesh.crypto;

import com.demo.upimesh.model.PaymentInstruction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.PublicKey;
import java.time.Instant;
import java.util.Base64;

/**
 * Shared decrypt + freshness + signature verification pipeline.
 *
 * Used by BOTH the legacy synchronous path (BridgeIngestionService) and the
 * event-driven Kafka path (SettlementEventConsumer), so the security checks
 * live in exactly one place regardless of which pipeline is active.
 *
 *   ciphertext
 *      -> decrypt (HybridCryptoService)      [confidentiality + tamper detection]
 *      -> freshness check                    [replay protection]
 *      -> signature verify (SignatureService) [authenticity — proves WHO sent it]
 */
@Service
public class PacketVerificationService {

    private static final Logger log = LoggerFactory.getLogger(PacketVerificationService.class);

    @Autowired private HybridCryptoService crypto;
    @Autowired private SignatureService signatureService;
    @Autowired private DeviceKeyRegistry deviceKeys;

    @Value("${upi.mesh.packet-max-age-seconds:86400}")
    private long maxAgeSeconds;

    public PaymentInstruction verify(String ciphertext) throws VerificationException {
        PaymentInstruction instruction;
        try {
            instruction = crypto.decrypt(ciphertext);
        } catch (Exception e) {
            throw new VerificationException("decryption_failed");
        }

        long ageSeconds = (Instant.now().toEpochMilli() - instruction.getSignedAt()) / 1000;
        if (ageSeconds > maxAgeSeconds) {
            throw new VerificationException("stale_packet");
        }
        if (ageSeconds < -300) { // small clock-skew tolerance
            throw new VerificationException("future_dated");
        }

        if (instruction.getSignature() == null || instruction.getSignature().isBlank()) {
            throw new VerificationException("missing_signature");
        }

        PublicKey senderPublicKey = deviceKeys.getPublicKey(instruction.getSenderVpa());
        if (senderPublicKey == null) {
            throw new VerificationException("sender_not_enrolled");
        }

        try {
            byte[] sigBytes = Base64.getDecoder().decode(instruction.getSignature());
            boolean valid = signatureService.verify(
                    SignatureService.canonicalBytes(instruction), sigBytes, senderPublicKey);
            if (!valid) {
                log.warn("Signature verification FAILED for sender {}", instruction.getSenderVpa());
                throw new VerificationException("invalid_signature");
            }
        } catch (VerificationException v) {
            throw v;
        } catch (Exception e) {
            throw new VerificationException("invalid_signature");
        }

        return instruction;
    }

    public static class VerificationException extends Exception {
        public VerificationException(String reasonCode) { super(reasonCode); }
        public String reasonCode() { return getMessage(); }
    }
}
