# RNS_WHY_AND_HOW — Why NEO-P2P Runs on Reticulum, and How This Fork Uses It

*Filed: 2026-09-01. State at time of writing: fork on v1.0.25, 266 tests passing, RNS/LXMF is the ONLY transport since v1.0.22.*

## 1. What Reticulum Is (the paradigm inversion)

Reticulum is **not a network you "join" — it is a toolkit for building networks**. You decide the mediums, how nodes connect, what trust boundaries exist, and what the network's purpose is. Reticulum provides the cryptographic foundation, transport mechanisms, and convergence algorithms; you provide the intent and structure.

- **Destinations, not addresses.** An address is a 16-byte hash (128 bits) = SHA-256 of the destination's identifying characteristics, including the node's public key. Any node generates unlimited destinations with zero coordination; same destination name + different keys = different hashes, both coexist. No address authority exists, so none can be captured. No subnets, no DHCP, no allocation planning.
- **Mediums are interchangeable.** LoRa, packet radio, WiFi, Ethernet, I2P, TCP/UDP tunnels, even encrypted QR paper — one protocol, one mesh. Adding a medium = implementing one interface class; the protocol itself does not change.
- **Zero-trust by construction.** All traffic encrypted with ephemeral ECDH keys on Curve25519. Packets carry no source address (initiator anonymity by default). Announces carry cryptographic signatures (path verification). Delivery confirmations are signed by the destination's identity key (unforgeable ACKs). No hop can inspect, prioritise, throttle, or impersonate.
- **Infrastructure is optional.** Instance = any node running the stack (default; phones, laptops). Transport Node = an instance configured for network-wide transport (forwards packets, propagates announces, maintains path tables, acts as a **distributed cryptographic keystore** caching announced public keys). Not every node should be a transport node (resource consumption, stability requirements, bandwidth on slow mediums). Any node *can* become one — the decision is yours.
- **Closed networks are available too.** IFAC (Interface Access Codes) restrict participation on any interface via a shared secret; Network Identities verify discovered interfaces belong to trusted operators; blackhole management blocks malicious identities.

## 2. Why It Fits NEO-P2P (what it buys)

1. **"Zero-backend" becomes structurally true.** There are three tiers:
   - **Tier 1 — no transport nodes**: two devices in communication range exchange offers/chat/escrow signalling directly, end-to-end encrypted. This tier literally cannot exist on the legacy relay stack.
   - **Tier 2 — transport nodes available**: multi-hop routing extends reach, and transport nodes cache public keys, so a peer that was offline when you announced can still resolve your destination hash and establish a link later.
   - **Tier 3 — mixed**: the operator's VPS becomes one of many optional amplifiers. Infrastructure amplifies; it never gates.
2. **Messaging is already solved.** LXMF gives direct / opportunistic / propagated delivery, ratchet sessions, stamps (anti-spam/anti-DoS), attachments, and PAPER/QR mode. The hand-rolled Nostr-kinds-over-WebSocket machinery reduces to LXMF fields + message titles.
3. **Anonymity + anti-censorship for free.** Source anonymity and uninspectable traffic match the app's core reason to exist — no relay operator can correlate sender → content.

## 3. How This Fork Actually Uses It (verified from code + CHANGELOG 1.0.22–1.0.25)

- **Phase 4: RNS/LXMF is the ONLY transport.** `LibP2PManager`, `P2PTransportManager`, `NostrClient`, `WebRTCManager`, `HybridP2PTransport` were deleted (v1.0.22). `RnsSession`/`RnsTransport` connect as a TCP client to the VPS transport node (`NeoP2PConfig.RNS_TRANSPORT_NODE_HOST/PORT`, rnsd-kt `enableTransport=true`).
- **Phones are client-only Instances** (`enableTransport=false`), with a deterministic 64-byte identity derived from the BIP-39 mnemonic (SLIP-10 `m/44'/999'/0'/0/1` curve25519 + `m/44'/999'/0'/0/2` ed25519) — peer IDs stay stable across the libp2p→RNS migration.
- **Peer addressing**: the app's peerId rides as the LXMF announce `displayName` (`lxmf.delivery(SINGLE, displayName=peerId)`), so the existing peerId-addressed send API is unchanged.
- **Offer feed is announce-based**: `neop2p/offers(SINGLE, appData=digest)` carries a **commitment-only** `RnsOfferDigest` (`{"v":1,"id":"offer_…","h":"<sha256 of canonical offer JSON>"}`, ~200B). Deliberately no trade data — announces broadcast in cleartext to every peer and the transport node, so any field would leak trading intent (G1). The full public offer subset is fetched on demand over **encrypted LXMF** (`offer_request` → `offer`) and verified against the commitment hash before ingest — a peer cannot announce one offer and serve a different one.
- **All signaling is LXMF DIRECT**: `offer_status` / `escrow_status` / `dispute` / `evidence` / `resolution` travel as LXMF messages (title = type, `FIELD_CUSTOM_DATA` = JSON); evidence images as file attachments (auto-Resource >319B).
- **Reliability machinery**: `EscrowRouter` is forward-only + no-downgrade; `OfferClaimGate` guards role transitions; failed DIRECT signaling re-queues (`pendingResends`); `OfflineQueue` covers cold-start; paced **2.5s offer re-announce** (12 announces/30s/dest, ~25% headroom under the fork's `MAX_RATE_TIMESTAMPS=16`/30s cap) is the sole feed-announce path.
- **Inbound digest pipeline**: `lxmf.delivery` announnce handler maps announced identity → peerId; digests arriving before their delivery announce are deferred (bounded: ≤32 digests/identity, ≤64 identities) and flushed once the delivery announce maps identity → peerId.
- **Infrastructure** (`infrastructure/`): `rns-transport` (rnsd-kt, transport enabled, `config` file bind-mounted `:ro` — note: rnsd-kt loads exactly `File(dir, "config")`, no extension) + `lxmf-propagation` (LXMF Propagation Node, `lxmd --config <dir> --rnsconfig <dir> -p`) on Oracle ARM64. Transport-node fixes that made it live: accepting TCP clients are registered with `Transport` (announce fan-out + path routing), LINKREQUEST forwarding passes the actual receiving interface (previously the parent no-op interface), TCP keepalive on the client interface, 20s delivery re-announce to keep NAT/firewall links alive.
- **Test harness**: 52 test files including `RnsSessionTest`, `RnsLoadTest`, `RnsSoakTest`, `RnsThreePeerTest`, `RnsTwoProcessIntegrationTest`, `RnsLatencyTest`, `RnsFaultInjectionTest` (fault proxy, flap server, offer flood server).

## 4. Honest Costs / Accepted Risks

- **NAT**: two phones behind symmetric NATs still need at least one public transport node for internet reach. Tier 1 (direct range) always works; internet-wide reach depends on the VPS transport node — which is now an amplifier, not a single point of failure for the whole product.
- **Async delivery UX**: announces propagate on timers and paths are discovered asynchronously; delivery is store-and-forward, not server push. Handled by `pendingResends`, `OfflineQueue`, resume-heal re-publishes, and the paced re-announce loops.
- **Frozen upstream**: RNS 1.5.1 is mature but unmaintained (maintainer stepped back; mirror-only public repo). The JVM fork (rns-core/lxmf-core, JDK 21 bytecode) is owned by this project — fixes land here (client registration, LINKREQUEST wiring, config loading) and must keep landing here.
- **Cleartext announce leakage**: solved by the commitment-only digest (G1) — the only cleartext payload is a hash commitment; everything trade-related travels over encrypted LXMF.
- **Rate limits**: the fork's path admission caps announces per destination per 30s; the 2.5s paced loop respects it with ~25% headroom (verified zero drops at production cadence by RnsLoadTest).

## 5. Verdict

Reticulum is the correct backbone for what NEO-P2P claims to be (zero-backend, P2P, anonymous). The migration is **done in this fork** — the protocol is not the risk. The remaining risks are NAT expectations, delivery-timing UX, and JVM-fork maintenance. The VPS transport node should be treated as an optional amplifier (Tier 3) while the app stays capable in Tier 1/2.

## See Also

- `docs/` — CRITICAL.md, IDENTITY_REWRITE.md, SECURITY_POSTURE.md, DEBUG_MAP.md, SCENARIO_MATRIX.md
- Reticulum Building Networks manual reference: `~/neo-p2p/docs/RETICULUM_BUILDING_NETWORKS.md` + wiki `topics/radio/reticulum-building-networks.md` + full manual mirror `~/wiki/docs/topics/radio/reticulum-manual/`
- Sideband reference client: wiki `topics/radio/sideband-architecture.md`
