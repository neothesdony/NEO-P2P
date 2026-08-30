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
  - `isDirect()` → true when a Link is established
- **Identity**: RNS 64-byte identity (X25519 priv + Ed25519 priv) derived deterministically from the existing BIP-39 mnemonic via SLIP-10 (already in `KeyDerivation.kt`), so peer IDs stay stable across the migration. The secp256k1 identity (Nostr/Bitcoin) is kept for escrow signing.
- **Infra**: one RNS transport node on the VPS (`enableTransport=true`, TCP server interface) + one LXMF propagation node (store-and-forward for offline peers) replaces strfry x3 + meta relay + libp2p relay + ws-relay + coturn. Phones connect as TCP clients.
- **Kind mapping** (Nostr → RNS/LXMF):
  - kind:33333 (offer) + kind:33336 (status) → announce appData on `neop2p/offers`
  - kind:33337 (escrow sync) → LXMF message with custom fields (FIELD_CUSTOM_TYPE/DATA)
  - kind:33386/33387/33388 (dispute/evidence/resolution) → LXMF messages with custom fields; `registerFailedDeliveryCallback` maps to PendingDisputeStore retry (replaces NIP-20 ack-gating — keep publish-then-commit semantics)

## Tech Stack

- rns-core + rns-interfaces (Kotlin 2.3.0, jvmTarget 21 — compatible with the upgraded toolchain, commit 91c6f91)
- lxmf-core (Kotlin 2.3.0, jvmTarget 21, pins rns-core v0.0.22 — also Kotlin 2.3.0, compatible)
- Kotlin 2.3.0, Hilt 2.58, Room 2.8.4, JDK 21 (already merged)
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

> Note: lxmf-core's build.gradle.kts pins rns-core via JitPack (`com.github.torlando-tech.reticulum-kt:rns-core:v0.0.22`). For mavenLocal publishing, either (a) publish rns-core v0.0.22 to mavenLocal first and let lxmf-core resolve it, or (b) patch lxmf-core's build to use the local SNAPSHOT. Decide by build result.

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
```

**Step 5: Verify**

Run: `cd android && JAVA_HOME=/home/thesdony/.sdkman/candidates/java/21.0.3-tem ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

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
- Test: `android/app/src/test/java/com/neop2p/data/p2p/RnsTransportTest.kt` (in-process Pipe interface, two Reticulum instances)

**Step 1: Write failing test**

Two in-process RNS instances over a `PipeInterface` (rns-interfaces has it, Python-parity): instance A sends an LXMF message to B's delivery destination, B receives. Assert `incomingMessages` emits with correct `fromPeerId`.

**Step 2: Implement**

- On start: create `LXMRouter(identity = rnsIdentity, storagePath = configDir)`, `registerDeliveryIdentity(identity, displayName)`, `registerDeliveryCallback { msg -> emit TransportMessage(...) }`, `router.start()`, `router.announce(dest)`.
- `send()`: build `LXMessage.create(destination = peerDest, source = myDest, content = data, title = type, desiredMethod = DIRECT)` → `router.handleOutbound(msg)`. LXMF handles path requests, link establishment, retries (5 attempts, 10s), and large-message Resource transfer automatically.
- Keep `SignalProtocol.encrypt()` before `send()` and `decrypt()` after receive (existing ChatRouter flow unchanged).

**Step 3: Verify**

- Unit: `./gradlew :app:testDebugUnitTest --tests "*RnsTransportTest*"` — PASS.
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

- Unit: attachment round-trip over Pipe interface (two in-process instances).
- Live: seller sends payment screenshot to buyer over LXMF; buyer sees it. WebRTC still active as fallback until Phase 4.

**Step 3: Commit**

```bash
git commit -m "feat(rns): file transfer over LXMF attachments (WebRTC fallback kept)"
```

---

## Phase 3 — Offers + Escrow + Arbitration over RNS

### Task 3.1: Offer feed via announces

**Objective:** kind:33333/33336 replaced by announce appData on `neop2p/offers`.

**Files:**
- Modify: `android/app/src/main/java/com/neop2p/data/p2p/RnsTransport.kt` (publish/subscribe)
- Modify: `android/app/src/main/java/com/neop2p/data/p2p/routing/OfferRouter.kt` (ingest from RNS announces)
- Modify: `android/app/src/main/java/com/neop2p/ui/screens/createoffer/CreateOfferScreen.kt` (publish via RNS)

**Step 1: Implement**

- `publish("offers", data)`: `offersDestination.setDefaultAppData(data)` + `announce()`.
- `subscribe("offers")`: register announce handler → parse appData → emit `TransportMessage(type="offer", ...)`.
- `OfferRouter`: add `ingestRnsOffer(json)` — same parsing/persistence as `ingestOfferEvent` (reuse the JSON schema; keep `matched_peer_id`/`payment_details` preservation rules).
- Dual-write: publish to both Nostr and RNS during transition (Nostr is the durable bus until Phase 4).

**Step 2: Verify**

- Unit: announce round-trip over Pipe (A announces offer, B's feed updates).
- Live: seller creates offer → buyer sees it in feed (from RNS announce). Kill strfry → feed still works via RNS.

**Step 3: Commit**

```bash
git commit -m "feat(rns): offer feed over announces (dual-write with Nostr)"
```

### Task 3.2: Escrow + arbitration signaling over LXMF

**Objective:** kind:33337/33386/33387/33388 replaced by LXMF messages with custom fields and delivery callbacks.

**Files:**
- Modify: `android/app/src/main/java/com/neop2p/data/p2p/routing/EscrowRouter.kt` (send/receive via LXMF)
- Modify: `android/app/src/main/java/com/neop2p/data/p2p/routing/OfferRouter.kt` (dispute kinds)
- Modify: `android/app/src/main/java/com/neop2p/data/escrow/EscrowService.kt` (publish path)
- Modify: `android/app/src/main/java/com/neop2p/data/local/PendingDisputeStore.kt` (retry semantics — `registerFailedDeliveryCallback` replaces NIP-20 acks)

**Step 1: Implement**

- Escrow status sync: `LXMessage.create(...)` with `fields[FIELD_CUSTOM_TYPE] = "escrow_status"`, `fields[FIELD_CUSTOM_DATA] = escrowJson`. `registerFailedDeliveryCallback` → PendingDisputeStore retry (same 60s sweep). Keep publish-then-commit (persist local state only after delivery callback, or after local commit + delivery — preserve the existing no-downgrade/forward-only router semantics).
- Dispute/evidence/resolution: same channel, `FIELD_CUSTOM_TYPE` = "dispute"/"evidence"/"resolution", `FIELD_IMAGE` for evidence screenshots. `registerFailedDeliveryCallback` retries.
- `EscrowRouter.applyRemoteStatus` unchanged — it consumes `TransportMessage` regardless of transport.

**Step 2: Verify**

- Unit: escrow status round-trip over Pipe; delivery failure → retry queued.
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
- Delete: `LibP2PManager.kt`, `P2PTransportManager.kt`, `NostrClient.kt`, `NostrEventSigner.kt`, `WebRTCManager.kt`, `WebRTCSignalCodec.kt`, `HybridP2PTransport.kt` (or reduce to a thin `RnsTransport`-only `P2PTransport` provider)
- Modify: `P2POrchestrator.kt`, `ChatRouter.kt`, `OfferRouter.kt`, `EscrowRouter.kt`, `AppModule.kt`, `NeoP2PConfig.kt` (remove relay URLs, TURN, libp2p config)
- Modify: `android/gradle/libs.versions.toml` (remove libp2p, ktor-websockets, stream-webrtc, protobuf exclusions)
- Modify: `android/app/src/main/AndroidManifest.xml` (remove unneeded permissions if any)

**Step 2: Verify**

- `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` — all green.
- Live: full flow test (offer → escrow → chat → receipt → release) with ONLY RNS. Kill VPS transport node → peers reconnect when it returns (RNS TCP reconnect).

**Step 3: Commit**

```bash
git commit -m "refactor(rns): remove libp2p/Nostr/WebRTC/ws-relay — RNS is the only transport"
```

### Task 4.2: Deploy RNS transport + LXMF propagation node on VPS

**Objective:** One RNS transport node + one LXMF propagation node replaces strfry x3 + meta + libp2p relay + ws-relay + coturn.

**Files:**
- Create: `infrastructure/rns-transport/Dockerfile` (JDK 21, rnsd-kt fat jar or a small Kotlin main)
- Create: `infrastructure/rns-transport/config.yml` (TCP server interface, `enableTransport=true`)
- Create: `infrastructure/lxmf-propagation/Dockerfile` (JDK 21, LXMF propagation node — LXMRouter with `autopeer=true` + propagation aspect)
- Modify: `infrastructure/docker-compose.yml` (replace relay services)
- Modify: `infrastructure/scripts/deploy.sh` (deploy RNS + LXMF nodes)

**Step 1: Build rnsd-kt**

```bash
cd ~/reticulum-kt
JAVA_HOME=/home/thesdony/.sdkman/candidates/java/21.0.3-tem ./gradlew :rns-cli:shadowJar
```

**Step 2: Dockerfile + compose**

- `rnsd-kt` with `--config /etc/reticulum`, TCP server on 0.0.0.0:42000 (or the port the app config points to).
- LXMF propagation node: small Kotlin main using `LXMRouter(identity, storagePath, autopeer=true)` + `router.start()` + `router.announce(dest)` — store-and-forward for offline peers (replaces the WS relay's offline queue).
- Replace strfry/libp2p/ws-relay/coturn services in compose (keep coturn only if TURN is still needed — RNS TCP client mode shouldn't need it).

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

- Transport: RNS (TCP/UDP/Auto interfaces) + LXMF messaging, client-only mode, transport + propagation nodes on VPS.
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

- **mavenLocal vs Forgejo maven**: mavenLocal is dev-only. Decide before CI matters (Task 1.1 note).
- **RNS announce size**: offer JSON in appData — RNS announces are small; large offers (payment details) should stay in link messages, not announces. Verify size limits in Phase 3.
- **LXMF delivery semantics**: `registerFailedDeliveryCallback` fires on delivery failure, not on peer persistence. Keep publish-then-commit + PendingDisputeStore retry (delivery-failure-triggered) — do NOT weaken the money-critical path.
- **SignalProtocol double-encryption**: keep app-level E2EE; RNS link adds transport-level encryption. Do not remove SignalProtocol (TOFU fingerprint UX depends on it).
- **Battery**: client-only mode + 60s job interval; verify against the existing foreground service (P2PBackgroundService).
- **rns-android module**: NOT needed — NEO-P2P has its own service. Only rns-core + rns-interfaces + lxmf-core.
- **LXMF propagation node**: needs a stable identity + storage on the VPS; verify autopeer behavior and message expiry (30 days) in Phase 4.
