package com.demo.upimesh.controller;

import com.demo.upimesh.cache.IdempotencyStore;
import com.demo.upimesh.crypto.DeviceKeyRegistry;
import com.demo.upimesh.crypto.ServerKeyHolder;
import com.demo.upimesh.gateway.PacketGatewayService;
import com.demo.upimesh.ledger.LedgerRepository;
import com.demo.upimesh.model.*;
import com.demo.upimesh.notification.NotificationRepository;
import com.demo.upimesh.service.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;

/**
 * Public REST surface.
 *
 *   /api/server-key              -> server's RSA public encryption key
 *   /api/devices/{vpa}/public-key -> a user's Ed25519 SIGNATURE public key
 *   /api/mesh/*                  -> simulator endpoints (inject, gossip, flush)
 *   /api/bridge/ingest           -> the real production endpoint a bridge node hits.
 *                                    Routes to the legacy synchronous pipeline OR the
 *                                    event-driven Kafka Gateway, depending on which
 *                                    profile is active.
 *   /api/accounts, /api/transactions, /api/ledger, /api/notifications -> dashboard data
 */
@RestController
@RequestMapping("/api")
public class ApiController {

    @Autowired private ServerKeyHolder serverKey;
    @Autowired private DeviceKeyRegistry deviceKeys;
    @Autowired private DemoService demo;
    @Autowired private MeshSimulatorService mesh;
    @Autowired private BridgeIngestionService bridge; // legacy synchronous path
    @Autowired private AccountRepository accountRepo;
    @Autowired private TransactionRepository txRepo;
    @Autowired private IdempotencyStore idempotency;
    @Autowired private LedgerRepository ledgerRepo;
    @Autowired private NotificationRepository notificationRepo;

    // Only present when the "event-driven" profile is active.
    @Autowired(required = false) private PacketGatewayService gateway;

    // ------------------------------------------------------------------ keys

    @GetMapping("/server-key")
    public Map<String, String> getServerPublicKey() {
        return Map.of(
                "publicKey", serverKey.getPublicKeyBase64(),
                "algorithm", "RSA-2048 / OAEP-SHA256",
                "hybridScheme", "RSA-OAEP encrypts an AES-256-GCM session key"
        );
    }

    @GetMapping("/devices/{vpa}/public-key")
    public ResponseEntity<?> getDeviceSignaturePublicKey(@PathVariable String vpa) {
        if (!deviceKeys.isEnrolled(vpa)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "no signing key enrolled for " + vpa));
        }
        return ResponseEntity.ok(Map.of(
                "vpa", vpa,
                "publicKey", deviceKeys.publicKeyBase64(vpa),
                "algorithm", "Ed25519"
        ));
    }

    @GetMapping("/pipeline-mode")
    public Map<String, Object> pipelineMode() {
        boolean eventDriven = gateway != null;
        return Map.of(
                "mode", eventDriven ? "EVENT_DRIVEN" : "SYNCHRONOUS",
                "description", eventDriven
                        ? "Phone -> Gateway -> Kafka -> Settlement -> Ledger -> Notification"
                        : "Phone -> Bridge -> BridgeIngestionService (in-process)"
        );
    }

    // ---------------------------------------------------------------- demo

    @PostMapping("/demo/send")
    public ResponseEntity<?> demoSend(@RequestBody DemoSendRequest req) throws Exception {
        MeshPacket packet = demo.createPacket(
                req.senderVpa, req.receiverVpa, req.amount, req.pin,
                req.ttl == null ? 5 : req.ttl);

        String startDevice = req.startDevice == null ? "phone-alice" : req.startDevice;
        mesh.inject(startDevice, packet);

        return ResponseEntity.ok(Map.of(
                "packetId", packet.getPacketId(),
                "ciphertextPreview", packet.getCiphertext().substring(0, 64) + "...",
                "ttl", packet.getTtl(),
                "injectedAt", startDevice
        ));
    }

    public static class DemoSendRequest {
        public String senderVpa;
        public String receiverVpa;
        public BigDecimal amount;
        public String pin;
        public Integer ttl;
        public String startDevice;
    }

    // -------------------------------------------------------------- mesh sim

    @GetMapping("/mesh/state")
    public Map<String, Object> meshState() {
        List<Map<String, Object>> deviceData = new ArrayList<>();
        for (VirtualDevice d : mesh.getDevices()) {
            deviceData.add(Map.of(
                    "deviceId", d.getDeviceId(),
                    "hasInternet", d.hasInternet(),
                    "packetCount", d.packetCount(),
                    "packetIds", d.getHeldPackets().stream()
                            .map(p -> p.getPacketId().substring(0, 8))
                            .toList()
            ));
        }
        return Map.of("devices", deviceData);
    }

    @PostMapping("/mesh/gossip")
    public Map<String, Object> meshGossip() {
        MeshSimulatorService.GossipResult r = mesh.gossipOnce();
        return Map.of(
                "transfers", r.transfers(),
                "deviceCounts", r.deviceCounts()
        );
    }

    /**
     * "All bridge nodes simultaneously walk outside and get 4G."
     * They all upload everything they hold to /api/bridge/ingest.
     *
     * In SYNCHRONOUS mode this settles inline and results come back immediately.
     * In EVENT_DRIVEN mode this only publishes to Kafka — settlement happens
     * asynchronously, so "results" here just means "accepted by the gateway".
     * Poll /api/transactions or /api/ledger afterwards to see the outcome.
     */
    @PostMapping("/mesh/flush")
    public Map<String, Object> meshFlush() {
        List<MeshSimulatorService.BridgeUpload> uploads = mesh.collectBridgeUploads();
        List<Map<String, Object>> results = new ArrayList<>();

        uploads.parallelStream().forEach(up -> {
            Map<String, Object> result;
            int hopCount = 5 - up.packet().getTtl();
            if (gateway != null) {
                PacketGatewayService.GatewayResult r = gateway.accept(up.packet(), up.bridgeNodeId(), hopCount);
                result = new HashMap<>(Map.of(
                        "bridgeNode", up.bridgeNodeId(),
                        "packetId", up.packet().getPacketId().substring(0, 8),
                        "outcome", r.accepted() ? "PUBLISHED_TO_KAFKA" : "GATEWAY_REJECTED"
                ));
                result.put("reason", r.reason() == null ? "" : r.reason());
            } else {
                BridgeIngestionService.IngestResult r = bridge.ingest(up.packet(), up.bridgeNodeId(), hopCount);
                result = new HashMap<>(Map.of(
                        "bridgeNode", up.bridgeNodeId(),
                        "packetId", up.packet().getPacketId().substring(0, 8),
                        "outcome", r.outcome(),
                        "transactionId", r.transactionId() == null ? -1 : r.transactionId()
                ));
                result.put("reason", r.reason() == null ? "" : r.reason());
            }
            synchronized (results) { results.add(result); }
        });

        return Map.of(
                "uploadsAttempted", uploads.size(),
                "results", results
        );
    }

    @PostMapping("/mesh/reset")
    public Map<String, Object> meshReset() {
        mesh.resetMesh();
        idempotency.clear();
        return Map.of("status", "mesh and idempotency cache cleared");
    }

    // -------------------------------------------------------------- bridge

    /**
     * THE PRODUCTION ENDPOINT.
     * Routes to the event-driven Gateway (Kafka publish, returns 202 immediately)
     * when that profile is active, otherwise falls back to the legacy synchronous
     * in-process pipeline (returns the settlement result directly).
     */
    @PostMapping("/bridge/ingest")
    public ResponseEntity<?> ingest(
            @RequestBody MeshPacket packet,
            @RequestHeader(value = "X-Bridge-Node-Id", defaultValue = "unknown") String bridgeNodeId,
            @RequestHeader(value = "X-Hop-Count", defaultValue = "0") int hopCount) {

        if (gateway != null) {
            PacketGatewayService.GatewayResult r = gateway.accept(packet, bridgeNodeId, hopCount);
            if (!r.accepted()) {
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(r);
            }
            return ResponseEntity.accepted().body(r);
        }

        BridgeIngestionService.IngestResult r = bridge.ingest(packet, bridgeNodeId, hopCount);
        return ResponseEntity.ok(r);
    }

    // ------------------------------------------------------------- accounts

    @GetMapping("/accounts")
    public List<Account> listAccounts() {
        return accountRepo.findAll();
    }

    @GetMapping("/transactions")
    public List<Transaction> listTransactions() {
        return txRepo.findTop20ByOrderByIdDesc();
    }

    @GetMapping("/transactions/by-hash/{packetHash}")
    public ResponseEntity<?> transactionByHash(@PathVariable String packetHash) {
        return txRepo.findAll().stream()
                .filter(t -> t.getPacketHash().equals(packetHash))
                .findFirst()
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("status", "PENDING_OR_UNKNOWN")));
    }

    // --------------------------------------------------------------- ledger

    @GetMapping("/ledger")
    public Object ledger() {
        return ledgerRepo.findTop50ByOrderByIdDesc();
    }

    @GetMapping("/ledger/{vpa}")
    public Object ledgerForAccount(@PathVariable String vpa) {
        return ledgerRepo.findByAccountVpaOrderByIdDesc(vpa);
    }

    // ------------------------------------------------------------ notifications

    @GetMapping("/notifications")
    public Object notifications() {
        return notificationRepo.findTop50ByOrderByIdDesc();
    }
}
