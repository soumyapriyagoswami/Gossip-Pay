# UPI Offline Mesh — Demo

A Spring Boot backend that demonstrates **offline UPI payments routed through a Bluetooth-style mesh network**. You're in a basement with zero connectivity. You send your friend ₹500. Your phone encrypts the payment, broadcasts it to nearby phones, and the packet hops device-to-device until *some* phone walks outside, gets 4G, and silently uploads it to this backend. The backend decrypts, deduplicates, and settles.

This repo is the **server side** of that system, plus a software simulator of the mesh so you can demo the whole flow on a single laptop without any real Bluetooth hardware.

---

## Table of Contents

1. [What this demo proves](#what-this-demo-proves)
2. [How to run it](#how-to-run-it)
3. [The demo flow (step by step)](#the-demo-flow-step-by-step)
4. [Architecture](#architecture)
5. [The three hard problems and how they're solved](#the-three-hard-problems-and-how-theyre-solved)
6. [File-by-file walkthrough](#file-by-file-walkthrough)
7. [API reference](#api-reference)
8. [Tests](#tests)
9. [What's NOT real (and what would change for production)](#whats-not-real-and-what-would-change-for-production)
10. [Honest limitations of the concept](#honest-limitations-of-the-concept)

---

## What this demo proves

The system shows three things working end to end:

1. **A payment can travel from sender to backend through untrusted intermediaries** without any of them being able to read or tamper with it. (Hybrid RSA + AES-GCM encryption.)
2. **Even if the same payment reaches the backend simultaneously through multiple bridge nodes, it settles exactly once.** (Idempotency via atomic compare-and-set on the ciphertext hash.)
3. **A tampered or replayed packet is rejected** before it touches the ledger.

You'll see all three in the dashboard.

---

## How to run it

### Prerequisites

- **JDK 17 or newer** installed and on PATH (or `JAVA_HOME` set). Check with `java -version`.
- That's it. No database, no Redis, no Maven (the wrapper handles it). Just Java.

### Run on Windows

Open a terminal in the project folder and run:

```cmd
mvnw.cmd spring-boot:run
```

The first run downloads Maven (~10 MB) and all dependencies (~80 MB) — give it a couple of minutes. Subsequent runs start in a few seconds.

### Run on Mac/Linux

```bash
./mvnw spring-boot:run
```

### Open the dashboard

Once you see `Started UpiMeshApplication in X.XXX seconds`, open:

**http://localhost:8080**

You'll get a dark dashboard with everything you need to drive the demo.

### Stop the server

`Ctrl+C` in the terminal.

### Run the tests

```cmd
mvnw.cmd test
```

The interesting one is `IdempotencyConcurrencyTest` — it fires three threads delivering the same packet simultaneously and asserts that exactly one settles.

---

## The demo flow (step by step)

The dashboard has four buttons that walk through the full pipeline. The intended sequence:

### Step 1 — Compose a payment

Choose sender, receiver, amount, PIN. Click **"📤 Inject into Mesh"**.

**What actually happens on the backend:**
- The server pretends to be the sender's phone.
- It builds a `PaymentInstruction` with a unique nonce and current timestamp.
- It encrypts that with the server's RSA public key (using hybrid encryption — see below).
- It wraps the ciphertext in a `MeshPacket` with a TTL of 5.
- It hands the packet to `phone-alice`, an offline virtual device.

You'll see `phone-alice` now holds 1 packet.

### Step 2 — Run gossip rounds

Click **"🔄 Run Gossip Round"**. Then click it again.

Each round, every device that holds a packet broadcasts it to every other device within "Bluetooth range" (which, in our simulator, means everyone). TTL decrements per hop.

After 1 round: every device holds the packet. After 2 rounds: still every device — TTL is just lower.

In the real system this would happen organically as people walk past each other in the basement.

### Step 3 — Bridge node walks outside

Click **"📡 Bridges Upload to Backend"**.

`phone-bridge` is the only device with `hasInternet=true`. The dashboard simulates that phone walking outside and getting 4G. It POSTs every packet it holds to `/api/bridge/ingest`.

The backend pipeline runs:
1. Hash the ciphertext (`SHA-256`).
2. Try to claim the hash in the idempotency cache.
3. If claimed: decrypt with the server's RSA private key.
4. Verify freshness (signedAt within 24 hours).
5. Run the debit/credit in a single DB transaction.

Watch the **Account Balances** table — money has moved. Watch the **Transaction Ledger** — a new row appears.

### Step 4 — Demonstrate idempotency (the killer feature)

Reset the mesh. Inject a single packet. Run gossip 2 times. Now **all 5 devices hold the same packet, including multiple bridges in a more complex setup**.

To really see idempotency in action, modify `MeshSimulatorService.java` to seed multiple bridge devices, or just:

1. Click "Inject" once.
2. Click "Gossip" twice.
3. Click "Flush Bridges" — only `phone-bridge` is a bridge in the default seed, so just one upload happens.

To exercise the *concurrent duplicate* case properly, run the test:
```cmd
mvnw.cmd test -Dtest=IdempotencyConcurrencyTest#singlePacketDeliveredByThreeBridgesSettlesExactlyOnce
```

This test creates one packet, fires 3 threads at `BridgeIngestionService.ingest()` simultaneously, and verifies that exactly one settles, two are dropped as duplicates, and the sender is debited exactly once.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         SENDER PHONE (offline)                          │
│  PaymentInstruction { sender, receiver, amount, pinHash, nonce, time }  │
│              │                                                          │
│              ▼ encrypt with server's RSA public key                     │
│   MeshPacket { packetId, ttl, createdAt, ciphertext }                   │
└──────────────────────────────────────┬──────────────────────────────────┘
                                       │ Bluetooth gossip
                                       ▼
        ┌─────────┐  hop   ┌─────────┐  hop   ┌─────────┐
        │stranger1│ ─────▶ │stranger2│ ─────▶ │ bridge  │ ◀── walks outside
        └─────────┘        └─────────┘        └────┬────┘     gets 4G
                                                   │
                                                   ▼ HTTPS POST
┌─────────────────────────────────────────────────────────────────────────┐
│                     SPRING BOOT BACKEND (this project)                  │
│                                                                         │
│  /api/bridge/ingest                                                     │
│       │                                                                 │
│       ▼                                                                 │
│  [1] hash ciphertext (SHA-256)                                          │
│       │                                                                 │
│       ▼                                                                 │
│  [2] IdempotencyService.claim(hash)  ◀── atomic putIfAbsent (≈ Redis    │
│       │                                  SETNX). Duplicates rejected    │
│       │                                  here, before any work.         │
│       ▼                                                                 │
│  [3] HybridCryptoService.decrypt(ciphertext)                            │
│       │       (RSA-OAEP unwraps AES key, AES-GCM decrypts payload       │
│       │        AND verifies the auth tag — tampering = exception)       │
│       ▼                                                                 │
│  [4] Freshness check: signedAt within last 24h                          │
│       │                                                                 │
│       ▼                                                                 │
│  [5] SettlementService.settle()                                         │
│       @Transactional: debit sender, credit receiver, write ledger       │
│       @Version on Account = optimistic locking (defense in depth)       │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## The three hard problems and how they're solved

### Problem 1: Untrusted intermediates

A random stranger's phone is carrying your transaction. How do you stop them from reading the amount or changing it?

**Solution: Hybrid encryption (RSA-OAEP + AES-GCM).**

The sender encrypts the payload with the server's public key. Only the server holds the private key, so intermediates see opaque ciphertext.

But RSA can only encrypt small data (~245 bytes for a 2048-bit key), and our payload is JSON that could exceed that. So we use the standard hybrid pattern:

1. Generate a fresh AES-256 key for *this packet*.
2. Encrypt the JSON with **AES-256-GCM** (fast + authenticated).
3. Encrypt just the AES key with **RSA-OAEP**.
4. Concatenate: `[256 bytes RSA-encrypted AES key][12 bytes IV][AES ciphertext + 16-byte GCM tag]`.

**Why GCM specifically?** It's authenticated encryption. If an intermediate flips one bit anywhere in the ciphertext, decryption throws an exception — the GCM tag won't verify. The server cannot be tricked into processing tampered data.

This is the same scheme TLS uses. See `HybridCryptoService.java`.

### Problem 2: The duplicate-storm

Three bridge nodes hold the same packet. They all walk outside at the same instant. They all POST to `/api/bridge/ingest` within milliseconds of each other. If you naively process all three, the sender is debited ₹1500 instead of ₹500.

**Solution: Atomic compare-and-set on the ciphertext hash.**

The very first thing the server does on receiving a packet is compute `SHA-256(ciphertext)` and try to "claim" that hash:

```java
// IdempotencyService.java
Instant prev = seen.putIfAbsent(packetHash, now);
return prev == null;  // true = first claimer, false = duplicate
```

`ConcurrentHashMap.putIfAbsent` is atomic. Even if 100 threads call it at the exact same nanosecond, exactly one returns `null` (the first claimer) and the rest return the existing entry. Only the first claimer proceeds to decrypt and settle. The rest are short-circuited as `DUPLICATE_DROPPED`.

**Why hash the ciphertext, not the packetId or the cleartext?**
- `packetId` can be rewritten by a malicious intermediate. Two copies of the same payment could have different packetIds. Bad key.
- The cleartext requires decryption first. We want to dedupe *before* spending CPU on RSA.
- The ciphertext is authenticated by GCM, so any tampering is detectable on decrypt. Two legitimate deliveries of the same payment have byte-identical ciphertexts (AES is deterministic for a given key+IV+plaintext, and the same packet means the same key+IV+plaintext).

In production this `ConcurrentHashMap` becomes Redis: `SET key NX EX 86400`. Same semantics, distributed across replicas.

There's also a defense-in-depth fallback: `transactions.packet_hash` has a unique index. If the cache layer ever fails and two settlements somehow try to write the same hash, the database rejects the second one.

### Problem 3: Replay attacks

An attacker who captured a ciphertext weeks ago could replay it whenever convenient.

**Solution: Two layers.**

1. **Inside the encrypted payload**, the sender includes `signedAt` (epoch millis). The server rejects any packet older than 24 hours. The attacker can't change `signedAt` without breaking the GCM tag.
2. **Inside the encrypted payload**, the sender includes a **nonce** (UUID). Even if Alice legitimately sends Bob ₹100 twice, the nonces differ → ciphertexts differ → hashes differ → both settle. But a *replay* of one specific signed packet is byte-identical, so the idempotency cache catches it.

See `BridgeIngestionService.java` for the freshness check.

---

## File-by-file walkthrough

```
upi-offline-mesh/
├── pom.xml                                  Maven build, Spring Boot 3.3, Java 17
├── mvnw, mvnw.cmd                           Maven wrapper (no install needed)
├── README.md                                this file
└── src/main/
    ├── resources/
    │   ├── application.properties           H2 in-memory DB, port 8080, TTLs
    │   └── templates/dashboard.html         The interactive demo UI
    └── java/com/demo/upimesh/
        ├── UpiMeshApplication.java          Spring Boot main class
        │
        ├── model/                           ── Domain layer
        │   ├── Account.java                 JPA entity. @Version = optimistic lock
        │   ├── AccountRepository.java       Spring Data JPA
        │   ├── Transaction.java             Settled-tx ledger. unique idx on packetHash
        │   ├── TransactionRepository.java   Spring Data JPA
        │   ├── MeshPacket.java              Wire format. Outer fields readable, ciphertext opaque
        │   └── PaymentInstruction.java      Decrypted payload (sender/receiver/amount/nonce/time)
        │
        ├── crypto/                          ── Cryptography layer
        │   ├── ServerKeyHolder.java         Generates RSA-2048 keypair on startup
        │   └── HybridCryptoService.java     RSA-OAEP + AES-256-GCM encrypt/decrypt + ciphertext hash
        │
        ├── service/                         ── Business logic
        │   ├── DemoService.java             Seeds accounts, simulates a sender phone
        │   ├── VirtualDevice.java           One simulated phone in the mesh
        │   ├── MeshSimulatorService.java    Gossip protocol across virtual devices
        │   ├── IdempotencyService.java      ConcurrentHashMap = JVM-local Redis SETNX
        │   ├── SettlementService.java       @Transactional debit + credit + ledger insert
        │   └── BridgeIngestionService.java  THE pipeline: hash → claim → decrypt → freshness → settle
        │
        ├── controller/                      ── HTTP layer
        │   ├── ApiController.java           All REST endpoints
        │   └── DashboardController.java     Serves the dashboard HTML at /
        │
        └── config/
            └── AppConfig.java               @EnableScheduling for cache eviction

src/test/java/com/demo/upimesh/
└── IdempotencyConcurrencyTest.java          The 3-bridges-at-once test + tamper test
```

---

## API reference

| Method | Path | What it does |
|---|---|---|
| GET | `/` | Dashboard HTML |
| GET | `/api/server-key` | Server's RSA public key (base64) |
| GET | `/api/accounts` | All accounts and balances |
| GET | `/api/transactions` | Last 20 transactions |
| GET | `/api/mesh/state` | Current state of every virtual device |
| POST | `/api/demo/send` | Simulate sender phone — encrypt + inject packet |
| POST | `/api/mesh/gossip` | Run one round of gossip across the mesh |
| POST | `/api/mesh/flush` | Bridges with internet upload to backend (parallel) |
| POST | `/api/mesh/reset` | Clear mesh + relay-receipt ledger + idempotency cache |
| POST | `/api/bridge/ingest` | **The production endpoint.** Real bridges POST here |
| GET | `/api/mesh/relay-receipts/{packetId}` | Signed relay receipts collected for one packet |
| GET | `/api/mesh/black-holes` | Run black-hole detection sweep over all known receipts |
| GET | `/api/mesh/reputation` | Per-device trust scores derived from forwarding behavior |
| GET | `/api/idempotency/status` | Pending/settled claim counts + last reconciliation drift report |
| GET | `/h2-console` | Browse the in-memory database |

H2 console login: JDBC URL `jdbc:h2:mem:upimesh`, username `sa`, no password.

### Request format for `/api/bridge/ingest`

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

Response:
```json
{
  "outcome": "SETTLED",                     // or "DUPLICATE_DROPPED" or "INVALID"
  "packetHash": "a3f8c9...",
  "reason": null,                            // populated on INVALID
  "transactionId": 42                        // populated on SETTLED
}
```

---

## Tests

Run all tests:
```
mvnw.cmd test
```

The three included tests:

- **`encryptDecryptRoundTrip`** — sanity-check that hybrid encryption is symmetric.
- **`tamperedCiphertextIsRejected`** — flip a byte in the ciphertext, verify that `BridgeIngestionService` returns `INVALID` instead of crashing or settling.
- **`singlePacketDeliveredByThreeBridgesSettlesExactlyOnce`** — the headline test. Three threads, one packet, simultaneous delivery. Asserts exactly one `SETTLED`, two `DUPLICATE_DROPPED`, and that the sender's balance changed by exactly the amount once.

---

## What's NOT real (and what would change for production)

This is a teaching demo. To make it production-grade you'd swap these things:

| What's in the demo | What it would be in production |
|---|---|
| H2 in-memory DB | PostgreSQL / MySQL with replicas |
| `ConcurrentHashMap` for idempotency | Redis with `SET NX EX` |
| RSA keypair regenerated on every startup | Private key in HSM (AWS KMS, HashiCorp Vault). Public key cached on devices. |
| Server-side `DemoService.createPacket()` | Same code running on Android, in a Kotlin port |
| Software-simulated mesh (`MeshSimulatorService`) | Real BLE GATT or Wi-Fi Direct between phones |
| One settlement service that owns the ledger | Integration with NPCI / a real bank core |
| No auth on `/api/bridge/ingest` | Mutual TLS or signed bridge-node certificates |
| In-memory accounts seeded on startup | Real KYC'd users, real VPAs, real PIN verification against the bank |
| H2 console exposed | Disabled |
| No rate limiting | Per-bridge-node rate limit, per-sender velocity check |
| Logs to console | Structured logs to a SIEM, alerts on `INVALID` spikes |

The cryptography and idempotency code is essentially production-shaped. The infrastructure around it is what changes.

---

## Honest limitations of the concept

I want this README to be useful to you when someone reviews the project, so let's be straight about what this design **does not** solve. These are not implementation bugs — they're inherent to "no internet, anywhere in the chain":

1. **The receiver has no way to verify the sender has the funds.** When sender hands receiver a phone showing "₹500 sent," it's an IOU, not a settled payment. If the sender's account is empty when the packet finally reaches the backend, the settlement will be `REJECTED` and the receiver is out ₹500 with no recourse. *This is why real offline UPI (UPI Lite) uses a pre-funded hardware-backed wallet* — to give cryptographic proof of available funds offline.
2. **A malicious sender can double-spend offline.** With ₹500 in their account, they could send a packet to Bob in basement A, walk to basement B, and send another ₹500 to Carol. Whichever packet hits the backend first wins; the other gets `REJECTED`. Same root cause as #1.
3. **Bluetooth in real life is hard.** Background BLE on Android is heavily throttled since Android 8. iOS peripheral mode is locked down. Two strangers' phones reliably forming a GATT connection while the apps aren't actively open is genuinely difficult and a lot of energy. This demo skips that problem entirely by simulating the mesh.
4. **Privacy / liability.** A stranger carries your encrypted transaction packet on their phone. They can't read it, but its existence is metadata. In a real deployment you'd want to think about regulatory disclosures and what happens if a device is seized.

For a college / portfolio project: name the concept honestly as **"mesh-routed deferred settlement"** rather than "real-time offline UPI," and you'll have a much stronger pitch. The cryptography and idempotency work here is real engineering and worth showing off.

---

## Troubleshooting

**`java: command not found`** — Install JDK 17+. On Windows, `winget install EclipseAdoptium.Temurin.17.JDK` or download from adoptium.net.

**Port 8080 already in use** — Change `server.port` in `application.properties`.

**First `mvnw.cmd` run hangs for a long time** — It's downloading Maven (~10 MB) then dependencies (~80 MB). Give it 2–3 minutes on a normal connection. After that, startup is ~5 seconds.

**`mvnw.cmd : The term 'mvnw.cmd' is not recognized`** — On PowerShell you need to prefix with `.\`: `.\mvnw.cmd spring-boot:run`.

**Tests fail intermittently** — The concurrency test is timing-sensitive. If it ever flakes, run it 3x; if it consistently fails on your hardware, file the actual failure output.

---

## License

Demo code, no license. Use it however you want for learning.

---

## NEW: Event-driven architecture, PostgreSQL + Redis, and digital signatures

Three major additions on top of the original demo. All three are backward
compatible — the original zero-setup `./mvnw spring-boot:run` still works
exactly as before with H2 and no external infra.

### 1. Digital signatures (closes the "who really sent this?" gap)

Encryption (`HybridCryptoService`) only proved a packet could be *read* solely
by the server. It never proved *who wrote it*. Now every `PaymentInstruction`
carries an Ed25519 signature, produced by the **sender's own private key**
(simulated per-device in `crypto/DeviceKeyRegistry`, separate from the
server's RSA keypair):

```
Sender's phone
   │
   ├─ generates Ed25519 keypair on enrollment (crypto/DeviceKeyRegistry)
   ├─ signs the PaymentInstruction with its PRIVATE key (crypto/SignatureService)
   ▼
Signed instruction  →  encrypted with the server's RSA public key (unchanged)
   ▼
MeshPacket  →  gossips through untrusted phones exactly as before
   ▼
Receiver (server, acting for the bank)
   ├─ decrypts with its private key
   ├─ looks up sender's PUBLIC key
   └─ verifies signature (crypto/PacketVerificationService) → reject if invalid
```

New/changed files:
- `crypto/SignatureService.java` — sign/verify with Ed25519
- `crypto/DeviceKeyRegistry.java` — per-VPA keypair enrollment
- `crypto/PacketVerificationService.java` — shared decrypt + freshness + signature check, used by both the legacy and event-driven pipelines
- `model/PaymentInstruction.java` — new `signature` field
- `service/DemoService.java` — enrolls each demo account's device key, signs every packet it creates
- New endpoint: `GET /api/devices/{vpa}/public-key`

A forged instruction (right shape, wrong signer) or a tampered field now fails
with `invalid_signature` before any money moves — try it by decoding a packet,
flipping the amount, and re-encrypting; the signature won't match anymore.

### 2. PostgreSQL + Redis

Active under the `event-driven` Spring profile (see below).

| Concern | Table/Store | Notes |
|---|---|---|
| Users' balances | `accounts` (Postgres) | unchanged entity, now Postgres-backed |
| Payments / settlement outcomes | `transactions` (Postgres) | unchanged entity |
| Immutable audit trail | `ledger_entries` (Postgres, NEW) | one DEBIT + one CREDIT row per settled payment — see `ledger/LedgerEntry.java` |
| Notifications sent | `notifications` (Postgres, NEW) | `notification/NotificationLog.java` |
| Duplicate packet / idempotency cache | Redis (`cache/RedisIdempotencyStore.java`) | atomic `SETNX` + TTL, shared across every instance |
| Rate limiting | Redis (`cache/RedisRateLimiterService.java`) | fixed-window `INCR` per bridge node, applied at the Gateway |

The idempotency contract (`IdempotencyStore` interface) is implemented twice:
`InMemoryIdempotencyStore` (default profile, single JVM) and
`RedisIdempotencyStore` (event-driven profile, distributed) — same guarantee,
different scope.

### 3. Event-driven pipeline

```
Phone
  │  MeshPacket (still gossips through untrusted intermediaries, unchanged)
  ▼
Gateway (gateway/PacketGatewayService)
  │  rate-limit → hash → publish, returns 202 immediately
  ▼
Kafka topic: payments.packet.received
  ▼
Settlement Service (settlement/SettlementEventConsumer)
  │  idempotency claim (Redis) → decrypt+verify signature → debit/credit (Postgres)
  ▼
Kafka topic: payments.settlement.completed
  ▼
Ledger Service (ledger/LedgerEventConsumer)
  │  writes immutable double-entry rows
  ▼
Kafka topic: payments.ledger.recorded
  ▼
Notification Service (notification/NotificationEventConsumer)
     notifies sender + receiver
```

Each stage only depends on the topic it consumes/produces, not on any other
stage's code — genuinely decoupled, and each package (`gateway`, `settlement`,
`ledger`, `notification`) could be pulled out into its own deployable Spring
Boot app later without touching the others; they already only share the
`events` and `model`/`crypto` packages.

The original synchronous pipeline (`service/BridgeIngestionService.java`)
still exists and is used automatically when the `event-driven` profile is
**not** active — same security checks (via the now-shared
`PacketVerificationService`), just called directly instead of via Kafka.
`ApiController` picks whichever path is available at runtime.

### Running the event-driven stack

```bash
docker compose up -d --build
# Postgres:  localhost:5432
# Redis:     localhost:6379
# Kafka:     localhost:9092
# Kafka UI:  http://localhost:8090   (inspect topics/messages live)
# App:       http://localhost:8080
```

Or run the app locally against infra started by `docker compose up -d postgres redis kafka`:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=event-driven
```

Check which mode is active: `GET /api/pipeline-mode`.

New/changed endpoints:
- `GET /api/ledger`, `GET /api/ledger/{vpa}` — immutable audit trail
- `GET /api/notifications` — sent notifications
- `GET /api/transactions/by-hash/{packetHash}` — poll settlement status after an async `202 Accepted`
- `POST /api/bridge/ingest` — now returns `202 Accepted` (event-driven mode) instead of the settlement result inline

---

## NEW: Idempotency state machine, reaper/reconciliation jobs, and mesh-layer security

A further set of additions on top of the event-driven architecture above,
addressing two gaps: (1) a transient failure could permanently strand a
packet as a false duplicate, and (2) the mesh had no way to detect a
malicious or broken relay node that swallows packets instead of forwarding
them.

### 1. Idempotency state machine: PENDING → SETTLED / FAILED

The old idempotency API was a single boolean `claim()`: once a hash was
claimed, it stayed "claimed" forever (until the TTL expired), full stop.
That's fine for the happy path, but it means **any transient error after the
claim — a decrypt hiccup, a DB blip, a pod crash — permanently strands the
packet as a false duplicate**, since every retry sees the hash as already
taken and drops it. The money never actually moves, and nothing ever
retries it.

`IdempotencyStore` (`cache/IdempotencyStore.java`) now models this properly:

```
claim(key)
   |
   v
PENDING --- markSettled(key) --> SETTLED   (terminal, kept for the full TTL —
   |                                        this is the durable dedup record)
   |
   +------ markFailed(key) -----> released (key deleted immediately, next
   |                                        claim() attempt starts fresh)
   |
   +------ releaseExpiredPending() (the reaper does this if nobody ever
                                     called markSettled/markFailed at all —
                                     e.g. the process died mid-processing)
```

Both `InMemoryIdempotencyStore` (default profile) and `RedisIdempotencyStore`
(event-driven profile) implement the same state machine. `BridgeIngestionService`
and `SettlementEventConsumer` (the two ingestion pipelines) both now:
- `markSettled()` only once a **Transaction row durably exists** — whether
  the outcome was `SETTLED` or a business `REJECTED` (insufficient balance),
  either way that packetHash has been fully and finally processed.
- `markFailed()` on any exception during verification or settlement, so the
  *next* delivery of the same packet gets a real second chance instead of
  being dropped forever.

### 2. Reaper job

`cache/IdempotencyReaperJob.java` runs on a schedule
(`upi.mesh.idempotency-reaper-interval-ms`, default 30s) and releases any
claim that's been sitting in `PENDING` for longer than
`upi.mesh.idempotency-pending-timeout-seconds` (default 120s) — the
"nobody ever called markSettled/markFailed" case, i.e. the owning process
died mid-flight. Works against either store implementation via
`releaseExpiredPending(Duration)`.

### 3. Reconciliation job

`cache/IdempotencyReconciliationJob.java` runs on a schedule
(`upi.mesh.reconciliation-interval-ms`, default 60s) and cross-checks the
idempotency cache against the `transactions` table — the actual source of
truth — to catch drift between the two:
- A claim marked `SETTLED` in the cache with **no matching Transaction row**
  (should be structurally impossible given the current code, but worth
  alerting on if a future bug ever violates that invariant).
- A recent Transaction row with **no matching claim at all** in the cache
  (the fast dedup path has lost protection for that packetHash — the DB's
  unique index on `packetHash` is still there as a backstop, but a
  duplicate would now fall through to a DB constraint violation instead of
  being caught cheaply upstream).

It only logs/report drift — it deliberately never auto-"fixes" anything,
since silently mutating financial state based on a heuristic isn't
appropriate. See `GET /api/idempotency/status` for a live view.

### 4. Signed relay receipts + black-hole detection

Every mesh device now has its own Ed25519 keypair (`crypto/MeshNodeKeyRegistry.java`,
separate from the per-account signing keys in `DeviceKeyRegistry` — a relay
node is not necessarily a payer/payee). Every gossip hop in
`MeshSimulatorService.gossipOnce()` produces a `RelayReceipt`
(`mesh/RelayReceipt.java`): a record signed by the **sending** device
attesting "I forwarded packet P to this device at this time, with this many
hops left."

`mesh/RelayReceiptLedger.java` collects these and can detect a black hole:
a device that received a packet with hops left to give (so it was expected
to keep relaying) but never appears as the sender of any later receipt for
that packet. Bridge nodes (devices with internet) are excluded from the
check, since their expected next move is uploading to the backend, not
gossiping further.

```
GET /api/mesh/relay-receipts/{packetId}   -> raw signed receipts for one packet
GET /api/mesh/black-holes                 -> run the detection sweep
```

**Trust model caveat**: a receipt only proves the sender's side of a
handoff. A colluding pair of nodes could still forge a chain, or a
malicious node could sign a receipt for a handoff that never completed to
frame an innocent neighbor. A production system would pair this with a
receiver-side acknowledgement receipt too — this demo implements the
sender-side receipt because it's the minimum needed to demonstrate the
detection mechanism end-to-end.

### 5. Device reputation scoring

`mesh/DeviceReputationService.java` keeps a per-device trust score in
`[0.0, 1.0]`, starting at a neutral `1.0` for a device with no history.
Every successful forward (an outbound `RelayReceipt`) nudges it up; every
black-hole suspicion nudges it down, weighted more heavily than a
successful forward (a handful of confirmed drops should tank trust faster
than a long streak of good behavior can rebuild it).

```
GET /api/mesh/reputation   -> every known device's forward count, suspected-drop
                               count, and trust score, sorted worst-first
```

New/changed config (all in both `application.properties` and
`application-event-driven.properties`):
```
upi.mesh.idempotency-pending-timeout-seconds=120
upi.mesh.idempotency-reaper-interval-ms=30000
upi.mesh.reconciliation-interval-ms=60000
upi.mesh.reconciliation-lookback-seconds=3600
```

New tests: `ReliabilityAndMeshSecurityTest.java` covers the state machine
transitions, the reaper, signed-receipt verification (including a tampered
receipt), black-hole detection (flagged, not-flagged, bridge-node exemption,
final-hop exemption), and reputation scoring.
