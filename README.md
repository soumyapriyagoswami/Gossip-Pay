<div align="center">

# 📡 GossipPay

### Offline UPI Payments, Routed Through a Bluetooth-Style Mesh Network

*Send money with zero internet. The mesh finds a way.*

<br/>

[![Java](https://img.shields.io/badge/Java-17+-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)](https://adoptium.net)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.5-6DB33F?style=for-the-badge&logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-4169E1?style=for-the-badge&logo=postgresql&logoColor=white)](https://www.postgresql.org)
[![Redis](https://img.shields.io/badge/Redis-7-DC382D?style=for-the-badge&logo=redis&logoColor=white)](https://redis.io)
[![Kafka](https://img.shields.io/badge/Kafka-KRaft-231F20?style=for-the-badge&logo=apachekafka&logoColor=white)](https://kafka.apache.org)
[![Docker](https://img.shields.io/badge/Docker-Compose-2496ED?style=for-the-badge&logo=docker&logoColor=white)](https://www.docker.com)
[![CI/CD](https://img.shields.io/github/actions/workflow/status/soumyapriyagoswami/Gossip-Pay/cicd.yml?branch=main&style=for-the-badge&logo=githubactions&logoColor=white&label=CI%2FCD)](https://github.com/soumyapriyagoswami/Gossip-Pay/actions/workflows/cicd.yml/badge.svg)
[![License](https://img.shields.io/badge/License-Demo%20%2F%20Learning-lightgrey?style=for-the-badge)](#license)

<br/>

**Built by [Soumyapriya Goswami](https://github.com/)**

[![GitHub](https://img.shields.io/badge/GitHub-Soumyapriya%20Goswami-181717?style=flat-square&logo=github&logoColor=white)](https://github.com/soumyapriyagoswami)

[![LinkedIn](https://img.shields.io/badge/LinkedIn-Connect-0A66C2?style=flat-square&logo=linkedin&logoColor=white)](https://www.linkedin.com/in/soumyapriya-goswami-9bb8a4288/)
</div>

---

## 💡 The Idea

You're in a basement with **zero connectivity**. You send your friend ₹500. Your phone encrypts the payment, broadcasts it to nearby phones over Bluetooth, and the packet hops device-to-device until *some* phone walks outside, gets 4G, and silently uploads it to the backend — which decrypts, deduplicates, and settles it.

**GossipPay** is the server side of that system, plus a software simulator of the mesh, so the entire flow can be demoed on a single laptop with no real Bluetooth hardware.

The project ships two complete runtimes:
- **Zero-setup mode** — `./mvnw spring-boot:run`, H2 in-memory DB, in-JVM idempotency cache. Nothing to install but a JDK.
- **`event-driven` profile** — a real Kafka → PostgreSQL → Redis pipeline via Docker Compose, for demonstrating how this scales past a single instance.

---

## 🚀 Quick Start

### Prerequisites
JDK 17+ on `PATH` (check with `java -version`). Nothing else for the default mode.

### Run it (zero setup)

```bash
# Windows
mvnw.cmd spring-boot:run

# Mac / Linux
./mvnw spring-boot:run
```

Open **http://localhost:8080** for the interactive dashboard — compose a payment, run gossip rounds, flush bridges, and watch balances and the ledger update live.

### Run the full event-driven stack

```bash
docker compose up -d --build
# Postgres:  localhost:5432
# Redis:     localhost:6379
# Kafka:     localhost:9092
# Kafka UI:  http://localhost:8090
# App:       http://localhost:8080
```

Or run the app locally against infra started separately:

```bash
docker compose up -d postgres redis kafka
./mvnw spring-boot:run -Dspring-boot.run.profiles=event-driven
```

Check which mode is active any time: `GET /api/pipeline-mode`.

### Run the tests

```bash
./mvnw test
```

`mvn clean verify` also runs automatically on every push and pull request via GitHub Actions (`.github/workflows/cicd.yml`).

---

## 🧩 The 5 Hard Problems — and How GossipPay Solves Them

### 1️⃣ Untrusted Intermediaries — *"A stranger's phone is carrying my money"*

**Problem:** Every hop between sender and backend is a random stranger's device. If that phone can read the payment or tamper with the amount, the whole system is worthless.

**Solution — Hybrid Encryption (RSA-OAEP + AES-256-GCM)**, `crypto/HybridCryptoService.java`:
- A fresh AES-256 key is generated per packet and encrypts the JSON payload.
- The AES key itself is encrypted with the server's RSA-2048 public key (`crypto/ServerKeyHolder.java`).
- AES-GCM is *authenticated* — flipping even one bit in transit breaks the auth tag, and decryption throws instead of silently corrupting data.
- Intermediates only ever see opaque ciphertext; only the server's private key can open it.

### 2️⃣ The Duplicate-Storm — *"Three phones deliver the same payment at once"*

**Problem:** If a packet reaches the backend through multiple bridge nodes simultaneously, naive processing debits the sender multiple times for one payment.

**Solution — Atomic Compare-and-Set on the Ciphertext Hash**, `cache/IdempotencyStore.java`:
- The server computes `SHA-256(ciphertext)` and atomically "claims" it — `InMemoryIdempotencyStore` (`ConcurrentHashMap.putIfAbsent`) in the default profile, `RedisIdempotencyStore` (`SETNX`) in the event-driven profile.
- Only the first claimant proceeds to decrypt and settle; every other delivery is dropped as `DUPLICATE_DROPPED` before any CPU is spent decrypting.
- A unique DB index on `Transaction.packetHash` acts as a defense-in-depth backstop if the cache layer ever fails.
- Proven under load by `IdempotencyConcurrencyTest` — three threads deliver one packet simultaneously, exactly one settles.

### 3️⃣ Replay Attacks — *"An old, captured packet gets resent later"*

**Problem:** An attacker who captures a legitimate ciphertext could replay it at any point in the future to trigger settlement again.

**Solution — Freshness Window + Per-Packet Nonce**, checked in `crypto/PacketVerificationService.java`:
- Every payload carries a `signedAt` timestamp; packets older than 24 hours are rejected.
- A unique nonce is embedded per packet so two *legitimate* payments never collide, while a true replay is byte-identical ciphertext and gets caught by the idempotency cache.
- Both fields sit inside the GCM-authenticated payload, so they can't be rewritten without breaking decryption.

### 4️⃣ "Who Really Sent This?" — *Encryption Alone Doesn't Prove Identity*

**Problem:** Hybrid encryption proves a packet can only be *read* by the server — it never proves *who wrote it*. A forged instruction with the right shape could still slip through.

**Solution — Ed25519 Digital Signatures**, `crypto/SignatureService.java` + `crypto/DeviceKeyRegistry.java`:
- Every device enrolls its own Ed25519 keypair (`GET /api/devices/{vpa}/public-key`).
- Each `PaymentInstruction` is signed by the **sender's private key** before encryption.
- On receipt, `PacketVerificationService` decrypts, looks up the sender's public key, and verifies the signature — a forged or tampered instruction fails with `invalid_signature` before any money moves. This shared verification path is used by both the synchronous and event-driven pipelines.

### 5️⃣ Silent Failures & Malicious Relays — *"What if a packet vanishes mid-processing, or a phone in the mesh drops it?"*

**Problem:** Two failure modes threaten reliability: (a) a transient crash *after* a packet is claimed permanently strands it as a false duplicate, and (b) a broken or malicious relay node can silently swallow a packet instead of forwarding it, with no way to detect the loss.

**Solution — Idempotency State Machine + Signed Relay Receipts:**
- Claims move through `PENDING → SETTLED / FAILED` (`IdempotencyStore`) instead of a single permanent boolean.
  - `cache/IdempotencyReaperJob.java` releases claims stuck in `PENDING` past a timeout (dead process mid-flight).
  - `cache/IdempotencyReconciliationJob.java` cross-checks the cache against the `transactions` table on a schedule and reports drift — visible live at `GET /api/idempotency/status`.
- Every gossip hop in `MeshSimulatorService` produces a signed `mesh/RelayReceipt.java`, using a per-device Ed25519 keypair (`crypto/MeshNodeKeyRegistry.java`) separate from the payer/payee signing keys.
  - `mesh/RelayReceiptLedger.java` runs **black-hole detection** — flagging a device that had hops left to give but never appears as the sender of a later receipt (`GET /api/mesh/black-holes`).
  - `mesh/DeviceReputationService.java` maintains a per-device trust score, weighted so confirmed drops hurt more than successful forwards help (`GET /api/mesh/reputation`).
- All covered end-to-end by `ReliabilityAndMeshSecurityTest.java`.

---

## 🏗️ Architecture

```
Sender Phone
   │  sign (Ed25519) → encrypt (RSA-OAEP + AES-256-GCM)
   ▼
MeshPacket ──Bluetooth gossip (signed relay receipts each hop)──▶ Bridge Node ──4G──▶ Backend
                                                                                          │
                                                        hash → idempotency claim → verify
                                                        signature → freshness check → settle
                                                                                          │
                                                              Immutable Ledger + Notifications
```

### Default profile (zero setup)
```
Phone → BridgeIngestionService → H2 (Account / Transaction) → InMemoryIdempotencyStore
```

### `event-driven` profile
```
Phone → PacketGatewayService (rate-limit → hash → publish, returns 202)
      → Kafka: payments.packet.received
      → SettlementEventConsumer (Redis claim → verify → debit/credit in Postgres)
      → Kafka: payments.settlement.completed
      → LedgerEventConsumer (immutable double-entry rows)
      → Kafka: payments.ledger.recorded
      → NotificationEventConsumer (notifies sender + receiver)
```

Both pipelines share the same `crypto`, `model`, and `cache` packages — `PacketVerificationService` and `IdempotencyStore` behave identically regardless of which one is active.

---

## 🔐 Deep Dive: ACID Guarantees in `SettlementService`

Money movement happens in exactly one place — `SettlementService.settle()` — wrapped in a single `@Transactional` boundary around the debit, the credit, and the ledger write. Every ACID property is doing real work here, not just checkbox compliance:

| Property | How it's enforced | What breaks without it |
|---|---|---|
| **Atomicity** | Debit sender, credit receiver, and insert the `Transaction` row all happen inside one `@Transactional` method. If any step throws (unknown VPA, DB error) the whole thing rolls back — there is no state where money left one account but never reached the other. | A crash between the debit and the credit would burn money into thin air. |
| **Consistency** | Balance is checked (`sender.getBalance().compareTo(amount) < 0`) *before* any mutation. Insufficient funds short-circuits into a `REJECTED` transaction record instead of a partial debit. The unique index on `Transaction.packetHash` also enforces "one packet, one settlement outcome" as a hard DB constraint, not just an application-level assumption. | Balances could go negative, or the same packet could be recorded as settled twice. |
| **Isolation** | `Account.version` is a JPA `@Version` field — **optimistic locking**. If two transactions somehow race to mutate the same account concurrently, the second commit fails with `OptimisticLockException` instead of silently overwriting the first write (a classic lost-update bug). This is deliberate defense-in-depth: the idempotency claim should already prevent two settlements for the same packet, but `@Version` protects the account row itself against *any* concurrent mutation, from any source. | Two simultaneous transfers touching the same account could silently lose one of the updates ("last write wins"). |
| **Durability** | Once `settle()` commits, the row is durably in H2 (default) or PostgreSQL (event-driven) — not just cached in memory. Every subsequent read (dashboard, `/api/transactions`, `/api/ledger`) reflects it. | A process crash right after "success" would make a settled payment disappear. |

Two outcomes are both first-class, durable results of the *same* transactional method — `settle()` doesn't only handle the happy path:
- **`SETTLED`** — balances moved, ledger entry written.
- **`REJECTED`** — insufficient funds; still gets a permanent `Transaction` row (status `REJECTED`) so the sender/receiver have an auditable record of *why* nothing moved, instead of the packet just silently vanishing.

This is also exactly what backs Problem #5's idempotency state machine: `markSettled()` is only called once one of these two `Transaction` rows durably exists — a `REJECTED` outcome is still "fully processed," so a retry of the same packet won't re-attempt the debit.

---

## 🕳️ Deep Dive: Black-Hole Detection

A **black hole** is a mesh device that receives a packet with hops left to give — meaning it was expected to keep relaying — but never forwards it anywhere. Maybe its battery died, maybe the app crashed, maybe it's actively malicious and dropping payments on purpose. Either way, the payment silently stops moving, and nobody would know if not for this mechanism.

**Step 1 — Every hop leaves signed evidence.**
`MeshSimulatorService.gossipOnce()` calls `RelayReceiptLedger.recordHop()` for every device-to-device forward. Each hop produces a `RelayReceipt`, an attestation *signed by the sending device's own Ed25519 key* (`crypto/MeshNodeKeyRegistry.java` — a mesh-layer keypair, distinct from the account-level signing keys used for `PaymentInstruction`):

> "I, `fromDeviceId`, forwarded packet `P` to `toDeviceId` at time `T`, with `ttlAfterHop` hops still remaining."

Because it's signed, a receipt can't be forged by a third party impersonating the sending device — `RelayReceiptLedger.verify()` checks it against that device's registered mesh public key.

**Step 2 — Reconstruct the forwarding graph.**
For any packet, `receiptsByPacketId` holds every hop that actually happened. `detectBlackHoles()` builds the set of devices that appear as a **sender** (`fromDeviceId`) in at least one receipt — i.e., every device that's *proven* to have relayed something onward.

**Step 3 — Find the gap.**
For every receipt where a device received the packet (`toDeviceId`) with `ttlAfterHop > 0` — meaning it had both the obligation and the ability to keep relaying — the sweep checks: did this device ever show up as a `fromDeviceId` for this same packet afterward?

- **No, and it's not a bridge node** → flagged as a `BlackHoleSuspicion`: *"received the packet with N hop(s) remaining but produced no signed forwarding receipt."*
- **No, but it's a bridge node** (`hasInternet=true`) → not flagged. Its expected next move is uploading to `/api/bridge/ingest`, an entirely different code path, not gossiping further.
- **`ttlAfterHop <= 0`** → not flagged. The packet legitimately reached the end of its hop budget; there was no obligation to forward it further.

**Step 4 — Consequences.**
Each first-time suspicion (deduplicated per `packetId:deviceId` via `alreadyPenalized`, so repeated API polling doesn't double-penalize) feeds `DeviceReputationService.recordSuspectedDrop()`. Trust score is a simple weighted ratio:

```
score = forwarded / (forwarded + suspectedDrops × 4.0)
```

A fresh device starts at a neutral `1.0`. Suspected drops are weighted **4× heavier** than successful forwards — a handful of confirmed black-hole events should tank a device's trust fast, while it takes a long streak of good behavior to earn that trust back. This mirrors how real fraud/trust systems are usually deliberately asymmetric.

**What this does *not* claim to prove** (stated plainly in the code): a receipt only proves the sending device's side of a handoff. Two colluding devices could still forge a consistent-looking chain between themselves, or one node could sign a receipt for a handoff that never actually completed, framing an innocent neighbor. Closing that gap fully would need a **receiver-side acknowledgement receipt** as well — this demo implements only the sender-side signature, the minimum needed to demonstrate the detection mechanism end-to-end.

Inspect it live: `GET /api/mesh/relay-receipts/{packetId}` for raw evidence, `GET /api/mesh/black-holes` to run the sweep, `GET /api/mesh/reputation` for the resulting trust scores.

---

## 📁 Project Layout

```
com/demo/upimesh/
├── cache/          Idempotency state machine, reaper & reconciliation jobs, Redis rate limiter
├── config/         Spring config: scheduling, Kafka topics, Redis
├── controller/     REST API + dashboard controller
├── crypto/         RSA/AES hybrid encryption, Ed25519 signing, per-device & per-mesh-node keys
├── events/         Kafka event payloads + topic names
├── gateway/        Event-driven ingress: rate-limit → hash → publish
├── ledger/         Immutable double-entry audit trail
├── mesh/           Signed relay receipts, black-hole detection, device reputation
├── model/          JPA entities: Account, Transaction, MeshPacket, PaymentInstruction
├── notification/   Post-settlement notification log
├── service/        Synchronous pipeline: bridge ingestion, settlement, mesh simulator
└── settlement/      Event-driven settlement consumer
```

---

## 📡 API Reference

| Method | Path | Purpose |
|---|---|---|
| GET | `/` | Dashboard UI |
| GET | `/api/server-key` | Server's RSA public key |
| GET | `/api/devices/{vpa}/public-key` | A device's Ed25519 public key |
| GET | `/api/pipeline-mode` | Which pipeline (default / event-driven) is active |
| POST | `/api/demo/send` | Simulate a sender phone — sign, encrypt, inject packet |
| GET | `/api/mesh/state` | Current state of every virtual device |
| POST | `/api/mesh/gossip` | Run one gossip round across the mesh |
| POST | `/api/mesh/flush` | Bridges with internet upload held packets |
| POST | `/api/mesh/reset` | Clear mesh, relay receipts, idempotency cache |
| GET | `/api/mesh/relay-receipts/{packetId}` | Signed relay receipts for one packet |
| GET | `/api/mesh/black-holes` | Run the black-hole detection sweep |
| GET | `/api/mesh/reputation` | Per-device trust scores |
| GET | `/api/idempotency/status` | Claim counts + reconciliation drift report |
| POST | `/api/bridge/ingest` | **The production endpoint** — real bridges POST here |
| GET | `/api/accounts` | All accounts and balances |
| GET | `/api/transactions` | Recent transactions |
| GET | `/api/transactions/by-hash/{packetHash}` | Poll settlement status after an async `202` |
| GET | `/api/ledger`, `/api/ledger/{vpa}` | Immutable audit trail |
| GET | `/api/notifications` | Sent notifications |

---

## ⚠️ Honest Limitations

This is a teaching / portfolio project, and it's worth naming the concept honestly: **mesh-routed deferred settlement**, not "real-time offline UPI."

- The receiver can't verify the sender's funds exist until the packet reaches the backend — it's an IOU until settled.
- A malicious sender could double-spend across two disconnected basements; whichever packet lands first wins.
- Real background Bluetooth mesh on modern phones (Android throttling, iOS peripheral restrictions) is genuinely hard — this demo simulates it in software.
- A signed relay receipt only proves the sender's side of a handoff; a colluding pair of nodes could still forge a chain. A production system would pair this with receiver-side acknowledgements too.

The cryptography, idempotency, and reliability engineering here are real and production-shaped; the surrounding infrastructure (H2 → managed Postgres, simulated mesh → real BLE, in-memory device registry → HSM-backed keys) is what would change for production.

---

## License

Demo code, no license. Use it however you want for learning.

<div align="center">

**Made with ☕ and stubborn persistence by Soumyapriya Goswami**

</div>