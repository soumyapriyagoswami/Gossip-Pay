package com.demo.upimesh.events;

/**
 * Emitted by the Gateway for every packet accepted from a bridge phone.
 * This is the ONLY thing that crosses the wire between Gateway and Settlement
 * Service — they are decoupled through Kafka, not through a direct method call.
 *
 * Carries the still-encrypted ciphertext; the Settlement Service is the one
 * that holds the private key and is allowed to decrypt it.
 */
public record PacketReceivedEvent(
        String packetHash,      // SHA-256 of ciphertext — idempotency key end-to-end
        String bridgeNodeId,    // which phone/bridge uploaded it
        int hopCount,           // how many mesh hops it took
        String ciphertext,      // base64 hybrid-encrypted PaymentInstruction
        long packetCreatedAt,   // epoch millis, set by the sender's phone
        long receivedAt         // epoch millis, set by the Gateway
) {}
