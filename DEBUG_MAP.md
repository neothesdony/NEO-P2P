# NEO-P2P DEBUG_MAP

Date: 2026-09-01 · HEAD: 45e9574 · Branch: main
Scope: Android app (`android/`), RNS/LXMF transport (Phase 4 — the ONLY transport), on-chain 2-of-3 escrow.

---

## 1. Component diagram (text)

```
┌────────────────────────────── ANDROID APP (com.neop2p.app) ──────────────────────────────┐
│                                                                                          │
│  UI (Compose, 8+ screens)                                                                │
│   HomeScreen / OfferDetail / CreateOffer / EscrowScreen / ReceiptComposer / ChatScreen   │
│   WalletScreen / DisputeFeed / History / Settings / Onboarding / Invite                   │
│        │ ViewModels (Hilt)                                                                │
│  ┌─────▼──────────────────────────────────────────────────────────────────────────┐     │
│  │ P2POrchestrator (data/p2p/P2POrchestrator.kt) — routing + sweep + notifications │     │
│  │   ├─ RnsTransport (P2PTransport impl) ── RnsSession (pure-JVM core)             │     │
│  │   │     ├─ Reticulum singleton (client-only, enableTransport=false)             │     │
│  │   │     │    └─ TCPClientInterface → VPS transport node :42000                  │     │
│  │   │     ├─ LXMRouter (delivery: DIRECT links, retries 5×10s, >319B Resource)   │     │
│  │   │     ├─ lxmf.delivery announce (displayName = peerId) + 20s re-announce      │     │
│  │   │     └─ neop2p/offers announce (appData = RnsOfferDigest ~200B)              │     │
│  │   ├─ SignalProtocol (E2EE chat: X25519+HKDF+ChaCha20-Poly1305, TOFU)            │     │
│  │   ├─ OfferRouter (ingest + status, OfferClaimGate)                               │     │
│  │   ├─ EscrowRouter (mirror ingest, forward-only)                                  │     │
│  │   ├─ ChatRouter (E2EE envelopes, payment details/receipts)                      │     │
│  │   ├─ OfflineQueue (Room pending_messages — chat/pre-key ONLY)                   │     │
│  │   └─ PeerRegistry (in-memory presence + quality)                                 │     │
│  └─────┬──────────────────────────────────────────────────────────────────────────┘     │
│  ┌─────▼──────────────────────────────────────────────────────────────────────────┐     │
│  │ EscrowService (data/escrow/EscrowService.kt) — 2-of-3 P2SH/P2WSH state machine │     │
│  │   └─ ChainMonitor (Mempool/Esplora explorers, fee est, broadcast, conf depth)   │     │
│  │ WalletService (BIP-44 wallet, raw-tx send, UTXO selection)                     │     │
│  └─────┬──────────────────────────────────────────────────────────────────────────┘     │
│  ┌─────▼──────────────────────────────────────────────────────────────────────────┐     │
│  │ Room/SQLCipher v23 (AppDatabase) + SharedPreferences (identity blob, dedup,    │     │
│  │  drafts, onboarding gate) + KeyStore (AES-GCM seed wrap, auth-gated)            │     │
│  └────────────────────────────────────────────────────────────────────────────────┘     │
└──────────────────────────────────────────────────────────────────────────────────────────┘

┌────────────────────────────── VPS (relay1.custom-minipc.com) ────────────────────────────┐
│  Python rnsd transport node (enableTransport=true, TCP server :42000) — routes           │
│  announces, paths, links between peers; LXMF propagation node (store-and-forward for     │
│  offline peers). announce_rate_target=1 REQUIRED (default 3600 blocks app destinations). │
└──────────────────────────────────────────────────────────────────────────────────────────┘
```

## 2. Data flow per user journey

### J1 — Offer advertise → discover
1. `CreateOfferScreen` → `CreateOfferViewModel.createOffer` → Room `trade_offers` (status OPEN, `expires_at` TTL) → `RnsTransport.trackOfferDigest(RnsOfferDigest.encode(offer, nickname))` → paced 2.5s re-announce loop → announce `neop2p/offers` with digest appData (RnsSession). **2026-09-01 (Bug B):** the one-shot `publishOffer` at create/edit was removed — the paced loop owns every feed announce (a duplicate within the fork's 16/30s-per-dest window risked drops).
2. Peer: `Transport.registerAnnounceHandler(aspectFilter="neop2p.offers")` (RnsSession.kt:180-186) → `handleOfferAnnounce` cross-checks identity vs lxmf.delivery table (RnsSession.kt:545-558) → `_offerAnnounces` → `P2POrchestrator` (P2POrchestrator.kt:257-266): decode digest → if offer unknown → `sendOfferRequest` (LXMF DIRECT).
3. Creator: `offer_request` handler (P2POrchestrator.kt:222-248) → rebuild offer JSON (payment details EXCLUDED, P0-1) → `sendOffer` → peer `OfferRouter.ingestRnsOffer` → `ingestOfferEvent` (OfferRouter.kt:264-426) → Room upsert + creator Peer row upsert.

### J2 — Accept / lock
1. Taker taps Accept → `OfferDetailScreen` → `acceptOffer` → local status MATCHED + `matched_peer_id` → `publishOfferStatusDual` → `RnsSession.sendOfferStatus` (LXMF DIRECT, RnsSession.kt:342-360).
2. Creator: `offer_status` → `OfferRouter.applyOfferStatus` (OfferRouter.kt:104-235) → `OfferClaimGate.effectiveStatus` (no-downgrade, creator-only unlock) + `adoptMatchedPeer` (two-taker race) → Room + notify seller (lock-proof foreign-peer check, OfferRouter.kt:219-231).
3. `republishLostClaims` (60s sweep) re-sends MATCHED if the first send died (P2POrchestrator.kt:651-654).

### J3 — Escrow create → fund → pay → release
1. Seller `createSellerEscrow` / buyer `acceptOffer` → `EscrowService.createEscrow` (EscrowService.kt:673-775): 2-of-3 P2SH/P2WSH address, deposit = C + fee(0.5%, integer) + networkFee, `seller_refund_address` set → Room + `publishEscrowSync` (LXMF escrow_status, EscrowService.kt:205-220).
2. Buyer: `EscrowRouter.ingestEscrowStatus` (EscrowRouter.kt:136-242) — party gate, create mirror row, forward-only `applyRemoteStatus`.
3. Seller funds: `onEscrowFunded` (EscrowService.kt:835-917) — txid bound to address+at-least-deposit (`findFundingOutputAtLeast`), the ACTUAL on-chain value recorded as `funded_amount_sats` (excess over the deposit is returned to the seller by payout/refund), txid synced immediately (FUNDING+txid), FUNDED only after `required_confirmations` (default 1, depth from tip height).
4. Auto-share bank details: `funded` transition → `ChatRouter.autoSharePaymentDetails` (E2EE) + 60s sweep `retryPaymentDetailShares` (P2POrchestrator.kt:794-817).
5. Buyer `markPaid` → PAYMENT_PENDING (peerId-gated BUYER) → `sendReceipt` → RECEIPT_SENT (reference + optional screenshot, E2EE `payment_receipt` payload).
6. Seller `confirmReceipt` → CONFIRMING → `releaseFunds` (EscrowService.kt:1124-1211): 2-of-3 assemble (role-pinned sigs), broadcast, RELEASED, offer → COMPLETED, sync both.

### J4 — Dispute / arbitration
1. Party `disputeEscrow` → DISPUTED + `publishDisputeRns` (P2POrchestrator.kt:700-739) → LXMF `dispute` to counterparty + arbitrator (via `ARBITRATOR_PEER_ID` — set since 2026-09-02) + `PendingDisputeStore` retry (ack-gated publish-then-commit, per-target durable retry).
2. Arbitrator: `DisputeFeed` (persisted `arbitrator_disputes` Room v23, carries `buyer_peer_id`/`seller_peer_id` so the resolution can be delivered to the parties) → `arbitratorSignTx` (remote sign, BIP-143 for P2WSH) → `sendResolution` (LXMF `resolution` with `signed_tx_hex`).
3. Party: `applyResolutionEvent` (P2POrchestrator.kt:554-613) — verifies the arbitrator's signature BEFORE marking the feed resolved → `storeArbitrationDecision` → broadcast exact signed tx → RELEASED/REFUNDED → sync. Re-deliveries deduped (`shouldProcessDispute`).

### J5 — Chat
1. Two-shot pre-key handshake: `pre_key_request` → `pre_key_bundle` (identity-bound: Ed25519 sig over X25519 key, peerId derivation check, SignalProtocol.kt:271-328) → session in SQLCipher `conversation_keys`.
2. `ChatRouter.sendText` → encrypt → OfflineQueue → drain (live if peer announced).
3. Inbound: `receiveChat` → ciphertext dedup → decrypt → Room `chat_messages` (payment_details/receipt/reject payloads filtered to cards/offer row).

## 3. Trust boundaries

| Boundary | Trust | Notes |
|---|---|---|
| App ↔ VPS transport node | **Routing trust only** | Node sees all announces (plaintext appData) + link metadata; cannot decrypt LXMF DIRECT link payloads (RNS link encryption) or app E2EE. Node can drop/blackhole traffic (availability, not confidentiality). |
| App ↔ LXMF propagation node | Store-and-forward | Offline delivery; same confidentiality as above. |
| Peer ↔ peer (LXMF DIRECT) | Link-encrypted | RNS link encryption + app E2EE (SignalProtocol) double layer. |
| Peer identity ↔ peerId | Pseudonymous | peerId = libp2p base58 of Ed25519 pubkey derived from mnemonic — stable forever, same across ALL trades (linkability by design, see §5). |
| Escrow roles | peerId-bound | Single-key model: buyer/seller pubkeys identical on one device; roles distinguished by peerId only (EscrowService.kt:1600-1607). |
| Fee wallet / arbitrator pubkey | Signature-pinned | Ed25519 owner signature verified at startup + enforced in createEscrow/dispute/resolve (NeoP2PConfig.kt:120-178). |
| Chain explorers (mempool.space etc.) | Read/broadcast | Public APIs; no secrets sent. Broadcast path is explicit (public explorer), user-visible. |

## 4. Secrets inventory

| Secret | Storage | Exposure |
|---|---|---|
| BIP-39 mnemonic (12 words) | SharedPreferences `neop2p_identity` blob, AES-256-GCM, KeyStore-wrapped key (auth-gated) | Never logged; seed-restore screen shows it (masked, auto-clear 60s) |
| X25519 chat key (m/44'/999'/0'/0/0) | Derived on demand from mnemonic | Never persisted in plaintext |
| Ed25519 libp2p/RNS key (m/44'/888', m/44'/999'/0'/0/2) | Derived on demand | Public part = peerId (announced) |
| secp256k1 Bitcoin key (m/44'/0') | Derived on demand | Public part in escrow redeem script (on-chain, by design) |
| Arbitrator key (m/44'/999'/0'/1/0) | Derived on demand (admin device only) | Public x-only in NeoP2PConfig |
| Peer X25519 keys | SQLCipher `conversation_keys` | Encrypted at rest |
| Bank details / payment details | Room `trade_offers.payment_details` (SQLCipher) | E2EE chat only, never on wire in clear (P0-1) |
| TURN creds / relay URL | `local.properties` (gitignored) | BuildConfig; never committed |
| Fee-wallet signer key | `android/fee-wallet-secret.key` (gitignored) | Owner machine only |

## 5. Anonymity / linkability audit

- **peerId is a global stable pseudonym**: announced in EVERY lxmf.delivery announce (displayName) and in every offer digest (`c` field). Same identity across all trades → all offers/trades of one user are trivially linkable to one peerId. This is the user's chosen identity reuse (documented in AGENTS.md); per-trade keys exist only as dead code (`PATH_NOSTR_TRADE_PREFIX`, `getNextTradeNostrKeyPair` — no caller since Nostr removal).
- **⚠️ PLAINTEXT TRADING INTENT ON THE WIRE (G1) — FIXED 2026-09-01**: the `neop2p/offers` announce appData used to carry the FULL digest in clear: `{id, creatorPeerId, type, fiatAmount, cryptoAmountSats, pricePerUnit, fiatMethods, nickname, expiresAt}` (old RnsOfferDigest.kt:33-48). RNS announces are NOT encrypted — any RNS peer (and the transport node) saw amounts, methods, and nickname for every offer. **Fix:** the digest is now a commitment only — `{"v":1,"id":"<offerId>","h":"<sha256 of canonical offer JSON>"}` (RnsOfferDigest.kt). The full public subset (incl. fiat_methods + nickname, which the old served JSON omitted) travels over encrypted LXMF via `RnsOfferDigest.canonicalJson` (P2POrchestrator offer_request handler), and the fetched JSON is verified against the digest commitment before ingest (P2POrchestrator "offer" handler) — a peer cannot announce one offer and serve a different one. Payment details + BTC receive address remain local-only (P0-1). Tests: RnsOfferDigestTest `digest carries no trade data - G1 invariant`, `tampered served json fails the commitment`.
- **Logs**: `RnsSession` prints full peerId + 12-hex dest prefixes (RnsSession.kt:196, 530). peerId is a public pseudonym — acceptable, but the mission standard prefers hash prefixes; consider `peerId.take(12)` in logs. No mnemonic/keys logged anywhere (verified: no `seedPhrase`/`privateKey` in Log/println calls).
- **Inbound from unknown peer dropped** (RnsSession.kt:577-580) — no source-address leak beyond RNS.
- **Timing oracle (I10)**: `send()` to unknown peer fails fast with "No RNS path" vs known peer proceeds — observable difference to a local observer; low risk (RNS hides source), flag only.

## 6. Failure domains

| Domain | Mechanism | Coverage |
|---|---|---|
| Link drop / TCP flap | TCPClientInterface keepAlive=true + 20s re-announce (RnsSession.kt:133-146, 190-195, 622) | Heals path; **S05/S06 verified 2026-09-01** (RnsFaultInjectionTest: proxy kill mid-conversation → reconnect + re-announce → failed DIRECT signaling re-sent on next announce). **Fix:** failed DIRECT signaling (offer_status/escrow_status/dispute/evidence/resolution/offer_request/offer) is re-queued in RnsSession.pendingResends (bounded 3 attempts, ≤16KB) and re-sent on the next peer announce — a DIRECT link that dies mid-trade no longer silently loses the message (chat/pre-key already ride the durable OfflineQueue). |
| Peer offline at send | `send()` fails fast → OfflineQueue (chat/pre-key only) | **Signaling (offer_status/escrow_status/dispute/evidence/resolution) is NOT queued** — fire-and-forget `runCatching`; LXMF retries only cover ~50s. Heals: resume-heal re-publish (getEscrow), republishLostClaims (MATCHED), PendingDisputeStore (dispute). **Gap: evidence + resolution have NO durable retry.** |
| Announce unseen / delayed | 20s re-announce; peer map in-memory (lost on restart until re-announce) | B3/B4 scenarios UNKNOWN |
| Chain reorg / un-confirm | **E7 fixed 2026-09-01**: sweep re-verifies the funding tx before auto-refund — unconfirmed + no address balance → revert to FUNDING (re-verify/cancel); explorer failure fails closed (skip). EscrowReorgTest + fundingDepositGone. | FUNDED is no longer one-shot: a reorg that un-confirms the funding tx is caught before a refund spends a nonexistent output. |
| Clock skew / jump | Wall-jump guard (>2h) + rollback guard in expireStaleEscrows (EscrowService.kt:447-469); countdowns are wall-clock | Skew between devices shows as different countdowns (documented) |
| Disk full / read-only | Room/SQLCipher writes throw → caught per-call; identity dir write fails → Reticulum.start throws → transport down | A7 UNKNOWN |
| NAT / LoRa / other interfaces | Phones are TCP clients only; no AutoInterface on device | B11: only TCPClient path exists on device |
| MTU / fragmentation | Digest ≤ ~300B cap; >319B LXMF auto-Resource (chunked+BZ2+retransmit) | Large receipts/evidence covered by Resource; J1 (100 offers) UNKNOWN |
| Store-and-forward | LXMF propagation node (VPS) | Offline delivery UNKNOWN (propagation node behavior not exercised in tests) |
| Two instances same identity dir | Reticulum singleton in-process; cross-process identity file locking UNKNOWN | A6 UNKNOWN |
| Kill -9 mid-transition | Status persisted BEFORE broadcast (crash-safe); resume-heal re-publishes on load | A3 partially covered by design; SIGNED zombie fixed 2026-08-28 |

## 7. Key file:line index

- Transport: `data/p2p/RnsSession.kt` (start :109, send :218, sendFile :249, isDirect :279, handlePeerAnnounce :515, handleOfferAnnounce :545, handleInbound :575, trackOfferDigest, re-announce :190)
- Transport wrapper: `data/p2p/RnsTransport.kt` (start :58, send :109, publishOffer :126, isDirect :206)
- Orchestrator: `data/p2p/P2POrchestrator.kt` (LXMF routing :173-254, offer-feed :257-266, drain :273-305, sweep :623-658, dispute publish :700-739, resolution apply :554-613)
- Offer: `data/p2p/routing/OfferRouter.kt` (applyOfferStatus :104, ingestOfferEvent :264, ingestRnsOffer :434, republishLostClaims :450); `OfferClaimGate.kt` (effectiveStatus :33, adoptMatchedPeer :93)
- Escrow mirror: `data/p2p/routing/EscrowRouter.kt` (applyRemoteStatus :65, ingestEscrowStatus :136)
- Escrow machine: `data/escrow/EscrowService.kt` (createEscrow :673, onEscrowFunded :835, generatePayoutTransaction :924, releaseFunds :1124, confirmReceipt :1512, markPaid :1420, sendReceipt :1467, disputeEscrow :1374, storeArbitrationDecision :1806, expireStaleEscrows :439, resume-heal :262-291, publishEscrowSync :205)
- Chain: `data/escrow/ChainMonitor.kt` (parseTxInfo :64, broadcastTx :215, estimateFees :197, getTxInfo :235)
- E2EE: `data/p2p/SignalProtocol.kt` (createSession :271, encrypt :351, decryptWithKey :418, bundle :119)
- Chat: `data/p2p/routing/ChatRouter.kt` (sendText :47, receiveChat :106, autoShare :224, resend :247)
- Identity: `data/p2p/IdentityManager.kt` (derive :304, restore :129, arbitrator :543); `KeyDerivation.kt` (rnsIdentity :113, peerId :84)
- Config: `NeoP2PConfig.kt` (transport node :66-67, fee :25-47, arbitrator :54-87)
- Digest: `data/p2p/RnsOfferDigest.kt` (encode :33)
- Queue: `data/p2p/queue/OfflineQueue.kt` (send :16, drainFor :33)
- Envelope: `data/p2p/protocol/EnvelopeCodec.kt` (encode :10, decode :36)
- Tests: `RnsTwoProcessIntegrationTest.kt` (2-JVM TCP), `RnsSessionTest.kt` (in-JVM synthetic), `EscrowRouterApplyTest.kt`, `OfferClaimGateTest.kt`, `EscrowTimeoutTest.kt`, `ChainMonitorTxInfoTest.kt`, `ChaChaRoundTripTest.kt`
