# NEO-P2P Sequence Diagrams

Render with `d2 ARCHITECTURE_DIAGRAMS.d2 output.svg` for the full set of diagrams.

## Offer Creation Flow

| Step | Actor | Action |
|------|-------|--------|
| 1 | User | Fill offer details (BTC amount, price, methods) |
| 2 | UI | Update fields via ViewModel |
| 3 | User | Tap "Create Offer" |
| 4 | ViewModel | `getOrCreateIdentity()` → IdentityManager |
| 5 | ViewModel | `saveOffer(offer)` → OfferRepository |
| 6 | OfferRepository | Insert offer to LocalDB (status=OPEN) |
| 7 | OfferRepository | `publishOffer(offer)` → NostrClient |
| 8 | NostrClient | Returns Nostr event ID |
| 9 | OfferRepository | Update offer in LocalDB (synced=1) |
| 10 | ViewModel | Navigate to Home screen |
| 11 | User | New offer visible in feed |

## Chat Messaging Flow (libp2p with Nostr Fallback)

| Step | Actor | Action |
|------|-------|--------|
| 1 | User | Type message and press send |
| 2 | ViewModel | `getOrCreateIdentity()` → IdentityManager |
| 3 | ViewModel | `encrypt(recipientPubkey, message)` → SignalProtocol |
| 4 | ViewModel | `openStream(peerId, ...)` → LibP2PManager |
| 5 | **alt** Stream available | |
| 5a | LibP2PManager | Return NetworkStream |
| 5b | ViewModel | Write encrypted data to stream |
| 5c | NetworkStream | Ack |
| 6 | **else** No stream | Fallback to Nostr |
| 6a | ViewModel | `publishChatMessage(offerId, encryptedMsg)` → NostrClient |
| 7 | ViewModel | Save message to LocalDB (optimistic) |
| 8 | UI | Message appears in chat bubble |
| | | |
| **Receive** | | |
| 9 | LibP2PManager/Nostr | Incoming encrypted data |
| 10 | ViewModel | `decrypt(privateKey, data)` → SignalProtocol |
| 11 | ViewModel | Save received message to LocalDB |
| 12 | UI | New message visible in chat |
