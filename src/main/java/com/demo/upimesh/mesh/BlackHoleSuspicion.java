package com.demo.upimesh.mesh;

/**
 * A finding produced by RelayReceiptLedger.detectBlackHoles(): a device that
 * received a packet with hops left to give but never produced a receipt
 * showing it forwarded that packet onward.
 */
public record BlackHoleSuspicion(
        String packetId,
        String suspectDeviceId,
        int ttlAtReceipt,
        long receivedAtMillis,
        String reason
) {}
