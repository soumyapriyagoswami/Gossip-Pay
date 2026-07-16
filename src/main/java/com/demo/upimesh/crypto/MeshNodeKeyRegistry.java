package com.demo.upimesh.crypto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registers an Ed25519 keypair per MESH NODE (phone in the Bluetooth gossip
 * network), distinct from DeviceKeyRegistry which registers a key per
 * account VPA. The two are deliberately separate:
 *
 *   - DeviceKeyRegistry keys sign PAYMENT INSTRUCTIONS ("alice authorized
 *     this ₹100 transfer") and belong to a VPA/user.
 *   - MeshNodeKeyRegistry keys sign RELAY RECEIPTS ("phone-stranger1
 *     received packet X from phone-alice and forwarded it to phone-bridge
 *     at time T") and belong to a mesh node/device, which may or may not
 *     have an associated VPA — plenty of relay hops in a real mesh are
 *     total strangers' phones that never touch the payment itself.
 *
 * A node's mesh key proves WHICH DEVICE relayed a packet, which is exactly
 * the primitive needed for black-hole detection and reputation scoring:
 * without a signature, any node could falsely claim "I forwarded it" (to
 * pad its own reputation) or falsely claim "I received it from X" (to frame
 * a neighbor as a black hole).
 *
 * Keys are generated lazily on first use so this registry doesn't need to
 * know the device topology in advance — it works the same whether devices
 * are seeded at startup (MeshSimulatorService's default scenario) or added
 * dynamically later.
 */
@Component
public class MeshNodeKeyRegistry {

    private static final Logger log = LoggerFactory.getLogger(MeshNodeKeyRegistry.class);

    @Autowired private SignatureService signatureService;

    private final Map<String, KeyPair> keysByDeviceId = new ConcurrentHashMap<>();

    /** Returns the existing keypair for a device, generating one on first use. */
    public KeyPair getOrCreate(String deviceId) {
        return keysByDeviceId.computeIfAbsent(deviceId, id -> {
            try {
                KeyPair kp = signatureService.generateKeyPair();
                log.info("Enrolled mesh relay signing key for device {}", id);
                return kp;
            } catch (Exception e) {
                throw new IllegalStateException("Failed to generate mesh node key for " + id, e);
            }
        });
    }

    public PrivateKey getPrivateKey(String deviceId) {
        return getOrCreate(deviceId).getPrivate();
    }

    public PublicKey getPublicKey(String deviceId) {
        KeyPair kp = keysByDeviceId.get(deviceId);
        return kp == null ? null : kp.getPublic();
    }

    public boolean isEnrolled(String deviceId) {
        return keysByDeviceId.containsKey(deviceId);
    }
}
