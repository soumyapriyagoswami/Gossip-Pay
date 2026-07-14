<div align="center">

# 📴 GossipPay

### **Offline-First UPI Payments over Bluetooth Mesh Networks**

*Send money with **zero internet** — from underground parking lots, disaster zones, crowded festivals, or remote villages — securely relayed through nearby devices until a connected bridge delivers the payment.*

---

**🔒 End-to-End Encrypted** • **📶 Internet-Free** • **📡 Bluetooth Mesh Gossip** • **⚡ Event-Driven** • **☁️ Kafka + Redis + PostgreSQL**



> **A secure offline payment architecture demonstrating Bluetooth mesh networking, hybrid cryptography, exactly-once transaction processing, distributed idempotency, and event-driven microservices with Spring Boot.**

</div>

---

**by [Soumyapriya Goswami](https://github.com/soumyapriyagoswami)**

[![Java](https://img.shields.io/badge/Java-17+-orange.svg?style=flat-square&logo=openjdk)](https://adoptium.net)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3-brightgreen.svg?style=flat-square&logo=spring)](https://spring.io)
[![Apache Kafka](https://img.shields.io/badge/Apache%20Kafka-Event--Driven-black?style=flat-square&logo=apachekafka)](https://kafka.apache.org/)
[![Redis](https://img.shields.io/badge/Redis-Distributed%20Cache-red?style=flat-square&logo=redis)](https://redis.io/)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-blue?style=flat-square&logo=postgresql)](https://www.postgresql.org/)
[![Docker Compose](https://img.shields.io/badge/Docker-Compose-2496ED?style=flat-square&logo=docker)](https://www.docker.com/)
[![License](https://img.shields.io/badge/license-Educational-blue.svg?style=flat-square)]()
[![PRs Welcome](https://img.shields.io/badge/PRs-welcome-ff69b4.svg?style=flat-square)]()
[![Made with ❤️ in India](https://img.shields.io/badge/Made%20with-%E2%9D%A4%EF%B8%8F%20in%20India-orange?style=flat-square)]()

**[Quick Start](#-how-to-run-it) · [Architecture](#-architecture) · [Event-Driven Architecture](#-event-driven-architecture-postgresql--redis--kafka) · [How It Works](#-the-demo-flow-step-by-step) · [The Hard Problems](#-the-three-hard-problems-and-how-theyre-solved) · [API](#-api-reference)**

</div>

---

## 🎬 The Pitch

> You're in a basement. Zero bars. Your friend needs ₹500 for the momo guy upstairs.
>
> You hit send anyway.
>
> Your phone encrypts the payment and beams it to every stranger's phone in Bluetooth range. Nobody can read it — they're just carrying it, like ants passing a leaf. The packet hops from phone to phone until *someone* — anyone — walks outside, hits 4G, and silently uploads it. The backend decrypts it, checks it's genuine, checks it hasn't already settled, and moves the money.
>
> **This repo is that backend** — plus a full software simulator of the mesh, so you can watch the entire flow happen on one laptop with zero real hardware.

If you've ever wondered how payments could survive a total network blackout, this is a working answer — encryption, signatures, idempotency, and an event-driven pipeline included.

---

## ⚡ What This Demo Actually Proves

| # | Claim | How it's proven |
|---|---|---|
| 1 | A payment can pass through **untrusted strangers' phones** without them reading or altering it | Hybrid RSA-OAEP + AES-256-GCM encryption |
| 2 | The **same payment reaching the backend 3 times at once still settles exactly once** | Atomic compare-and-set on the ciphertext hash |
| 3 | **Nobody can forge who sent it, and nobody can replay it later** | Ed25519 signatures + 24h freshness window |
| 4 | A **tampered packet is rejected**, never silently corrupted | AES-GCM auth tag fails to verify → hard reject |

You'll watch all four happen live in the dashboard.

---

## 🚀 How to Run It

### Prerequisites
Just **JDK 17+**. No database, no Redis, no Maven install — the wrapper handles everything.

```bash
java -version   # confirm you're on 17+
```

### Windows
```cmd
mvnw.cmd spring-boot:run
```

### Mac / Linux
```bash
./mvnw spring-boot:run
```

First run downloads Maven + dependencies (~90 MB, a couple of minutes). Every run after that starts in seconds.

### Open the dashboard

Once you see `Started UpiMeshApplication in X.XXX seconds`, go to:

### 👉 [http://localhost:8080](http://localhost:8080)

Stop anytime with `Ctrl+C`.

```bash
mvnw.cmd test   # run the full test suite, including the killer concurrency test
```

---

## 🕹️ The Demo Flow (Step by Step)

The dashboard has four buttons. Click them in order and watch the pipeline execute for real.

### 1️⃣ Compose a Payment — "📤 Inject into Mesh"

Pick sender, receiver, amount, PIN. The backend:
- Builds a `PaymentInstruction` with a unique nonce + timestamp
- **Signs it** with the sender's Ed25519 private key
- **Encrypts it** with the server's RSA public key (hybrid scheme)
- Wraps it in a `MeshPacket` (TTL = 5) and hands it to `phone-alice`

### 2️⃣ Run Gossip Rounds — "🔄 Run Gossip Round"

Every device holding a packet broadcasts it to every device in "Bluetooth range" (everyone, in this simulator). TTL ticks down each hop. After 2 rounds, every phone in the mesh holds a copy — exactly like it would after a few strangers walk past each other in real life.

### 3️⃣ Bridge Walks Outside — "📡 Bridges Upload to Backend"

`phone-bridge` is the one device with signal. It POSTs every packet it's carrying to `/api/bridge/ingest`. The backend then, in order:
hash ciphertext → claim it (dedupe) → decrypt → verify signature → check freshness → settle

Watch **Account Balances** move and a new row land in the **Transaction Ledger**.

### 4️⃣ Prove Idempotency — the headline feature

Run the concurrency test directly:

```bash
mvnw.cmd test -Dtest=IdempotencyConcurrencyTest#singlePacketDeliveredByThreeBridgesSettlesExactlyOnce
```

Three threads deliver the **exact same packet** simultaneously. Exactly one settles. Two get dropped as duplicates. The sender's balance moves by the amount **once** — not three times.

---

## 🏗️ Architecture
# Offline Mesh Payment System Architecture

```text
┌─────────────────────────────────────────────────────────────────────────┐
│                         SENDER PHONE (Offline)                          │
├─────────────────────────────────────────────────────────────────────────┤
│ PaymentInstruction {                                                    │
│   sender, receiver, amount, pinHash, nonce, timestamp                   │
│ }                                                                       │
│                                                                         │
│ 1. Sign with sender's Ed25519 PRIVATE key                               │
│ 2. Encrypt using server's RSA Public Key (Hybrid Encryption)            │
│                                                                         │
│ MeshPacket {                                                            │
│   packetId,                                                             │
│   ttl,                                                                  │
│   createdAt,                                                            │
│   ciphertext                                                            │
│ }                                                                       │
└──────────────────────────────┬──────────────────────────────────────────┘
                               │
                      Bluetooth Mesh Gossip
                               │
                               ▼
                  ┌────────────┴────────────┐
                  │                         │
            ┌──────────┐              ┌──────────┐
            │ Stranger │ ───────────▶ │ Stranger │
            │    #1    │              │    #2    │
            └──────────┘              └────┬─────┘
                                           │
                                           ▼
                                    ┌────────────┐
                                    │   Bridge   │
                                    │  Device    │
                                    └────┬───────┘
                                         │
                                 Internet Available
                                         │
                                         ▼
                                  HTTPS POST Request
                                         │
                                         ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                     SPRING BOOT BACKEND SERVER                          │
├─────────────────────────────────────────────────────────────────────────┤
│ Endpoint                                                                │
│                                                                         │
│ POST /api/bridge/ingest                                                 │
│                                                                         │
│ Processing Pipeline                                                     │
│                                                                         │
│ [1] SHA-256 Hash of Ciphertext                                          │
│      │                                                                  │
│      ▼                                                                  │
│ [2] IdempotencyService.claim(hash)                                      │
│      • Atomic putIfAbsent()                                             │
│      • Redis SETNX                                                      │
│      • Reject duplicate packets                                         │
│      │                                                                  │
│      ▼                                                                  │
│ [3] HybridCryptoService.decrypt()                                       │
│      • RSA-OAEP unwraps AES key                                         │
│      • AES-GCM decrypts payload                                         │
│      • Authentication verified                                          │
│      │                                                                  │
│      ▼                                                                  │
│ [4] PacketVerificationService                                           │
│      • Verify Ed25519 Digital Signature                                │
│      │                                                                  │
│      ▼                                                                  │
│ [5] Freshness Validation                                                │
│      • signedAt must be within last 24 hours                            │
│      • Prevent replay attacks                                           │
│      │                                                                  │
│      ▼                                                                  │
│ [6] SettlementService.settle()                                          │
│      @Transactional                                                     │
│      • Debit sender account                                             │
│      • Credit receiver account                                          │
│      • Write immutable ledger entry                                     │
│      • @Version optimistic locking                                      │
│        (defense against concurrent updates)                             │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## End-to-End Flow

1. The sender creates a `PaymentInstruction`.
2. The instruction is digitally signed using the sender's **Ed25519 private key**.
3. The signed payload is encrypted using **Hybrid Encryption**:
   - RSA-OAEP encrypts the AES session key.
   - AES-GCM encrypts and authenticates the payment payload.
4. The encrypted payload becomes a `MeshPacket`.
5. The packet propagates over Bluetooth using **store-and-forward mesh gossip**.
6. A bridge device with Internet connectivity uploads the packet to the backend.
7. The Spring Boot backend:
   - Rejects duplicate packets using SHA-256 + Redis SETNX.
   - Decrypts the packet.
   - Verifies the sender's digital signature.
   - Ensures the packet is fresh (within 24 hours).
   - Executes an atomic transaction to transfer funds.
   - Records the payment in the ledger.

---

## 🧩 The Three Hard Problems (and How They're Solved)

### 1. Untrusted intermediates
A random stranger's phone is carrying your transaction. **Hybrid encryption** (RSA-OAEP wraps a fresh AES-256 key, AES-256-GCM encrypts the payload) means intermediates see only opaque, tamper-evident ciphertext — flip one bit, and the GCM auth tag fails to verify. This is the same pattern TLS uses.

### 2. The duplicate storm
Three bridge phones upload the same packet within milliseconds of each other. Naively processing all three would triple-charge the sender.

**Fix:** the very first thing the server does is `SHA-256(ciphertext)` and atomically claim that hash via `ConcurrentHashMap.putIfAbsent` (in production: `Redis SET NX EX`). Exactly one caller wins the race; the rest are short-circuited as `DUPLICATE_DROPPED` — before any decryption or DB work happens. A unique DB index on `packet_hash` is the defense-in-depth backstop.

### 3. Replay attacks
An attacker who captured a ciphertext weeks ago could resend it later.

**Fix, two layers:**
- `signedAt` inside the encrypted payload is rejected if older than 24 hours — and it can't be altered without breaking the GCM tag.
- Each packet carries a unique nonce, so two *legitimate* ₹100 payments look different and both settle — but a byte-for-byte *replay* of one payment is caught by the idempotency cache.

### 4. Who really sent this?
Encryption proves only the server can *read* a packet — not who *wrote* it. Every `PaymentInstruction` is now signed with the sender's own Ed25519 private key on enrollment (`DeviceKeyRegistry`), and `PacketVerificationService` checks that signature against the sender's registered public key before a single rupee moves. A forged or altered instruction fails with `invalid_signature`, full stop.

---

## 📂 File-by-File Walkthrough
# Project Structure

```text
upi-offline-mesh/
│
├── pom.xml
│   Maven build configuration (Spring Boot 3.3, Java 17)
│
├── mvnw
├── mvnw.cmd
│   Maven Wrapper (build without installing Maven)
│
├── README.md
│   Project documentation
│
├── docker-compose.yml
│   Starts PostgreSQL, Redis, Kafka, and Kafka UI
│   (used in Event-Driven mode)
│
└── src/
    ├── main/
    │
    ├── resources/
    │   ├── application.properties
    │   │   Application configuration
    │   │   • H2 In-Memory Database
    │   │   • Server Port (8080)
    │   │   • Cache & TTL configuration
    │   │
    │   └── templates/
    │       └── dashboard.html
    │           Interactive dashboard for the demo
    │
    └── java/com/demo/upimesh/
        │
        ├── UpiMeshApplication.java
        │   Spring Boot application entry point
        │
        ├── model/
        │   Domain model and persistence layer
        │
        │   ├── Account.java
        │   │   JPA entity representing a bank account
        │   │   Uses @Version for optimistic locking
        │   │
        │   ├── AccountRepository.java
        │   │   Repository for account operations
        │   │
        │   ├── Transaction.java
        │   │   Ledger of settled transactions
        │   │   Unique constraint on packetHash
        │   │
        │   ├── TransactionRepository.java
        │   │   Repository for transaction records
        │   │
        │   ├── MeshPacket.java
        │   │   Outer packet exchanged over Bluetooth
        │   │   Metadata is visible, payload remains encrypted
        │   │
        │   └── PaymentInstruction.java
        │       Decrypted payment payload
        │       • Sender
        │       • Receiver
        │       • Amount
        │       • Nonce
        │       • Timestamp
        │       • Digital Signature
        │
        ├── crypto/
        │   Cryptographic services
        │
        │   ├── ServerKeyHolder.java
        │   │   Generates RSA-2048 server keypair on startup
        │   │
        │   ├── HybridCryptoService.java
        │   │   Implements Hybrid Encryption
        │   │   • RSA-OAEP
        │   │   • AES-256-GCM
        │   │   • SHA-256 packet hashing
        │   │
        │   ├── SignatureService.java
        │   │   Ed25519 signature generation & verification
        │   │
        │   ├── DeviceKeyRegistry.java
        │   │   Stores enrolled public keys for each VPA/device
        │   │
        │   └── PacketVerificationService.java
        │       Shared verification pipeline
        │       • Decrypt
        │       • Verify signature
        │       • Validate freshness
        │
        ├── service/
        │   Core business logic (Synchronous Pipeline)
        │
        │   ├── DemoService.java
        │   │   Seeds demo accounts and simulates sender devices
        │   │
        │   ├── VirtualDevice.java
        │   │   Represents a simulated Bluetooth device
        │   │
        │   ├── MeshSimulatorService.java
        │   │   Implements Bluetooth mesh gossip protocol
        │   │
        │   ├── IdempotencyService.java
        │   │   JVM-local implementation of Redis SETNX
        │   │
        │   ├── SettlementService.java
        │   │   Atomic settlement logic
        │   │   • Debit sender
        │   │   • Credit receiver
        │   │   • Write transaction ledger
        │   │
        │   └── BridgeIngestionService.java
        │       Main processing pipeline
        │       Hash → Claim → Decrypt → Verify → Settle
        │
        ├── gateway/
        │   Event-driven packet ingestion
        │
        │   └── PacketGatewayService.java
        │       • Rate limiting
        │       • Packet hashing
        │       • Kafka publishing
        │       • Returns HTTP 202 Accepted
        │
        ├── settlement/
        │   Settlement consumers
        │
        │   └── SettlementEventConsumer.java
        │       Kafka consumer
        │       • Redis idempotency
        │       • Decrypt
        │       • Verify
        │       • Execute settlement
        │
        ├── ledger/
        │   Double-entry accounting
        │
        │   ├── LedgerEntry.java
        │   │   Immutable ledger row
        │   │
        │   └── LedgerEventConsumer.java
        │       Creates DEBIT and CREDIT entries
        │
        ├── notification/
        │   Notification services
        │
        │   ├── NotificationLog.java
        │   │   Stores notification history
        │   │
        │   └── NotificationEventConsumer.java
        │       Sends settlement notifications
        │
        ├── cache/
        │   Distributed cache and rate limiting
        │
        │   ├── RedisIdempotencyStore.java
        │   │   Redis SETNX with TTL
        │   │
        │   └── RedisRateLimiterService.java
        │       Fixed-window rate limiting per bridge
        │
        ├── controller/
        │   REST Controllers
        │
        │   ├── ApiController.java
        │   │   Exposes all REST APIs
        │   │
        │   └── DashboardController.java
        │       Serves the dashboard at "/"
        │
        └── config/
            └── AppConfig.java
                Enables scheduling and cache cleanup

└── src/test/java/com/demo/upimesh/
    └── IdempotencyConcurrencyTest.java
        Tests:
        • Three bridge nodes submitting the same packet simultaneously
        • Packet tampering detection
        • Idempotency validation
```

## Architecture Layers

| Layer | Responsibility |
|--------|----------------|
| **Model** | JPA entities, repositories, packet formats |
| **Crypto** | Encryption, signatures, key management, verification |
| **Service** | Core business logic and payment settlement |
| **Gateway** | Event-driven packet ingestion via Kafka |
| **Settlement** | Kafka consumers performing transaction settlement |
| **Ledger** | Immutable double-entry accounting |
| **Notification** | Settlement notification processing |
| **Cache** | Redis-based idempotency and rate limiting |
| **Controller** | REST APIs and dashboard |
| **Configuration** | Scheduling and application configuration |
| **Tests** | Concurrency, tamper detection, and idempotency validation |

## 📡 API Reference

| Method | Path | What it does |
|---|---|---|
| GET | `/` | Dashboard HTML |
| GET | `/api/server-key` | Server's RSA public key (base64) |
| GET | `/api/devices/{vpa}/public-key` | A sender's Ed25519 public key |
| GET | `/api/accounts` | All accounts and balances |
| GET | `/api/transactions` | Last 20 transactions |
| GET | `/api/transactions/by-hash/{packetHash}` | Poll settlement status after an async `202` |
| GET | `/api/ledger`, `/api/ledger/{vpa}` | Immutable double-entry audit trail |
| GET | `/api/notifications` | Sent notifications |
| GET | `/api/mesh/state` | Current state of every virtual device |
| GET | `/api/pipeline-mode` | Which pipeline is active — sync or event-driven |
| POST | `/api/demo/send` | Simulate sender phone — sign, encrypt, inject packet |
| POST | `/api/mesh/gossip` | Run one round of gossip across the mesh |
| POST | `/api/mesh/flush` | Bridges with internet upload to backend (parallel) |
| POST | `/api/mesh/reset` | Clear mesh + idempotency cache |
| POST | `/api/bridge/ingest` | **The production endpoint.** Real bridges POST here |
| GET | `/h2-console` | Browse the in-memory database (default profile only) |

H2 console: JDBC URL `jdbc:h2:mem:upimesh`, user `sa`, no password.

<details>
<summary><b>Request format for <code>/api/bridge/ingest</code></b></summary>

```http
POST /api/bridge/ingest
Content-Type: application/json
X-Bridge-Node-Id: phone-bridge-42
X-Hop-Count: 3

{
  "packetId": "550e8400-e29b-41d4-a716-446655440000",
  "ttl": 2,
  "createdAt": 1730000000000,
  "ciphertext": "base64-encoded-RSA-and-AES-blob"
}
```

Response (synchronous / default profile):
```json
{
  "outcome": "SETTLED",
  "packetHash": "a3f8c9...",
  "reason": null,
  "transactionId": 42
}
```

Response (event-driven profile — returns immediately, poll `/api/transactions/by-hash/{packetHash}` for the result):
```json
{ "status": "ACCEPTED", "packetHash": "a3f8c9..." }
```

</details>

---

## 🔥 The Event-Driven Stack (Postgres + Redis + Kafka)

Everything above still runs with **zero setup** using H2 and an in-memory map. Flip on the `event-driven` Spring profile for a horizontally-scalable version of the exact same guarantees:
'''
## 🔥 Event-Driven Architecture (PostgreSQL + Redis + Kafka)

The project runs **out of the box** with **H2** and an **in-memory idempotency store**, requiring no external infrastructure.

For production-scale deployments, enable the **`event-driven` Spring profile** to switch to a distributed architecture backed by **PostgreSQL**, **Redis**, and **Apache Kafka**, while preserving the same security and correctness guarantees.

```text
                         Phone
                           │
                           │
                  PaymentInstruction
                           │
                           ▼
                    MeshPacket
      (Bluetooth mesh gossip through untrusted devices)
                           │
                           ▼
┌────────────────────────────────────────────────────────────┐
│                  API Gateway Service                       │
│------------------------------------------------------------│
│ • Rate limiting (Redis)                                    │
│ • SHA-256 packet hashing                                   │
│ • Publish packet to Kafka                                  │
│ • Returns HTTP 202 Accepted                                │
└──────────────────────────────┬─────────────────────────────┘
                               │
                               ▼
                Kafka Topic: payments.packet.received
                               │
                               ▼
┌────────────────────────────────────────────────────────────┐
│                Settlement Service                          │
│------------------------------------------------------------│
│ • Redis SETNX idempotency claim                            │
│ • Hybrid decryption (RSA-OAEP + AES-GCM)                   │
│ • Ed25519 signature verification                           │
│ • Freshness validation                                     │
│ • Debit sender account                                     │
│ • Credit receiver account                                  │
│ • Persist transaction in PostgreSQL                        │
└──────────────────────────────┬─────────────────────────────┘
                               │
                               ▼
            Kafka Topic: payments.settlement.completed
                               │
                               ▼
┌────────────────────────────────────────────────────────────┐
│                   Ledger Service                           │
│------------------------------------------------------------│
│ • Create immutable DEBIT entry                             │
│ • Create immutable CREDIT entry                            │
│ • Store double-entry ledger records                        │
└──────────────────────────────┬─────────────────────────────┘
                               │
                               ▼
             Kafka Topic: payments.ledger.recorded
                               │
                               ▼
┌────────────────────────────────────────────────────────────┐
│                Notification Service                        │
│------------------------------------------------------------│
│ • Notify sender                                             │
│ • Notify receiver                                           │
│ • Persist notification logs                                │
└────────────────────────────────────────────────────────────┘
```

### Event Flow

1. **Phone** creates a signed and encrypted `MeshPacket`.
2. The packet propagates through the **Bluetooth mesh** until it reaches an Internet-connected bridge.
3. The **Gateway Service**:
   - Applies rate limiting.
   - Computes a SHA-256 hash of the packet.
   - Publishes the packet to the `payments.packet.received` Kafka topic.
   - Immediately returns **HTTP 202 Accepted**.
4. The **Settlement Service** consumes the event:
   - Claims idempotency using Redis (`SETNX`).
   - Decrypts the payload.
   - Verifies the Ed25519 signature.
   - Checks packet freshness.
   - Executes the debit/credit transaction in PostgreSQL.
5. After settlement, a `payments.settlement.completed` event is published.
6. The **Ledger Service** records immutable **DEBIT** and **CREDIT** entries using double-entry accounting.
7. Finally, the **Notification Service** consumes the ledger event and notifies both the sender and receiver while storing notification logs.

### Benefits of the Event-Driven Architecture

| Component | Responsibility |
|-----------|----------------|
| **Kafka** | Reliable asynchronous event streaming between services |
| **Redis** | Distributed idempotency (`SETNX`) and rate limiting |
| **PostgreSQL** | Durable transactional storage and account settlement |
| **Settlement Service** | Validates and processes payments exactly once |
| **Ledger Service** | Maintains immutable double-entry accounting records |
| **Notification Service** | Delivers payment confirmations asynchronously |

### Key Guarantees

- Exactly-once payment settlement
- Distributed idempotency using Redis
- Immutable financial ledger
- End-to-end cryptographic verification
- Horizontally scalable microservice architecture
- Asynchronous processing with Kafka
- Fault tolerance through durable event streams
- Independent scaling of gateway, settlement, ledger, and notification services

Each stage only touches the Kafka topic it needs — `gateway`, `settlement`, `ledger`, and `notification` share nothing but the `events`, `model`, and `crypto` packages, so any one of them could become its own deployable service without touching the others.
'''
### Run it

```bash
docker compose up -d --build
# Postgres:  localhost:5432
# Redis:     localhost:6379
# Kafka:     localhost:9092
# Kafka UI:  http://localhost:8090   (watch topics/messages live)
# App:       http://localhost:8080
```

Or run the app locally against infra you start separately:

```bash
docker compose up -d postgres redis kafka
./mvnw spring-boot:run -Dspring-boot.run.profiles=event-driven
```

Check which mode is live: `GET /api/pipeline-mode`.

---

## 🧪 Tests

```bash
mvnw.cmd test
```

| Test | What it proves |
|---|---|
| `encryptDecryptRoundTrip` | Hybrid encryption is symmetric |
| `tamperedCiphertextIsRejected` | Flip one byte → `INVALID`, never a crash or a silent settlement |
| `singlePacketDeliveredByThreeBridgesSettlesExactlyOnce` | **The headline test.** 3 threads, 1 packet, simultaneous delivery → exactly one `SETTLED`, two `DUPLICATE_DROPPED`, sender debited exactly once |

---

## 🎭 What's NOT Real (and What Changes for Production)

| In this demo | In production |
|---|---|
| H2 in-memory DB (default profile) | PostgreSQL with replicas (event-driven profile) |
| `ConcurrentHashMap` idempotency (default) | Redis `SET NX EX` (event-driven profile) |
| RSA keypair regenerated every startup | Private key in an HSM — AWS KMS / HashiCorp Vault |
| Ed25519 device keys simulated server-side | Generated on-device, in the Secure Enclave / StrongBox |
| Software-simulated mesh | Real BLE GATT or Wi-Fi Direct between phones |
| One settlement service owning the ledger | Integration with NPCI / a real bank core |
| No auth on `/api/bridge/ingest` | Mutual TLS or signed bridge-node certificates |
| In-memory seeded accounts | Real KYC'd users, real VPAs, real PIN verification |
| H2 console exposed | Disabled entirely |
| No rate limiting (default profile) | Per-bridge-node + per-sender velocity limits (built into event-driven profile) |
| Console logging | Structured logs to a SIEM, alerts on `INVALID` / `invalid_signature` spikes |

The cryptography, signature verification, and idempotency logic are essentially production-shaped already. It's the infrastructure around them that changes.

---

## 🧠 Honest Limitations of the Concept

These aren't bugs — they're what "no internet, anywhere in the chain" *inherently* cannot solve:

1. **The receiver can't verify the sender actually has the funds at the moment of sending.** A phone showing "₹500 sent" while offline is an IOU, not a settlement. If the sender's balance is empty by the time the packet reaches the backend, it settles as `REJECTED` and the receiver has no recourse. This is precisely why real offline UPI (**UPI Lite**) uses a pre-funded, hardware-backed wallet — to prove funds are available *without* a network round-trip.
2. **A malicious sender can double-spend offline.** ₹500 in the account, one packet to Bob in basement A, another ₹500 to Carol in basement B — whichever reaches the backend first wins, the other is `REJECTED`. Same root cause as #1.
3. **Bluetooth in real life is genuinely hard.** Background BLE on Android has been heavily throttled since Android 8. iOS peripheral mode is locked down. Two strangers' phones reliably forming a GATT connection while neither app is in the foreground is a real, unsolved-at-scale problem. This demo sidesteps it entirely with a software simulator.
4. **Metadata is not nothing.** A stranger can't read your transaction, but the fact that it exists on their phone is still information. Any real deployment needs a real answer for device seizure and regulatory disclosure.

**Honest framing for a portfolio or interview:** call this **"mesh-routed deferred settlement,"** not "real-time offline UPI." The crypto, signatures, and idempotency engineering here are genuinely solid and worth showing off exactly as they are.

---

## 🛠️ Troubleshooting

| Problem | Fix |
|---|---|
| `java: command not found` | Install JDK 17+. Windows: `winget install EclipseAdoptium.Temurin.17.JDK` |
| Port 8080 already in use | Change `server.port` in `application.properties` |
| First `mvnw.cmd` run hangs | Downloading Maven + deps (~90 MB) — give it 2–3 min |
| `'mvnw.cmd' is not recognized` (PowerShell) | Prefix with `.\`: `.\mvnw.cmd spring-boot:run` |
| Concurrency test flakes | Timing-sensitive by nature — rerun 3x; if it consistently fails, that's a real bug worth filing |

---

## ⭐ Support This Project

If this demo taught you something about encryption, idempotency, or event-driven architecture — **star the repo**. It genuinely helps other learners find it.

Contributions, issues, and forks are welcome. If you build a Kotlin/Android port of the sender side, open a PR — I'd love to link it here.

---

<div align="center">

**Built by [Soumyapriya Goswami](https://github.com/soumyapriyagoswami)**

*Educational project — no license restrictions, use it however helps you learn.*

</div>