package com.demo.upimesh.crypto;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registers each user's phone's Ed25519 public key so the server can verify
 * signatures — the "bank" side of key enrollment.
 *
 * In a real deployment: the phone generates its keypair locally (e.g. in the
 * Android Keystore, hardware-backed), and only the PUBLIC key is ever sent to
 * the bank/PSP during device registration, over an authenticated channel
 * (e.g. during KYC/UPI PIN setup). The private key never leaves the device.
 *
 * For this demo we simulate that enrollment in-memory (same pattern as
 * ServerKeyHolder: fresh keys each run, good enough to demonstrate the
 * cryptographic flow end-to-end without needing real devices).
 */
@Component
public class DeviceKeyRegistry {

    private static final Logger log = LoggerFactory.getLogger(DeviceKeyRegistry.class);

    @Autowired private SignatureService signatureService;

    // vpa -> keypair. In production ONLY the public key would ever be stored server-side.
    private final Map<String, KeyPair> keysByVpa = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        // Nothing to seed here — DemoService enrolls each account's device
        // right after it creates the account, mirroring "user signs up,
        // phone generates keys, public key registered with the bank".
    }

    public void enroll(String vpa) throws Exception {
        KeyPair kp = signatureService.generateKeyPair();
        keysByVpa.put(vpa, kp);
        log.info("Enrolled signing key for {} (Ed25519 public key: {}...)",
                vpa, publicKeyBase64(vpa).substring(0, 20));
    }

    public boolean isEnrolled(String vpa) {
        return keysByVpa.containsKey(vpa);
    }

    public PublicKey getPublicKey(String vpa) {
        KeyPair kp = keysByVpa.get(vpa);
        return kp == null ? null : kp.getPublic();
    }

    /** DEMO ONLY: in reality the phone keeps this, the server never sees it. */
    public PrivateKey getPrivateKeyForDemoSigning(String vpa) {
        KeyPair kp = keysByVpa.get(vpa);
        if (kp == null) throw new IllegalStateException("No signing key enrolled for " + vpa);
        return kp.getPrivate();
    }

    public String publicKeyBase64(String vpa) {
        PublicKey pk = getPublicKey(vpa);
        if (pk == null) return null;
        return Base64.getEncoder().encodeToString(pk.getEncoded());
    }
}
