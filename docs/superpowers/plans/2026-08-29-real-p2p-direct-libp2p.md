# P2P Upgrade — Direct libp2p Connections (Phases 1 + 2)

Date: 2026-08-29
Branch: `p2p-upgrade`
Status: PLAN — awaiting user confirmation before code (per android-crypto-p2p skill)

## Goal

Make NEO-P2P actually peer-to-peer: peers learn each other's libp2p multiaddrs from the offer feed, dial each other directly (TCP/WS), and fall back to the circuit relay (go-libp2p, already deployed on port 4001) then the WS relay. Chat + payment details + receipts travel over direct streams when reachable; Nostr stays the durable bus for offers/escrow/arbitration.

## Current state (verified)

- `LibP2PManager` starts a host (TCP + WS listen, NoiseXX, Mplex, Identify) but **nothing ever dials** — `connectedPeerIds()` is always empty, so `HybridP2PTransport.send()` always falls through to the WS relay.
- `PeerEntity` already has a `multiaddrs` column (JSON array) and `OfferRouter.ingestOfferEvent` already upserts a creator Peer row — but the offer JSON never carries multiaddrs, so the column is always `[]`.
- `NeoP2PConfig.DEFAULT_LIBP2P_RELAYS` already lists `/dns/relay1.custom-minipc.com/tcp/4001/p2p-circuit` — but `RelayTransport` is not wired into the host.
- App pins `io.libp2p:jvm-libp2p:1.3.6-RELEASE` (libs.versions.toml:59). The clone at `~/jvm-libp2p` (develop, commit d0a6cd8d 2026-08-28) has the full circuit relay v2 (`RelayTransport`, `CircuitHopProtocol`, `CircuitStopProtocol`, `AutonatProtocol`) plus the gossipsub hardening.
- Infra relay: go-libp2p circuit relay v2, 512 reservations / 256 circuits, health at :4002, persistent key at `/data/relay.key` (peerId stable across restarts).

## Phase 1 — Multiaddrs in the offer event

### 1.1 Collect listen addresses
`LibP2PManager` already exposes `listenAddresses(): List<String>`. Add a method `currentMultiaddrs(): List<String>` that returns the host's listen addresses with the `/p2p/<peerId>` suffix appended (the form a dialer needs), e.g. `/ip4/10.0.0.5/tcp/41234/p2p/12D3KooW...`. Note: on Android the listen address is the LAN IP — good for same-network peers (the flow-test setup: OnePlus + emulator on the same LAN), useless for internet peers; the circuit relay covers those.

### 1.2 Publish in offer JSON
- `CreateOfferScreen.kt:752` create builder: add `putJsonArray("multiaddrs") { libp2pManager.currentMultiaddrs().forEach { add(it) } }`.
- `CreateOfferScreen.kt:940` edit builder: same.
- Keep it best-effort: if libp2p isn't running, publish an empty array (or omit the field).

### 1.3 Ingest + persist
- `OfferRouter.ingestOfferEvent`: parse `multiaddrs` from the offer JSON and store into the creator Peer row upsert (OfferRouter.kt:295-312) — `peerDao.upsert(peer.copy(multiaddrs = toJsonStringList(parsed)))`. Preserve existing multiaddrs if the event has none (same local-only preservation pattern as `paymentDetails`/`matchedPeerId` — a re-announce must not wipe stored addrs).
- Also upsert the creator's multiaddrs into the local Peer row at offer creation (CreateOfferScreen already upserts own peer row).

### 1.4 PeerRegistry: add multiaddr lookup
`PeerInfo` gains `multiaddrs: List<String>` (or read from the Peer DAO — decide in code; the registry is in-memory, the DAO is durable, prefer DAO as source of truth). Add `fun multiaddrsOf(peerId: String): List<String>`.

### 1.5 Tests
- Offer JSON round-trip: create → publish → ingest preserves multiaddrs.
- Ingest with missing multiaddrs preserves existing stored addrs (no wipe on re-announce).
- Multiaddr string format: `/p2p/<peerId>` suffix present.

## Phase 2 — Direct dialing with relay fallback

### 2.1 jvm-libp2p upgrade
- Build the clone locally first: `cd ~/jvm-libp2p && ./gradlew :libp2p:jar` (JDK 17 pin applies; the clone targets JDK 11+).
- Try bumping `libs.versions.toml` to a locally-published build (or the next available release). **Risk:** the protobuf-java/javalite conflict documented in AGENTS.md — the app already excludes javalite and declares full protobuf-java, so the relay classes (which use full protobuf) should fit; verify with `:app:assembleDebug`.
- If the version bump fights the build, fallback: vendor the relay classes (`RelayTransport`, `CircuitHopProtocol`, `CircuitStopProtocol`, `AutonatProtocol` + their protos) from the clone into the app. Decide by build result, not by preference.

### 2.2 Wire RelayTransport + Autonat into the host
`LibP2PManager.createHost()`:
- Add `RelayTransport` with `candidateRelays` = `DEFAULT_LIBP2P_RELAYS` parsed to `CandidateRelay(relayPeerId, addrs)`. **Gap:** the config currently has no relay PeerID — the go relay's peerId is stable (persistent key) but unknown to the app. Options: (a) hardcode the relay peerId in `NeoP2PConfig` (fetch once from the relay's `/health` endpoint — it returns `peerID`), (b) dial the relay by address without peerId and let Noise verify identity (jvm-libp2p `hop.dial(us, relay)` accepts a bare multiaddr — the relay's identity is then TOFU). Recommend (a) with a config constant + comment; the health endpoint makes it a one-time lookup.
- Add `AutonatProtocol` for reachability detection (optional in this phase — it informs but doesn't gate).
- Keep TCP + WS transports; add the relay as a third transport so `/p2p-circuit` multiaddrs are handled.

### 2.3 Dial path
`LibP2PManager`:
- New `suspend fun dial(peerId: String, addrs: List<String>): Result<Unit>` — for each addr: try `host.newStream(protocols, peerId, multiaddr)` with a per-addr timeout (e.g. 5s); first success wins; on failure try the next. If all direct addrs fail and a relay is configured, dial `/p2p/<peerId>` via the relay (`/dns/relay1.../tcp/4001/p2p/<relayId>/p2p-circuit/p2p/<peerId>`).
- Keep the connection in `host.network` (NetworkImpl reuses existing connections to the same peer — no duplicate dials).
- `P2PTransport` interface: add `suspend fun dial(peerId: String, addrs: List<String>): Result<Unit>` (default no-op in `P2PTransportManager`).

### 2.4 Hybrid send path
`HybridP2PTransport.send()`:
1. If peer already in `libp2p.connectedPeerIds()` → direct send (existing path).
2. Else look up `peerRegistry.multiaddrsOf(toPeerId)` (from Phase 1) → `libp2p.dial(...)` → on success, direct send.
3. Else fall through to WS relay (existing path).
- `publish()`: keep Nostr as the durable bus — no change to the publish path in this phase (gossipsub is Phase 3, deferred).

### 2.5 Chat routing
`ChatRouter` already sends via `HybridP2PTransport` — no router changes needed; the transport handles the direct-vs-relay decision. Verify the E2EE envelope is untouched (it is — `TransportMessage.data` is opaque).

### 2.6 Tests
- Dial ordering: direct addr success → no relay attempt; direct failure → relay attempt; all fail → error.
- `HybridP2PTransport.send()` with a known direct peer uses libp2p (fake transports in unit test).
- Multiaddr construction for the circuit path.

## Verification (live, per flow-test convention)

1. Build: `./gradlew :app:assembleDebug` + `:app:testDebugUnitTest` + `:app:lintDebug` from `android/`.
2. OnePlus (seller) + emulator (buyer), same LAN: seller creates offer → buyer ingests → seller's chat send shows a direct libp2p stream in logcat (`/neop2p/chat/1.0.0` over TCP, not WS relay) → ConnectionQualityChip shows DIRECT.
3. Kill the WS relay container → chat still works between the two direct-connected peers; escrow sync still works via Nostr.
4. Kill the libp2p relay → strict-NAT peer still works via WS relay fallback.
5. Reinstall test: multiaddrs survive relay replay (no wipe on re-announce).

## Out of scope (this branch)

- Gossipsub (Phase 3) — deferred.
- Hole-punching / DCUtR — not in jvm-libp2p.
- WebRTC data-channel as a transport — exists but untouched.

## Open items

- Relay peerId for `NeoP2PConfig` — fetch from `https://relay1.custom-minipc.com:4002/health` (returns `peerID`) and hardcode with a comment.
- Whether the app's `DEFAULT_LIBP2P_RELAYS` entry needs the `/p2p/<relayId>` component added (it currently ends at `/p2p-circuit`).
