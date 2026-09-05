# NEO-P2P Architecture Overview

Render with `d2 ARCHITECTURE_DIAGRAMS.d2 output.svg`.

> **Status (2026-09-02):** Phase 4 (2026-08-31) removed libp2p, the WS relay, Nostr, and WebRTC — **RNS + LXMF is the only transport**. This file reflects the live architecture.

## Layers

**Mobile App** → UI / ViewModels / Repositories / Use Cases / Data Models

**P2P Networks** → RNS (transport + discovery), LXMF (messaging + signaling), bitcoinj multisig (escrow)

**Local Storage** → SQLCipher DB, Android KeyStore

## Data Flow

| Direction | From | To | Description |
|-----------|------|----|-------------|
| Observes | UI | ViewModels | State collection |
| Calls | ViewModels | Repositories | Business logic |
| Announces | Repositories | RNS `neop2p/offers` | Offer digest feed (commitment-only, ~200B) |
| Fetches | Repositories | LXMF DIRECT | Full offer JSON on demand (`offer_request` → `offer`) |
| Signals | Repositories | LXMF DIRECT | offer_status / escrow_status / dispute / evidence / resolution |
| Creates/Signs | Repositories | bitcoinj multisig | Escrow transactions |
| Persists | Models | SQLCipher DB | Local storage |

## P2P Strategy (Phase 4 — RNS/LXMF only)

1. **Transport**: phones connect as TCP clients to the VPS transport node (official Python rnsd, port 42420); the node routes announces, paths, and links between peers and to the LXMF propagation node.
2. **Tier 1 LAN**: phones also register an RNS `AutoInterface` (IPv6 link-local multicast + per-peer UDP unicast) — two devices on one Wi-Fi exchange announces/paths/DIRECT LXMF links with no transport node in the path.
3. **Tier 3 multi-node**: users can add extra RNS transport nodes in Settings (`TransportNodeStore`); every node is a packet ferry, not a trust anchor.
4. **Messaging**: LXMF DIRECT links for chat + signaling; the Python `lxmd` propagation node provides store-and-forward for offline peers.
5. **E2EE**: custom NIP-44-inspired scheme (X25519 ECDH + HKDF-SHA256 + ChaCha20-Poly1305) over LXMF.
6. **Escrow**: bitcoinj builds 2-of-3 multisig on Bitcoin testnet4 (Testnet4 — same address format as Testnet3, different chain).

## Key Components

| File | Responsibility |
|------|----------------|
| `data/p2p/RnsSession.kt` | Pure-JVM RNS + LXMF core (client-only, TCP clients + AutoInterface) |
| `data/p2p/RnsTransport.kt` | Android wrapper (P2PTransport impl; MulticastLock for AutoInterface) |
| `data/p2p/RnsOfferDigest.kt` | Compact commitment-only offer digest for the announce feed |
| `data/p2p/SignalProtocol.kt` | E2EE chat encryption (X25519 + ChaCha20-Poly1305, custom NIP-44-inspired) |
| `data/p2p/P2POrchestrator.kt` | LXMF routing, offer-feed pipeline, 60s sweep, dispute/evidence/resolution ingest |
| `data/p2p/routing/OfferRouter.kt` | Offer ingest + status (OfferClaimGate) |
| `data/p2p/routing/EscrowRouter.kt` | Mirror escrow ingest, forward-only |
| `data/p2p/routing/ChatRouter.kt` | E2EE envelopes, payment details/receipts |
| `data/local/TransportNodeStore.kt` | Extra RNS transport nodes (Settings, SharedPreferences JSON) |
| `data/escrow/EscrowService.kt` | 2-of-3 multisig escrow + fee payout |
| `data/escrow/ChainMonitor.kt` | Testnet4 Mempool/Blockstream API for funding verification + fees |
| `data/wallet/WalletService.kt` | Personal wallet: balance, history, raw-tx send |
| `data/local/AppDatabase.kt` | Room + SQLCipher persistence (v24) |
| `data/local/SqlCipherPassphraseManager.kt` | KeyStore-derived DB passphrase |
| `data/reputation/ReputationSystem.kt` | Attestations over LXMF (sender-authenticated ingest, 2026-09-04) |
| `service/P2PBackgroundService.kt` | Foreground service keeping transports alive |
