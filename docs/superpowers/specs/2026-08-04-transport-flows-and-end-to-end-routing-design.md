# Design: Stable Transport Flows & End-to-End Message Routing (Clusters A + E)

**Date:** 2026-08-04
**Status:** Approved (design review)
**Applies to:** `android/` module (NEO-P2P Android app)

## Summary

Two related architecture problems in the Android app's data layer:

1. **Cluster A — transport flow identity bug.** `HybridP2PTransport` exposes
   `state` and `incomingMessages` as getters that return the *currently active*
   child transport's flows. When the transport flips between libp2p direct and
   the WebSocket relay, subscribers that collected at startup keep collecting an
   orphaned flow and silently stop receiving messages.

2. **Cluster E — wiring gaps.** Nothing connects incoming transport messages to
   Signal decrypt → chat persistence, or Nostr offers → Room, or escrow events →
   `EscrowService`. The individual components exist and work in isolation, but no
   orchestrator routes messages end-to-end, no real Signal session handshake runs,
   and outbound messages are dropped when the peer is offline.

This design fixes both: a stable merge layer for the hybrid transport, and a
`P2POrchestrator` that owns the shared scope and routes all application messages
over a single typed envelope protocol, with an offline queue and real Signal
pre-key session establishment.

## Context (current state)

- `HybridP2PTransport` implements `P2PTransport`; children `LibP2PManager` and
  `P2PTransportManager` each implement it too. `P2PTransport` exposes
  `StateFlow<TransportState>` and `SharedFlow<TransportMessage>`.
- `SignalProtocol` persists to Room/SQLCipher via the four `SqlCipher*Store`
  classes but its `ensurePreKeys()` only generates a signed pre-key, and
  `getPreKeyBundle()` returns a bundle with a zeroed one-time pre-key
  (`preKeyId=1`). No real session handshake exists on the wire.
- `NostrClient.offers` (`SharedFlow<JsonObject>`) receives kind-33333 trade
  offers but nothing consumes them into Room.
- `EscrowService` persists a state machine to Room but nothing routes incoming
  counterparty events to it.
- `P2PBackgroundService` starts `identityManager`, `p2pTransport`, and
  `nostrClient` directly with no orchestrator, and `EscrowService` /
  `SignalProtocol` create their own `CoroutineScope` instances.

## Approach

Three new layers, layered over the existing `P2PTransport` abstraction:

1. **Stable hybrid flows (Cluster A)** — `HybridP2PTransport` owns its own
   `StateFlow`/`SharedFlow` and merges the children into them.
2. **Typed envelope protocol** — a sealed `AppMessage` type and a codec mapping
   to/from `TransportMessage`.
3. **`P2POrchestrator`** — owns the shared scope, starts/stops all components,
   and routes inbound messages to feature routers; routers push outbound messages
   through an offline queue.

## Detailed Design

### 1. Cluster A — stable hybrid flows

Replace the delegated getters in `HybridP2PTransport` with owned flows.

```kotlin
private val _state = MutableStateFlow(P2PTransport.TransportState(transportType = "hybrid"))
private val _incomingMessages = MutableSharedFlow<P2PTransport.TransportMessage>(replay = 64)

override val state: StateFlow<P2PTransport.TransportState> = _state.asStateFlow()
override val incomingMessages: SharedFlow<P2PTransport.TransportMessage> = _incomingMessages.asSharedFlow()
```

- A `CoroutineScope(SupervisorJob() + Dispatchers.Default)` is created once in
  the `init`/constructor (owned by the `@Singleton`), not per `start()`.
- `start()` launches a merger coroutine that:
  - collects **both** children's `incomingMessages` and emits into
    `_incomingMessages`;
  - derives a composite `TransportState` and writes it to `_state`:
    `isRunning = libp2p.running || relay.running`,
    `transportType = "libp2p"` when libp2p active else `"ws-relay"`,
    `peerId` from whichever child is running, `connectedPeers` from the peer
    registry.
- The merger `Job` is stored in a `@Volatile` field and cancelled in `stop()`
  so the scope does not leak across restarts.
- `send` / `publish` / `subscribe` keep their existing dual-transport fan-out
  (unchanged behavior).
- `isActive()` is unchanged.

**Safety:** children still own their raw flows; `HybridP2PTransport` becomes a
pure merge point. Consumers subscribe once to stable flows and receive messages
from whichever transport delivers. `replay=64` avoids the subscribe-after-start
race.

### 2. Typed envelope protocol

New package `data/p2p/protocol`.

```kotlin
sealed interface AppMessage {
    val type: String
    data class PreKeyRequest(val to: String)
    data class PreKeyBundle(val to: String, val bundle: ByteArray)
    data class Chat(val to: String, val offerId: String, val ciphertext: ByteArray)
    data class Offer(val to: String, val offerJson: String)
    data class EscrowEvent(val to: String, val escrowId: String, val event: String, val payload: ByteArray)
}
```

`EnvelopeCodec` (pure Kotlin, unit-testable):
- `encode(msg: AppMessage): P2PTransport.TransportMessage`
- `decode(env: P2PTransport.TransportMessage): AppMessage?`
- Compact length-prefixed binary framing (type discriminator + field count +
  length-prefixed fields). No new serialization dependency.
- `decode` returns `null` on malformed input; never throws.

### 3. Signal session handshake (real pre-keys)

- `SignalProtocol.ensurePreKeys()` additionally generates a small one-time
  pre-key pool and persists it.
- `getPreKeyBundle()` returns a bundle backed by a real, consumed one-time
  pre-key instead of the current zeroed `preKeyId=1`.
- Handshake (driven by `P2POrchestrator`):
  1. A wants chat with B → `send(AppMessage.PreKeyRequest(to=B))`.
  2. B replies `send(AppMessage.PreKeyBundle(to=A, bundle))`.
  3. A → `signal.createSession(B, bundle)` → `send(AppMessage.Chat(...))`.
  4. B decrypts the `PreKeySignalMessage` on first message → session established
     both ways.

### 4. `P2POrchestrator` (`@Singleton`, `data/p2p/P2POrchestrator.kt`)

- Owns one shared `CoroutineScope(SupervisorJob() + Dispatchers.Default)` created
  in the constructor.
- `suspend fun start()` (idempotent):
  - `signal.initialize()` (guarded — non-fatal on failure)
  - `p2pTransport.start()`
  - `nostrClient.connect(identity.nostrPubkeyHex)`
  - `reputation.initialize()`
  - launch the inbound router
- `suspend fun stop()`: cancel scope, `p2pTransport.stop()`,
  `nostrClient.disconnect()`.
- Inbound: `p2pTransport.incomingMessages` → `EnvelopeCodec.decode` → route by
  `AppMessage.type` to the routers.
- Outbound helpers: `sendEncryptedChat`, `requestPreKeyBundle`, `sendOffer`,
  `sendEscrowEvent` — all via the offline queue.

### 5. Feature routers

New package `data/p2p/routing`.

- **`ChatRouter`** (`data/p2p/routing/ChatRouter.kt`):
  - `sendText(peerId, offerId, plaintext)`: `signal.encrypt` →
    `AppMessage.Chat` → offline queue.
  - `receiveChat(msg)`: `signal.handleIncomingMessage` → persist
    `ChatMessageEntity` via `chatMessageDao.insert()`.
  - On decrypt failure, auto-trigger pre-key handshake and queue message as
    pending.
- **`OfferRouter`** (`data/p2p/routing/OfferRouter.kt`):
  - Collector on `NostrClient.offers` parses kind-33333 JSON → `TradeOffer` →
    `offerDao.upsert`.
  - Outbound via `nostrClient.publishTradeOffer()` (existing) and
    `AppMessage.Offer` → queue.
- **`EscrowRouter`** (`data/p2p/routing/EscrowRouter.kt`):
  - Inbound `EscrowEvent` → `EscrowService` transition methods.
  - Outbound escrow transitions → `AppMessage.EscrowEvent` → queue to
    counterparty.

### 6. Offline queue

New package `data/p2p/queue` + Room persistence.

- `OfflineQueue.send(msg, toPeerId)` enqueues a `PendingMessageEntity`
  (`message_id`, `to_peer_id`, `type`, `payload`, `created_at`).
- `drainFor(peerId)`: when `PeerRegistry` reports the peer online (or after a
  successful `p2pTransport.send`), flush pending messages for that peer with
  capped retry/backoff.

### 7. Service wiring / lifecycle

- `AppModule`: bind `P2POrchestrator`, routers, `EnvelopeCodec`, `OfflineQueue`,
  and `PendingMessageDao`. Provide a shared
  `CoroutineScope(SupervisorJob() + Dispatchers.Default)` to `EscrowService` and
  `SignalProtocol` (removing their per-instance/per-call scopes).
- `P2PBackgroundService`: `onStartCommand` → `orchestrator.start()`;
  `onDestroy` → `orchestrator.stop()`.

### 8. Schema migration

- `AppDatabase` version 5 → 6.
- Add `Migration(5, 6)` creating `pending_messages`.
- Remove `fallbackToDestructiveMigration()` so existing data is preserved.

## Explicitly Out of Scope (tracked separately)

- WebRTC file transfer wiring (needs offer/negotiation handshake).
- Reputation gossip transport wiring (Cluster D).
- Identity storage / PeerID fixes (Cluster B).
- Escrow real PSBT signing (Cluster C).

## Verification

- Build: `./gradlew :app:assembleDebug` (from `android/`).
- Unit tests: `./gradlew :app:testDebugUnitTest`.
  - New: `EnvelopeCodecTest`, `OfflineQueueTest`, `ChatRouterTest`.
- Lint: `./gradlew :app:lintDebug` (lint-baseline.xml retained).
