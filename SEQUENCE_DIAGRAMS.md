# NEO-P2P Sequence Diagrams

Render with `d2 ARCHITECTURE_DIAGRAMS.d2 output.svg` for the full set of diagrams.

## Offer Creation Flow

| Step | Actor | Action |
|------|-------|--------|
| 1 | User | Fill offer details (BTC amount, price, methods) |
| 2 | UI | Update fields via ViewModel |
| 3 | User | Tap "Create Offer" |
| 4 | ViewModel | `getOrCreateIdentity()` → IdentityManager |
| 5 | ViewModel | `saveOffer(offer)` → OfferRepository → Room `trade_offers` (status=OPEN, `expires_at` TTL) |
| 6 | ViewModel | `RnsTransport.trackOfferDigest(RnsOfferDigest.encode(offer, nickname))` → paced 2.5s re-announce loop |
| 7 | RnsSession | Announce `neop2p/offers` with digest appData (commitment only: `{v,id,h}`) |
| 8 | Peer | Announce handler → `offer_request` → LXMF DIRECT → serve `canonicalJson` → verify commitment → ingest |
| 9 | ViewModel | Navigate to Home screen |
| 10 | User | New offer visible in feed |

## Chat Messaging Flow (LXMF + app-level E2EE envelope)

| Step | Actor | Action |
|------|-------|--------|
| 1 | User | Type message and press send |
| 2 | ViewModel | `getOrCreateIdentity()` → IdentityManager |
| 3 | ViewModel | `encrypt(recipientPubkey, message)` → SignalProtocol (custom NIP-44-inspired E2EE) |
| 4 | ViewModel | `queue.send(peerId, envelope)` → OfflineQueue |
| 5 | Peer announces (fresh path) | `P2POrchestrator.drainPending` → `RnsSession.send` → LXMF DIRECT link |
| 6 | RnsSession | LXMF delivery (auto-Resource for >319B) |
| 7 | ViewModel | Save message to LocalDB (optimistic) |
| 8 | UI | Message appears in chat bubble |
| | | |
| **Receive** | | |
| 9 | RnsSession | Inbound LXMF → `peerIdByDestHash` maps source hash → peerId |
| 10 | ViewModel | `decrypt(privateKey, data)` → SignalProtocol |
| 11 | ViewModel | Save received message to LocalDB |
| 12 | UI | New message visible in chat |
