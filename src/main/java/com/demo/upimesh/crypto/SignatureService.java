package com.demo.upimesh.crypto;

import com.demo.upimesh.model.PaymentInstruction;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.*;

/**
 * Digital signatures — the piece that was missing.
 *
 * Encryption (HybridCryptoService) proves the packet can only be READ by the
 * server. It does NOT prove WHO wrote it. Without a signature, anyone who
 * ever obtained the server's public key could forge a "payment instruction"
 * claiming to be from alice@demo, encrypt it, and the server would happily
 * decrypt and settle it.
 *
 * Digital signatures close that gap:
 *
 *   Sender's phone:
 *     1. Generates an Ed25519 keypair on first use (private key never leaves
 *        the device — analogous to how the server's RSA private key never
 *        leaves ServerKeyHolder).
 *     2. Signs the canonical bytes of the PaymentInstruction with its
 *        PRIVATE key. Only alice's phone can produce this signature.
 *     3. Puts the signature INSIDE the instruction, so it travels encrypted
 *        end-to-end — a snooping intermediate can't even see whose packet
 *        it is, let alone tamper with the signature.
 *
 *   Receiver (the server, acting for the receiving bank):
 *     4. Decrypts the packet.
 *     5. Looks up the sender's registered PUBLIC key (DeviceKeyRegistry).
 *     6. Verifies the signature against the canonical bytes. If it doesn't
 *        match — wrong key, tampered field, replayed-and-edited packet — the
 *        packet is rejected before any money moves.
 *
 * Ed25519 is used instead of RSA-signing here because it's fast, has small
 * (32-byte) keys/signatures — good for a low-power/offline phone — and is
 * natively supported since JDK 15 with no extra dependency.
 */
@Service
public class SignatureService {

    private static final String ALGORITHM = "Ed25519";

    public KeyPair generateKeyPair() throws NoSuchAlgorithmException {
        return KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair();
    }

    public byte[] sign(byte[] data, PrivateKey privateKey) throws GeneralSecurityException {
        Signature signer = Signature.getInstance(ALGORITHM);
        signer.initSign(privateKey);
        signer.update(data);
        return signer.sign();
    }

    public boolean verify(byte[] data, byte[] signature, PublicKey publicKey) throws GeneralSecurityException {
        Signature verifier = Signature.getInstance(ALGORITHM);
        verifier.initVerify(publicKey);
        verifier.update(data);
        return verifier.verify(signature);
    }

    /**
     * The exact bytes that get signed and later re-derived for verification.
     * Deliberately excludes the `signature` field itself (you can't sign a
     * field that contains its own signature) and pins field order so signer
     * and verifier always agree byte-for-byte.
     */
    public static byte[] canonicalBytes(PaymentInstruction pi) {
        String s = String.join("|",
                nullSafe(pi.getSenderVpa()),
                nullSafe(pi.getReceiverVpa()),
                pi.getAmount() == null ? "" : pi.getAmount().toPlainString(),
                nullSafe(pi.getPinHash()),
                nullSafe(pi.getNonce()),
                pi.getSignedAt() == null ? "" : String.valueOf(pi.getSignedAt())
        );
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String nullSafe(String s) { return s == null ? "" : s; }
}
