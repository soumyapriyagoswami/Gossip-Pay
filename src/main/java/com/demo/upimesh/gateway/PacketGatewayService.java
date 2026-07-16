package com.demo.upimesh.gateway;

import com.demo.upimesh.cache.RedisRateLimiterService;
import com.demo.upimesh.crypto.HybridCryptoService;
import com.demo.upimesh.events.KafkaTopics;
import com.demo.upimesh.events.PacketReceivedEvent;
import com.demo.upimesh.model.MeshPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * The "Gateway" stage of the event-driven pipeline:
 *
 *   Phone -> [Gateway] -> Kafka -> Settlement Service -> Ledger Service -> Notification Service
 *
 * The Gateway is intentionally dumb and fast: it does NOT decrypt anything
 * and does NOT touch the database. Its only jobs are:
 *   1. Rate-limit the source (protect the pipeline from a flooding bridge).
 *   2. Compute the packetHash (needed as the Kafka message key + idempotency key).
 *   3. Publish a PacketReceivedEvent and return immediately.
 *
 * This is what makes it "event driven" rather than a synchronous call chain:
 * the HTTP request completes the instant the event is on the Kafka topic —
 * the caller doesn't wait for settlement, ledger writes, or notifications.
 */
@Service
@Profile("event-driven")
public class PacketGatewayService {

    private static final Logger log = LoggerFactory.getLogger(PacketGatewayService.class);

    @Autowired private HybridCryptoService crypto;
    @Autowired private RedisRateLimiterService rateLimiter;
    @Autowired private KafkaTemplate<String, Object> kafkaTemplate;

    public GatewayResult accept(MeshPacket packet, String bridgeNodeId, int hopCount) {
        if (!rateLimiter.allow(bridgeNodeId)) {
            log.warn("Rate limit exceeded for bridge node {}", bridgeNodeId);
            return GatewayResult.rejected("rate_limited");
        }

        String packetHash;
        try {
            packetHash = crypto.hashCiphertext(packet.getCiphertext());
        } catch (Exception e) {
            return GatewayResult.rejected("malformed_packet");
        }

        PacketReceivedEvent event = new PacketReceivedEvent(
                packetHash,
                bridgeNodeId,
                hopCount,
                packet.getCiphertext(),
                packet.getCreatedAt() == null ? 0L : packet.getCreatedAt(),
                Instant.now().toEpochMilli()
        );

        // Key by packetHash so every event for the same packet lands on the
        // same partition and is processed in order by the same consumer.
        kafkaTemplate.send(KafkaTopics.PACKET_RECEIVED, packetHash, event);
        log.info("Gateway accepted packet {} from bridge {} -> published to {}",
                packetHash.substring(0, 12) + "...", bridgeNodeId, KafkaTopics.PACKET_RECEIVED);

        return GatewayResult.accepted(packetHash);
    }

    public record GatewayResult(boolean accepted, String packetHash, String reason) {
        public static GatewayResult accepted(String hash) { return new GatewayResult(true, hash, null); }
        public static GatewayResult rejected(String reason) { return new GatewayResult(false, null, reason); }
    }
}
