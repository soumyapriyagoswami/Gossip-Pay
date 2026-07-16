package com.demo.upimesh.mesh;

import com.demo.upimesh.crypto.MeshNodeKeyRegistry;
import com.demo.upimesh.crypto.SignatureService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Records a signed RelayReceipt for every hop of the Bluetooth gossip
 * simulation, and can be interrogated (by the sender, or the backend
 * retroactively) to detect a node that received a packet but never
 * forwarded it on — a "black hole".
 *
 * How black-hole detection works:
 *   Every gossip hop produces a receipt signed by the SOURCE device:
 *   "I (fromDeviceId) had packet P and forwarded it to toDeviceId at time T
 *   with ttlAfterHop hops still remaining." Collecting all receipts for a
 *   packet lets us reconstruct which devices received it. If a device
 *   received the packet (appears as `toDeviceId` in some receipt) with hops
 *   still remaining (so it was expected to keep relaying, not stop) and
 *   never appears as the `fromDeviceId` of any later receipt for that same
 *   packet, it looks like the packet went in and never came out — the
 *   signature of a black hole. Devices that have internet connectivity are
 *   excluded from this check, because a bridge node's expected next move is
 *   to upload the packet to the backend (a completely different code path,
 *   see /api/bridge/ingest), not to relay it via gossip.
 */
@Service
public class RelayReceiptLedger {

    private static final Logger log = LoggerFactory.getLogger(RelayReceiptLedger.class);

    @Autowired private MeshNodeKeyRegistry nodeKeys;
    @Autowired private SignatureService signatureService;
    @Autowired private DeviceReputationService reputation;

    private final Map<String, List<RelayReceipt>> receiptsByPacketId = new ConcurrentHashMap<>();

    // Dedup key ("packetId:deviceId") so a repeated call to detectBlackHoles()
    // doesn't re-penalize the same device's reputation for the same
    // still-unresolved suspicion every time someone hits the API.
    private final Set<String> alreadyPenalized = ConcurrentHashMap.newKeySet();

    /**
     * Called once per gossip hop. Signs and stores the receipt, and credits
     * the forwarding device's reputation for having relayed the packet.
     */
    public RelayReceipt recordHop(String packetId, String fromDeviceId, String toDeviceId, int ttlAfterHop) {
        long now = System.currentTimeMillis();
        byte[] data = RelayReceipt.canonicalBytes(packetId, fromDeviceId, toDeviceId, ttlAfterHop, now);
        try {
            byte[] signature = signatureService.sign(data, nodeKeys.getPrivateKey(fromDeviceId));
            RelayReceipt receipt = new RelayReceipt(packetId, fromDeviceId, toDeviceId, ttlAfterHop, now,
                    Base64.getEncoder().encodeToString(signature));
            receiptsByPacketId.computeIfAbsent(packetId, k -> new CopyOnWriteArrayList<>()).add(receipt);
            reputation.recordForward(fromDeviceId);
            return receipt;
        } catch (Exception e) {
            // Signing failure shouldn't take down the whole gossip round —
            // log and skip the receipt for this hop rather than propagating.
            log.error("Failed to sign relay receipt for packet {} ({} -> {}): {}",
                    packetId, fromDeviceId, toDeviceId, e.getMessage());
            return null;
        }
    }

    /** Verifies a receipt's signature against the claimed sender's registered mesh key. */
    public boolean verify(RelayReceipt receipt) {
        PublicKey publicKey = nodeKeys.getPublicKey(receipt.fromDeviceId());
        if (publicKey == null) return false;
        try {
            byte[] sig = Base64.getDecoder().decode(receipt.signatureBase64());
            return signatureService.verify(receipt.canonicalBytes(), sig, publicKey);
        } catch (Exception e) {
            return false;
        }
    }

    public List<RelayReceipt> receiptsForPacket(String packetId) {
        return List.copyOf(receiptsByPacketId.getOrDefault(packetId, List.of()));
    }

    public List<RelayReceipt> allReceipts() {
        return receiptsByPacketId.values().stream()
                .flatMap(List::stream)
                .collect(Collectors.toList());
    }

    /**
     * Sweeps every known packet's receipts for black-hole evidence.
     *
     * @param bridgeNodeIds ids of devices with internet connectivity. A
     *                       bridge node holding a packet with hops left is
     *                       expected to upload it to the backend, not relay
     *                       it further via gossip — so it's excluded from
     *                       the "never forwarded" check.
     */
    public List<BlackHoleSuspicion> detectBlackHoles(Set<String> bridgeNodeIds) {
        List<BlackHoleSuspicion> suspicions = new ArrayList<>();

        for (Map.Entry<String, List<RelayReceipt>> entry : receiptsByPacketId.entrySet()) {
            String packetId = entry.getKey();
            List<RelayReceipt> receipts = entry.getValue();

            Set<String> devicesThatForwarded = receipts.stream()
                    .map(RelayReceipt::fromDeviceId)
                    .collect(Collectors.toSet());

            for (RelayReceipt r : receipts) {
                if (r.ttlAfterHop() <= 0) {
                    continue; // legitimately terminal — no obligation to forward further
                }
                String recipient = r.toDeviceId();
                if (bridgeNodeIds.contains(recipient)) {
                    continue; // expected to deliver to the backend instead, not gossip onward
                }
                if (devicesThatForwarded.contains(recipient)) {
                    continue; // it did forward — not a suspect
                }

                suspicions.add(new BlackHoleSuspicion(
                        packetId, recipient, r.ttlAfterHop(), r.timestampMillis(),
                        "received the packet with " + r.ttlAfterHop() +
                                " hop(s) remaining but produced no signed forwarding receipt"));

                String dedupKey = packetId + ":" + recipient;
                if (alreadyPenalized.add(dedupKey)) {
                    reputation.recordSuspectedDrop(recipient);
                }
            }
        }
        return suspicions;
    }

    public void clear() {
        receiptsByPacketId.clear();
        alreadyPenalized.clear();
    }
}
