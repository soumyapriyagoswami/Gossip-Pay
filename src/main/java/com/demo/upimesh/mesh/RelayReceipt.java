package com.demo.upimesh.mesh;

import java.nio.charset.StandardCharsets;

/**
 * A signed attestation that one mesh node handed a packet to another.
 *
 * Produced every time gossipOnce() copies a packet from a source device to a
 * destination device. Signed by the SOURCE device's mesh key (see
 * MeshNodeKeyRegistry), so it proves "fromDeviceId had this packet and
 * forwarded it to toDeviceId at this time" — the source is attesting to its
 * own forwarding action, the same way a courier signs a handoff manifest.
 *
 * This is the primitive black-hole detection is built on: if a node
 * received a packet (there exists a receipt naming it as `toDeviceId`) but
 * never appears as the `fromDeviceId` of any later receipt for that same
 * packet — despite having hops left to give — it looks like it swallowed
 * the packet instead of relaying it.
 *
 * NOTE on trust model: a receipt only proves the SENDER's side of a handoff
 * ("I sent it"), not that the recipient actually received it (a truly
 * malicious node could sign a receipt for a handoff that never completed,
 * e.g. to frame an innocent neighbor, or two colluding nodes could forge a
 * chain). Real deployments would pair this with a receiver-side
 * acknowledgement receipt too. This demo implements the sender-side receipt
 * because it's the minimum needed to demonstrate the detection mechanism
 * end-to-end; extending to two-sided receipts is a natural next step.
 */
public record RelayReceipt(
        String packetId,
        String fromDeviceId,
        String toDeviceId,
        int ttlAfterHop,
        long timestampMillis,
        String signatureBase64
) {

    /**
     * The exact bytes that get signed and later re-derived for verification.
     * Field order is pinned so signer and verifier always agree byte-for-byte.
     */
    public static byte[] canonicalBytes(String packetId, String fromDeviceId, String toDeviceId,
                                         int ttlAfterHop, long timestampMillis) {
        String s = String.join("|",
                nullSafe(packetId),
                nullSafe(fromDeviceId),
                nullSafe(toDeviceId),
                String.valueOf(ttlAfterHop),
                String.valueOf(timestampMillis)
        );
        return s.getBytes(StandardCharsets.UTF_8);
    }

    public byte[] canonicalBytes() {
        return canonicalBytes(packetId, fromDeviceId, toDeviceId, ttlAfterHop, timestampMillis);
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
