# NEO-P2P Architecture Overview

Render with `d2 ARCHITECTURE_DIAGRAMS.d2 output.svg`.

## Layers

**Mobile App** → UI / ViewModels / Repositories / Use Cases / Data Models

**P2P Networks** → Nostr (discovery), libp2p (direct P2P), WebSocket relay (fallback), bitcoinj multisig (escrow)

**Local Storage** → SQLCipher DB, Android KeyStore

## Data Flow

| Direction | From | To | Description |
|-----------|------|----|-------------|
| Observes | UI | ViewModels | State collection |
| Calls | ViewModels | Repositories | Business logic |
| Publishes/Subscribes | Repositories | Nostr | Offer events, peer metadata |
| Streams | Repositories | libp2p | Direct P2P data (preferred) |
| Streams (fallback) | Repositories | WebSocket relay | NAT/firewall fallback |
| Creates/Signs | Repositories | bitcoinj multisig | Escrow transactions |
| Persists | Models | SQLCipher DB | Local storage |

## Hybrid P2P Strategy

1. **Discovery**: Nostr relays broadcast trade offers and peer multiaddrs.
2. **Direct**: Peers attempt libp2p connection first (TCP, then WebSocket transport).
3. **Fallback**: If direct libp2p fails, traffic flows through the WebSocket relay.
4. **E2EE**: Signal Protocol runs over either transport.
5. **Escrow**: bitcoinj builds 2-of-3 multisig on Bitcoin testnet; LDK Lightning integration is planned.

## Key Components

| File | Responsibility |
|------|----------------|
| `data/p2p/LibP2PManager.kt` | Direct libp2p host (TCP/WebSocket, Noise, Mplex) |
| `data/p2p/P2PTransportManager.kt` | WebSocket relay fallback client |
| `data/p2p/HybridP2PTransport.kt` | Selects direct vs fallback transport |
| `data/p2p/NostrClient.kt` | Nostr event publishing/subscription |
| `data/p2p/SignalProtocol.kt` | E2EE chat encryption |
| `data/p2p/WebRTCManager.kt` | Data channel file transfer |
| `data/escrow/EscrowService.kt` | 2-of-3 multisig escrow + fee payout |
| `data/escrow/ChainMonitor.kt` | Mempool API for funding verification |
| `data/local/AppDatabase.kt` | Room + SQLCipher persistence |
| `data/local/SqlCipherPassphraseManager.kt` | KeyStore-derived DB passphrase |
| `data/reputation/ReputationSystem.kt` | Signed gossip attestations |
| `service/P2PBackgroundService.kt` | Foreground service keeping transports alive |
