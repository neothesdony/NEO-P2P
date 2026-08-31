# Reticulum Migration — Replace libp2p/Nostr/WebRTC transport with RNS + LXMF

Date: 2026-08-31
Branch: `reticulum-migration` (create from main)
Status: PLAN — awaiting user confirmation before code

## Goal

Replace NEO-P2P's transport stack (jvm-libp2p direct + Ktor WS relay + Nostr discovery + WebRTC file transfer + custom NIP-44-inspired E2EE) with the Reticulum Network Stack (rns-core/rns-interfaces from `~/reticulum-kt`) plus the LXMF messaging layer (lxmf-core from `~/LXMF-kt`). Escrow (bitcoinj 2-of-3), Room/SQLCipher v22, integer money math, UI, Hilt DI stay untouched. The app keeps its own E2EE envelope (SignalProtocol) on top of RNS/LXMF transport — RNS Link encryption is a second layer, not a replacement for app-level E2EE.

## Architecture

- **RNS replaces**: `LibP2PManager`, `P2PTransportManager` (WS relay), `NostrClient` (discovery + event bus), `WebRTCManager` (file transfer), and the custom chat E2EE transport path.
- **LXMF replaces**: the chat/escrow/arbitration message layer. `LXMRouter` handles delivery (OPPORTUNISTIC <319B, DIRECT over Link, PROPAGATED via propagation node for offline peers), retries (5 attempts, 10s), path requests, and large messages (>319B auto-Resource over Link — replaces WebRTC file transfer AND raw Resource handling). Structured fields (FIELD_FILE_ATTACHMENTS, FIELD_IMAGE, FIELD_THREAD, FIELD_REPLY_TO, FIELD_CUSTOM_TYPE/DATA/META) carry payment receipts, escrow status, dispute evidence as typed payloads.
- **New component**: `RnsTransport` — a `P2PTransport` implementation wrapping `Reticulum.start()` (client-only, `enableTransport=false` on phones) + `LXMRouter`. It maps:
  - `send(toPeerId, data, type)` → `LXMessage.create(dest, source, content, title, desiredMethod)` → `router.handleOutbound(msg)` (LXMF picks OPPORTUNISTIC/DIRECT/PROPAGATED)
  - `publish(topic, data)` → announce with `appData` (offer feed) — each peer announces a well-known destination `neop2p/offers` carrying its latest offer state
  - `subscribe(topic)` → announce handler registration
  - `dial(peerId, addrs)` → no-op (RNS handles paths itself)
  - `isDirect()` → `router.hasActiveLink(peerHashHex)` — a PUBLIC accessor added to the LXMF-kt fork (LXMRouter.kt:682, `directLinks.containsKey(...)`). `directLinks` itself is `private` (:121) and the only pre-existing accessor is `internal directLinkForTest` (:679) — not callable from the app. NOT RNS path state (a path exists ≠ link up)
- **Identity**: RNS 64-byte identity (X25519 priv + Ed25519 priv) derived deterministically from the existing BIP-39 mnemonic via SLIP-10 (already in `KeyDerivation.kt`), so peer IDs stay stable across the migration. The secp256k1 identity (Nostr/Bitcoin) is kept for escrow signing.
- **Infra**: one RNS transport node on the VPS (`enableTransport=true`, TCP server interface) + one LXMF propagation node (store-and-forward for offline peers) replaces strfry x3 + meta relay + libp2p relay + ws-relay + coturn. Phones connect as TCP clients.
- **Kind mapping** (Nostr → RNS/LXMF):
  - kind:33333 (offer) + kind:33336 (status) → announce appData on `neop2p/offers`
  - kind:33337 (escrow sync) → LXMF message with custom fields (FIELD_CUSTOM_TYPE/DATA)
  - kind:33386/33387/33388 (dispute/evidence/resolution) → LXMF messages with custom fields; `registerFailedDeliveryCallback` maps to PendingDisputeStore retry (replaces NIP-20 ack-gating — keep publish-then-commit semantics)

## Tech Stack

- rns-core + rns-interfaces (Kotlin 2.3.0, jvmTarget 21 — compatible with the upgraded toolchain, commit 91c6f91)
- lxmf-core (Kotlin 2.3.0, jvmTarget 21, pins rns-core v0.0.22 — also Kotlin 2.3.0, compatible)
- Kotlin 2.3.0, Hilt 2.60.1, Room 2.8.4, JDK 21 (already merged; toolchain upgraded 2026-08-31: Gradle 9.5.0 + AGP 9.3.0 built-in Kotlin + KSP 2.3.11)
- Bouncy Castle (already present), kotlinx-coroutines (already present)
- SLIP-10 derivation (already in `KeyDerivation.kt`)

---

## Phase 1 — Dependencies + Identity + Dual-Run

### Task 1.1: Publish rns-core/rns-interfaces/lxmf-core to a consumable repo

**Objective:** Make the RNS + LXMF modules available to the app build.

**Files:**
- Modify: `android/gradle/libs.versions.toml` (add rns-core, rns-interfaces, lxmf-core)
- Modify: `android/settings.gradle.kts` (add repository)
- Modify: `android/app/build.gradle.kts` (add dependencies)

**Step 1: Publish from ~/reticulum-kt and ~/LXMF-kt**

```bash
cd ~/reticulum-kt
JAVA_HOME=/home/thesdony/.sdkman/candidates/java/21.0.3-tem ./gradlew :rns-core:publishToMavenLocal :rns-interfaces:publishToMavenLocal -x test
cd ~/LXMF-kt
JAVA_HOME=/home/thesdony/.sdkman/candidates/java/21.0.3-tem ./gradlew :lxmf-core:publishToMavenLocal -x test
```

Expected: `~/.m2/repository/network/reticulum/rns-core/0.1.0-SNAPSHOT/` and `~/.m2/repository/network/reticulum/lxmf-core/0.1.0-SNAPSHOT/` exist.

> **JitPack pin — MUST fix before publishing (review point 4):** lxmf-core's build.gradle.kts:23 declares `api("com.github.torlando-tech.reticulum-kt:rns-core:v0.0.22")` — `api` scope leaks transitively, and JitPack being a separate repo means Gradle can silently resolve rns-core from JitPack instead of mavenLocal. Patch the fork FIRST so both repos publish the SAME version:
>
> ```bash
> cd ~/LXMF-kt
> # change line 23 to depend on the mavenLocal-published version:
> #   api("network.reticulum:rns-core:0.1.0-SNAPSHOT")
> # (and the testImplementation rns-interfaces pin on line ~26 the same way)
> ```
>
> **hasActiveLink accessor — also required (review point 7):** `LXMRouter.directLinks` is `private` (LXMRouter.kt:121) and the only pre-existing accessor is `internal directLinkForTest` (:679) — NOT callable from the app. Task 2.1's `isDirect()` needs a public accessor. Add to the same fork patch (LXMRouter.kt, after `directLinkForTest`):
>
> ```kotlin
> /** Whether an active DIRECT delivery link exists for the given destination hash. */
> fun hasActiveLink(destHashHex: String): Boolean = directLinks.containsKey(destHashHex)
> ```
>
> Then publish rns-core/rns-interfaces to mavenLocal, THEN lxmf-core. Verify with `./gradlew :lxmf-core:dependencies --configuration runtimeClasspath` — no `com.github.torlando-tech` entries. No JitPack in the app classpath at all.

**Step 2: Add mavenLocal() to NEO-P2P settings**

`android/settings.gradle.kts` — add `mavenLocal()` to `pluginManagement.repositories` and `dependencyResolutionManagement.repositories`.

**Step 3: Add catalog entries**

```toml
rns-core = { module = "network.reticulum:rns-core", version = "0.1.0-SNAPSHOT" }
rns-interfaces = { module = "network.reticulum:rns-interfaces", version = "0.1.0-SNAPSHOT" }
lxmf-core = { module = "network.reticulum:lxmf-core", version = "0.1.0-SNAPSHOT" }
```

**Step 4: Add deps to app**

`android/app/build.gradle.kts` dependencies block:
```kotlin
implementation(libs.rns.core)
implementation(libs.rns.interfaces)
implementation(libs.lxmf.core)
// SLF4J binding — rns-core + lxmf-core use kotlin-logging-jvm (SLF4J);
// without a binding SLF4J silently NOPs and the transport layer logs NOTHING (review point 6)
implementation("org.slf4j:slf4j-android:2.0.9")
```

**Step 5: Verify**

Run: `cd android && JAVA_HOME=/home/thesdony/.sdkman/candidates/java/21.0.3-tem ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. Also run `./gradlew :app:dependencies --configuration debugRuntimeClasspath | grep -i "torlando\|jitpack"` — expect NO output (no JitPack artifacts on the classpath).

**Step 6: Commit**

```bash
git add android/gradle/libs.versions.toml android/settings.gradle.kts android/app/build.gradle.kts
git commit -m "feat(rns): add rns-core + rns-interfaces + lxmf-core dependencies"
```

> Note: mavenLocal is machine-specific. For CI/other machines, later publish to Forgejo's maven registry (or vendor source). Dev-first: mavenLocal is fine.

### Task 1.2: RNS identity derivation from mnemonic

**Objective:** Derive the 64-byte RNS identity (X25519 + Ed25519) from the BIP-39 mnemonic so peer IDs survive the migration.

**Files:**
- Modify: `android/app/src/main/java/com/neop2p/data/p2p/KeyDerivation.kt` (add `rnsIdentity()`)
- Test: `android/app/src/test/java/com/neop2p/data/p2p/KeyDerivationTest.kt`

**Step 1: Write failing test**

```kotlin
@Test
fun rnsIdentity_isDeterministic_and64Bytes() {
    val mnemonic = "abandon abandon ... about" // test mnemonic
    val a = KeyDerivation.rnsIdentity(mnemonic)
    val b = KeyDerivation.rnsIdentity(mnemonic)
    assertTrue(a.contentEquals(b))
    assertEquals(64, a.size)
}
```

**Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "*KeyDerivationTest*"`
Expected: FAIL — `rnsIdentity` not defined.

**Step 3: Implement**

In `KeyDerivation.kt`, add a SLIP-10 curve25519 derivation (already exists for ed25519/curve25519) at a fixed path (e.g. `m/44'/999'/0'/0/1`), returning `x25519Priv(32) + ed25519Priv(32)`:

```kotlin
fun rnsIdentity(mnemonic: String): ByteArray {
    val seed = mnemonicToSeed(mnemonic) // existing
    val x25519 = deriveSlip10Curve25519(seed, "m/44'/999'/0'/0/1")
    val ed25519 = deriveSlip10Ed25519(seed, "m/44'/999'/0'/0/2")
    return x25519 + ed25519
}
```

**Step 4: Run to verify pass**

Expected: PASS.

**Step 5: Commit**

```bash
git commit -m "feat(rns): derive deterministic RNS identity from mnemonic (SLIP-10)"
```

### Task 1.3: RnsTransport skeleton (start/stop only, dual-run)

**Objective:** Start RNS alongside the existing stack without changing behavior.

**Files:**
- Create: `android/app/src/main/java/com/neop2p/data/p2p/RnsTransport.kt`
- Modify: `android/app/src/main/java/com/neop2p/di/AppModule.kt` (provide it)
- Modify: `android/app/src/main/java/com/neop2p/data/p2p/P2POrchestrator.kt` (start it)

**Step 1: Implement RnsTransport**

```kotlin
class RnsTransport @Inject constructor(
    private val identityManager: IdentityManager
) : P2PTransport {
    private var rns: Reticulum? = null
    override val state = MutableStateFlow(TransportState())
    override val incomingMessages = MutableSharedFlow<TransportMessage>()

    override suspend fun start(): Result<Unit> = runCatching {
        val identity = Identity.fromPrivateKey(KeyDerivation.rnsIdentity(identityManager.mnemonic()))
        rns = Reticulum.start(
            configDir = context.filesDir.resolve("reticulum").absolutePath,
            enableTransport = false,
            transportIdentity = identity
        )
        // register peer destination: appName="neop2p", aspect="peer"
        state.value = TransportState(isRunning = true, transportType = "rns")
    }

    override suspend fun stop(): Result<Unit> = runCatching {
        Reticulum.stop()
        state.value = TransportState()
    }

    override suspend fun send(toPeerId: String, data: ByteArray, type: String): Result<Unit> =
        Result.failure(NotImplementedError("Phase 2"))

    override suspend fun publish(topic: String, data: ByteArray): Result<Unit> =
        Result.failure(NotImplementedError("Phase 3"))

    override suspend fun subscribe(topic: String): Result<Unit> = Result.success(Unit)
    override fun isDirect(): Boolean = false
}
```

**Step 2: Wire into DI + orchestrator**

- `AppModule.kt`: provide `RnsTransport` (or bind via `@Binds` to `P2PTransport` — decide: keep `HybridP2PTransport` as the active transport until Phase 2; provide RnsTransport as a separate singleton).
- `P2POrchestrator.start()`: call `rnsTransport.start()` alongside `p2pTransport.start()` — log success/failure, never fail the orchestrator if RNS fails (dual-run).

**Step 3: Verify**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass.

**Step 4: Live smoke test (per flow-test convention)**

- Install on OnePlus + emulator. Launch. Check logcat: `RnsTransport: started` on both.
- User drives UI; agent monitors. No behavior change expected (Nostr/libp2p still active).

**Step 5: Commit**

```bash
git commit -m "feat(rns): RnsTransport skeleton — dual-run start/stop alongside existing stack"
```

---

## Phase 2 — Chat over LXMF

### Task 2.1: LXMRouter integration + send/receive

**Objective:** Chat messages travel over LXMF (OPPORTUNISTIC/DIRECT/PROPAGATED); keep the SignalProtocol E2EE envelope (double encryption: app-level + RNS link).

**Files:**
- Modify: `android/app/src/main/java/com/neop2p/data/p2p/RnsTransport.kt`
- Test: `android/app/src/test/java/com/neop2p/data/p2p/RnsTransportTest.kt` (single-instance local-delivery test; two-process integration test for peer-to-peer)

**Step 1: Write failing test**

> **Singleton constraint (review point 3):** `Reticulum.start()` is a JVM-wide singleton (`AtomicBoolean` at Reticulum.kt:188, `compareAndSet` at :271) — TWO instances in ONE JVM is impossible. `shareInstance`/`connectToSharedInstance` (Reticulum.kt:34-41) are for OTHER PROCESSES over TCP loopback, not in-process. So the original "two in-process instances over PipeInterface" test CANNOT work. Two options:
>
> - **Unit (in-JVM):** single instance, LXMF delivery to a LOCAL registered delivery identity (LXMRouter has `locallyDeliveredTransientIds`, LXMRouter.kt:216-217 — local delivery works). Exercises router wiring (register → send → callback) without peer-to-peer.
> - **Integration (two processes):** JVM A + JVM B, each a real Reticulum instance, connected over a TCP interface (or PipeInterface via a shared socket). Verifies actual peer-to-peer delivery. Run as a separate Gradle test task or a script — NOT in the unit test JVM.

Unit test (in-JVM, local delivery):
```kotlin
@Test
fun lxmf_localDelivery_emitsIncomingMessage() {
    val router = LXMRouter(identity = testIdentity, storagePath = tmpDir)
    router.registerDeliveryIdentity(testIdentity, "alice")
    val received = mutableListOf<LXMessage>()
    router.registerDeliveryCallback { received.add(it) }
    router.start()
    val msg = LXMessage.create(
        destination = testIdentity.destination, // local
        source = testIdentity.destination,
        content = "hello".encodeToByteArray(),
        title = "chat",
        desiredMethod = DeliveryMethod.DIRECT
    )
    router.handleOutbound(msg)
    // poll until received.isNotEmpty() (async delivery)
    assertTrue(received.any { it.content.decodeToString() == "hello" })
}
```

**Step 2: Implement**

- On start: create `LXMRouter(identity = rnsIdentity, storagePath = configDir)`, `registerDeliveryIdentity(identity, displayName)`, `registerDeliveryCallback { msg -> emit TransportMessage(...) }`, `router.start()`, `router.announce(dest)`.
- `send()`: build `LXMessage.create(destination = peerDest, source = myDest, content = data, title = type, desiredMethod = DIRECT)` → `router.handleOutbound(msg)`. LXMF handles path requests, link establishment, retries (5 attempts, 10s), and large-message Resource transfer automatically.
- `isDirect()`: `router.hasActiveLink(peerHashHex)` — public accessor added to the LXMF-kt fork (see Task 1.1 fork-patch block) — an established LXMF link, NOT RNS path state (review point 1).
- Keep `SignalProtocol.encrypt()` before `send()` and `decrypt()` after receive (existing ChatRouter flow unchanged).

**Step 3: Verify**

- Unit: `./gradlew :app:testDebugUnitTest --tests "*RnsTransportTest*"` — PASS (local-delivery test).
- Integration: two-process test (JVM A ↔ JVM B over TCP) — PASS.
- Live: OnePlus + emulator, same LAN. Chat send shows LXMF delivery in logcat (`LXMF message delivered`), ConnectionQualityChip shows DIRECT (map `isDirect()` → true when link up). Kill WS relay container → chat still works.

**Step 4: Commit**

```bash
git commit -m "feat(rns): chat over LXMF with delivery callbacks"
```

### Task 2.2: File transfer via LXMF attachments (replaces WebRTC)

**Objective:** Payment-proof images transfer over LXMF FIELD_FILE_ATTACHMENTS / FIELD_IMAGE instead of WebRTC DataChannel.

**Files:**
- Modify: `android/app/src/main/java/com/neop2p/data/p2p/RnsTransport.kt` (add attachment field to LXMessage)
- Modify: `android/app/src/main/java/com/neop2p/data/p2p/routing/ChatRouter.kt` (route `sendFile` to LXMF when active)
- Modify: `android/app/src/main/java/com/neop2p/ui/screens/chat/ChatScreen.kt` (receivedFiles flow from LXMF)

**Step 1: Implement**

- `sendFile(peerId, fileName, data)`: `LXMessage.create(...)` with `fields[FIELD_FILE_ATTACHMENTS] = listOf(fileName to data)` (or FIELD_IMAGE for screenshots). LXMF auto-Resources messages >319B (chunked + BZ2 + retransmission).
- Receive: `registerDeliveryCallback` → parse attachment field → emit `TransportMessage(type="file", ...)` → ChatScreen's `receivedFiles` flow.

**Step 2: Verify**

- Unit: attachment round-trip via local delivery (single instance, same pattern as Task 2.1); two-process integration for peer-to-peer.
- Live: seller sends payment screenshot to buyer over LXMF; buyer sees it. WebRTC still active as fallback until Phase 4.

**Step 3: Commit**

```bash
git commit -m "feat(rns): file transfer over LXMF attachments (WebRTC fallback kept)"
```

---

## Phase 3 — Offers + Escrow + Arbitration over RNS

### Task 3.1: Offer feed via announces

**Objective:** kind:33333/33336 replaced by announce appData on `neop2p/offers`.

> **Offer-feed gap — DECISION (review point 2):** RNS announces are ephemeral (QUEUED_ANNOUNCE_LIFE = 24h, ANNOUNCE_CAP rate limit) and the propagation node does NOT replay announces (it's LXMF message store-and-forward only). A late-joining buyer misses offers published before it joined. LXMF has NO broadcast primitive (verified: no broadcast API in LXMRouter/LXMessage), so "LXMF broadcast per offer" is not available without building it. **DECISION: accept the limitation (option b).** Offers are time-sensitive (24h TTL) and the app's offer lifecycle is match-driven, not feed-driven — a buyer who misses an offer can ask the seller to re-announce. If this proves wrong in live testing, fall back to option (a): an "offers query" destination — new buyer sends a query LXMF message to `neop2p/offers-query`, online peers respond with their current offers (request/response fits LXMF naturally). Note: RNS has a persistent announce cache (Transport.kt:651 `announceStore`, file-based fallback) — peers online when an offer was announced retain it; only true late-joiners miss it.

> **Announce size — DECISION (2026-08-31, implemented):** RNS announce appData is capped at ~300 bytes (MTU 500 − HEADER_MIN 19 − announce overhead 180 with ratchet: 64 pubkey + 10 name hash + 10 random hash + 32 ratchet + 64 sig). A full offer JSON (with multiaddrs, nickname, TTL) exceeds this. **Implemented: compact digest announce + on-demand full offer over LXMF.** The `neop2p/offers` announce carries `RnsOfferDigest` (v1: id, creator, type, fiat, sats, price, methods, nickname, expiry — ~200 bytes). A peer that sees a digest it doesn't have sends `offer_request` over LXMF (DIRECT); the creator replies with the full offer JSON (`offer`), which `OfferRouter.ingestRnsOffer` runs through the same persistence pipeline as Nostr offers. The digest's announcing identity is cross-checked against the peer's `lxmf.delivery` announce (same RNS identity ⇒ same peerId) so a spoofed digest cannot claim a peerId it does not own.

**Files:**
- Modify: `android/app/src/main/java/com/neop2p/data/p2p/RnsSession.kt` (offers destination + announce handler + offer_request/offer LXMF)
- Modify: `android/app/src/main/java/com/neop2p/data/p2p/RnsTransport.kt` (delegates)
- Create: `android/app/src/main/java/com/neop2p/data/p2p/RnsOfferDigest.kt` (compact digest)
- Modify: `android/app/src/main/java/com/neop2p/data/p2p/routing/OfferRouter.kt` (ingestRnsOffer + applyOfferStatus extraction)
- Modify: `android/app/src/main/java/com/neop2p/ui/screens/createoffer/CreateOfferScreen.kt` (digest announce on create/edit)
- Modify: `android/app/src/main/java/com/neop2p/ui/screens/offerdetail/OfferDetailScreen.kt` (offer_status LXMF to matched peer)

**Step 1: Implement** (done 2026-08-31)

- `publishOffer(digestJson)`: announce the `neop2p/offers` destination with the digest as appData.
- `subscribe("offers")`: announce handler → identity cross-check → emit `OfferAnnounce` → orchestrator requests the full offer over LXMF.
- `OfferRouter.ingestRnsOffer(json)`: same parsing/persistence as `ingestOfferEvent` (reuse the JSON schema; keep `matched_peer_id`/`payment_details` preservation rules).
- `OfferRouter.applyOfferStatus(...)`: extracted from the kind:33336 collector so the LXMF `offer_status` path shares the no-downgrade/lost-claim/multiaddr rules.
- Dual-write: publish to both Nostr and RNS during transition (Nostr is the durable bus until Phase 4).

**Step 2: Verify** (done 2026-08-31)

- Unit: `RnsOfferDigestTest` (4 tests: budget ≤301B, round-trip, malformed, no-expiry) + `RnsSessionTest` additions (offer announce mapping, unknown-identity drop, publishOffer, 7 signaling sends).
- Two-process integration: child now also receives an `offer_status` over the real link (`SIGNAL` line) — passes.
- Live: seller creates offer → buyer sees it in feed (from RNS announce). Kill strfry → feed still works via RNS. Verify late-join behavior: buyer joins AFTER offer published → offer missing (accepted limitation) OR query-destination fallback works.

**Step 3: Commit**

```bash
git commit -m "feat(rns): offer feed over announces (dual-write with Nostr)"
```

### Task 3.2: Escrow + arbitration signaling over LXMF

**Objective:** kind:33337/33386/33387/33388 replaced by LXMF messages with custom fields and delivery callbacks.

> **Delivery model — DECISION (2026-08-31, implemented):** LXMF DIRECT delivery (link-based, forward secrecy) with the app's existing offline-queue + resume-heal semantics. `registerFailedDeliveryCallback` fires when LXMF gives up (5 attempts × 10s); the app's 60s sweep + `getEscrow` resume-heal re-publish the same way they heal Nostr publishes. The Nostr relay remains the durable bus until Phase 4 — every signaling send is dual-write (Nostr + LXMF to the counterparty), so a relay outage no longer stalls escrow sync.

**Files:**
- Modify: `android/app/src/main/java/com/neop2p/data/p2p/RnsSession.kt` (sendEscrowStatus/sendDispute/sendEvidence/sendResolution)
- Modify: `android/app/src/main/java/com/neop2p/data/p2p/P2POrchestrator.kt` (LXMF signaling inbound routing; dispute/evidence/resolution handlers extracted for reuse)
- Modify: `android/app/src/main/java/com/neop2p/data/escrow/EscrowService.kt` (dual-path publishEscrowSync + publishOfferStatusDual)
- Modify: `android/app/src/main/java/com/neop2p/ui/screens/escrow/EscrowScreen.kt` (dispute → counterparty + arbitrator over LXMF)
- Modify: `android/app/src/main/java/com/neop2p/ui/screens/escrow/DisputeEvidenceScreen.kt` (evidence → counterparty + arbitrator over LXMF)
- Modify: `android/app/src/main/java/com/neop2p/ui/screens/escrow/DisputeFeedScreen.kt` (resolution → both parties over LXMF)
- Modify: `android/app/src/main/java/com/neop2p/NeoP2PConfig.kt` (`ARBITRATOR_PEER_ID` — the arbitrator's LXMF delivery destination; blank = RNS arbitration disabled)

**Step 1: Implement** (done 2026-08-31)

- Escrow status sync: `sendEscrowStatus(toPeerId, escrowId, status, fields)` — same mutable-field map as kind:33337, delivered DIRECT to the counterparty. `EscrowRouter.ingestEscrowStatus` consumes it unchanged (transport-agnostic).
- Dispute/evidence/resolution: `sendDispute`/`sendEvidence`/`sendResolution` — same payloads as kind:33386/33387/33388; evidence images ride as LXMF file attachments (auto-Resource). The orchestrator routes inbound LXMF signaling to the same handlers as the Nostr collectors (`applyDisputeEvent`/`applyEvidenceEvent`/`applyResolutionEvent` were extracted for reuse).
- `ARBITRATOR_PEER_ID` in `NeoP2PConfig` lets parties deliver disputes/evidence to the arbitrator over LXMF without the relay.

**Step 2: Verify** (done 2026-08-31)

- Unit: `RnsSessionTest` — 7 signaling sends (offer_status/escrow_status/dispute/evidence/resolution/offer_request/offer) queue DIRECT LXMF messages to a known peer.
- Two-process integration: `offer_status` delivered over a real link (child prints `SIGNAL offer_status`).
- Live: full flow test — seller funds escrow, buyer marks paid, seller confirms, release. Kill strfry mid-flow → escrow sync survives via LXMF.

**Step 3: Commit**

```bash
git commit -m "feat(rns): escrow + arbitration signaling over LXMF with delivery callbacks"
```

---

## Phase 4 — Teardown

### Task 4.1: Remove legacy transports

**Objective:** Delete libp2p, WS relay, Nostr, WebRTC; RNS is the only transport.

**Files:**
- Delete: `LibP2PManager.kt`, `P2PTransportManager.kt`, `NostrClient.kt`, `NostrEventSigner.kt`, `WebRTCManager.kt`, `WebRTCSignalCodec.kt`, `HybridP2PTransport.kt`
- Modify: `P2POrchestrator.kt`, `ChatRouter.kt`, `OfferRouter.kt`, `EscrowRouter.kt`, `AppModule.kt`, `NeoP2PConfig.kt` (remove relay URLs, TURN, libp2p config)
- Modify: `android/gradle/libs.versions.toml` (remove libp2p, ktor-websockets, stream-webrtc, protobuf exclusions)
- Modify: `android/app/src/main/AndroidManifest.xml` (remove unneeded permissions if any)

**Step 1: Implement** (done 2026-08-31)

- Deleted all 7 legacy transport files. `P2PTransport` interface kept (RnsTransport implements it); `PeerRegistry` kept (RNS announce population).
- `P2POrchestrator` is RNS-only: Nostr collectors (offers/statuses/deletions/attestations/disputes/evidence/resolutions) removed — LXMF signaling routes to the same handlers; `retryPendingDisputes`/`healDisputePsbt` now deliver over LXMF (`publishDisputeRns`).
- `ChatRouter`/`OfferRouter`/`EscrowRouter`/`EscrowService` drop NostrClient + hybrid transport; all publishes are LXMF DIRECT.
- UI: Home (relay banner → RNS), Settings (relay/TURN sections → RNS transport card), Chat (WebRTC removed), CreateOffer/OfferDetail (Nostr publish → digest announce + LXMF status), Escrow/DisputeEvidence/DisputeFeed (Nostr publish → LXMF; attestation publish removed — local-only).
- `KeyDerivation.deriveLibp2pPeerIdFromKey` reimplemented locally (base58btc of identity multihash of protobuf Ed25519 pubkey) — jvm-libp2p removed; peerIds stay stable.
- `RnsSession`/`RnsTransport` gain a TCP client interface to the VPS transport node (`NeoP2PConfig.RNS_TRANSPORT_NODE_HOST/PORT`).
- Build: libp2p, stream-webrtc, ktor-websockets, protobuf-java removed; ktor-client-core/okhttp kept (ChainMonitor Mempool API + market price). TURN BuildConfig fields removed.

**Step 2: Verify** (done 2026-08-31)

- `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` — all green (232 tests).
- Live: full flow test (offer → escrow → chat → receipt → release) with ONLY RNS. Kill VPS transport node → peers reconnect when it returns (RNS TCP reconnect).

**Step 3: Commit**

```bash
git commit -m "refactor(rns): remove libp2p/Nostr/WebRTC/ws-relay — RNS is the only transport"
```

### Task 4.2: Deploy RNS transport + LXMF propagation node on VPS

**Objective:** One RNS transport node + one LXMF propagation node replaces strfry x3 + meta + libp2p relay + ws-relay + coturn.

**Files:**
- Create: `infrastructure/rns-transport/Dockerfile` (JDK 21, rnsd-kt fat jar) + `config.yml` (TCP server, `enableTransport=true`)
- Create: `infrastructure/lxmf-propagation/Dockerfile` + `lxmd.sh` (Python lxmd — the Kotlin lxmf-core fork is client-only for propagation; the Python node is the reference the Kotlin client interops with)
- Modify: `infrastructure/docker-compose.yml` + `.amd64.yml` (replace relay services), `scripts/deploy.sh`/`status.sh`/`backup.sh`/`healthcheck.sh`
- Delete: `strfry/`, `libp2p-relay/`, `ws-relay/`, `coturn/`

**Step 1: Build rnsd-kt** (done 2026-08-31 — jar copied to `rns-transport/rnsd-kt.jar`, not committed)

```bash
cd ~/reticulum-kt
JAVA_HOME=/home/thesdony/.sdkman/candidates/java/21.0.3-tem ./gradlew :rns-cli:shadowJar
```

**Step 2: Dockerfile + compose** (done 2026-08-31)

- `rnsd-kt` with `--config /etc/reticulum`, TCP server on 0.0.0.0:42000 (the port the app config points to).
- LXMF propagation node: Python `lxmd` with a **stable identity** (persisted in the volume — peers cache the node's destination hash) + daily prune (LXMF caps: PROPAGATION_LIMIT=256, DELIVERY_LIMIT=1000, MESSAGE_EXPIRY=30 days).
- strfry/libp2p/ws-relay/coturn services removed from compose (RNS TCP client mode needs no TURN).

**Step 3: Verify**

- Deploy to VPS (user drives SFTP per convention; Forgejo push = backup).
- Live: OnePlus + emulator connect to the RNS node; full flow test. Kill a phone mid-flow → messages queue on the propagation node → delivered on reconnect.

**Step 4: Commit**

```bash
git commit -m "feat(infra): RNS transport + LXMF propagation node replaces strfry/libp2p/ws-relay/coturn"
```

### Task 4.3: Docs + cleanup

**Objective:** Update docs to match the new stack.

**Files:**
- Modify: `README.md` (transport table, fee section already stale — fix 0.3%→0.5% while here), `AGENTS.md`, `android/AGENTS.md`, `docs/SECURITY_POSTURE.md`, `CHANGELOG.md` (new entry)

**Step 1: Update docs**

- Transport: RNS (TCP client to the VPS transport node) + LXMF messaging, client-only mode, transport + propagation nodes on VPS.
- E2EE: app-level SignalProtocol envelope + RNS link encryption (two layers).
- Identity: BIP-39 → SLIP-10 → RNS 64B identity; secp256k1 kept for escrow.
- Remove all libp2p/Nostr/WebRTC references.

**Step 2: Commit**

```bash
git commit -m "docs: Reticulum migration — transport, identity, E2EE, infra"
```

---

## Verification (per phase, per flow-test convention)

1. Build: `cd android && JAVA_HOME=/home/thesdony/.sdkman/candidates/java/21.0.3-tem ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`
2. Live: OnePlus (seller) + emulator (buyer). User drives UI; agent monitors logcat + fixes code. Debug pkg `com.neop2p.app.debug`; `logcat -c` after launch.
3. Kill tests per phase: WS relay (Phase 2), strfry (Phase 3), VPS node (Phase 4) — verify graceful degradation/reconnect.

## Risks / Open Questions

- **mavenLocal vs Forgejo maven**: mavenLocal is dev-only. JitPack pin resolved in Task 1.1 (patch lxmf-core fork to mavenLocal version, verify no `com.github.torlando-tech` on the classpath). Forgejo maven registry (or vendored source) still needed for CI/other machines.
- **RNS announce size**: offer JSON in appData — RNS announces are small; large offers (payment details) should stay in link messages, not announces. Verify size limits in Phase 3.
- **Offer-feed late-join gap**: DECIDED — accept the limitation (offers are 24h-TTL, match-driven lifecycle); fallback = "offers query" destination (Task 3.1).
- **LXMF delivery semantics**: `registerFailedDeliveryCallback` fires on delivery failure, not on peer persistence. Keep publish-then-commit + PendingDisputeStore retry (delivery-failure-triggered) — do NOT weaken the money-critical path.
- **SignalProtocol double-encryption**: keep app-level E2EE; RNS link adds transport-level encryption. Do not remove SignalProtocol (TOFU fingerprint UX depends on it).
- **Battery**: client-only mode + 60s job interval; verify against the existing foreground service (P2PBackgroundService).
- **rns-android module**: NOT needed — NEO-P2P has its own service. Only rns-core + rns-interfaces + lxmf-core.
- **LXMF propagation node**: needs a stable identity + storage on the VPS; pruning policy (256-message cap, 30-day expiry) in Task 4.2; verify autopeer behavior in Phase 4.
- **Reticulum singleton**: one instance per JVM — unit tests use local delivery, peer-to-peer tests are two-process (Task 2.1).
- **SLF4J binding**: rns-core + lxmf-core log via kotlin-logging-jvm; slf4j-android added in Task 1.1 so transport logs are visible during migration.
- **hasActiveLink accessor**: `LXMRouter.directLinks` is private — the fork adds a public `hasActiveLink(destHashHex)` (Task 1.1 fork-patch block); without it Task 2.1's `isDirect()` won't compile.
