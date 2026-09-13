# NEO-P2P Arbitration Protocol (Option 1)

How disputes travel between parties and the arbitrator over LXMF (Phase 4 —
the Nostr relay was removed) — zero backend, keys never leave devices.

## Message types (LXMF DIRECT, title = type, FIELD_CUSTOM_DATA = JSON)

| Type | Name | Content | Producer |
|------|------|---------|----------|
| `dispute` | Dispute opened | `{escrow_id, offer_id, opened_by, reason, opened_at, redeem_script_hex, psbt_hex, refund_tx_hex, deposit_sats, trade_sats, funding_script_type, seller_refund_address, buyer_btc_address, buyer_peer_id, seller_peer_id, buyer_pubkey_hex, seller_pubkey_hex, seller_refund_attestation, buyer_address_attestation}` | party |
| `evidence` | Evidence | `{escrow_id, submitter, description, mime_type}` + image as LXMF file attachment | party |
| `resolution` | Resolution | `{escrow_id, decision, arbitrator_sig_hex, notes, decided_at, seller_refund_address, signed_tx_hex}` | arbitrator |

Delivered DIRECT to the counterparty (and to `NeoP2PConfig.ARBITRATOR_PEER_ID`
when set — blank = RNS arbitration delivery disabled). LXMF messages are
encrypted to the destination identity; the transport node cannot read them.

`buyer_peer_id`/`seller_peer_id` (v23, 2026-09-02) are carried by the dispute
event so the arbitrator — who has NO local escrow row — can deliver the
resolution to the parties. Pre-v23 the resolution was sent to nobody and the
funds stayed locked in the multisig forever.

**F2 (2026-09-12):** the dispute additionally carries the role keys
(`buyer_pubkey_hex`/`seller_pubkey_hex`), the destinations (`buyer_btc_address`
+ `seller_refund_address`), `offer_id`, `trade_sats`, and the role-signed
attestations (`seller_refund_attestation` scope=escrowId,
`buyer_address_attestation` scope=offerId). The same attestations ride in
`escrow_status`/`offer_status`. A dispute from a pre-v27 build has no
attestations — the arbitrator refuses to sign and the parties refuse to apply
it (fail closed).

**F1 (2026-09-12):** dispute/evidence/resolution ingest is only accepted when
the LXMF sender's delivery destination maps back to the claimed peerId through
a verified `neop2p.identity` binding. A pre-F1 peer's arbitration message is
dropped until it upgrades.

## Flow

```
Party opens dispute ──LXMF "dispute"──▶ counterparty + arbitrator
   (publish-then-commit: psbt_hex = payout, refund_tx_hex = pre-built refund,
    redeem_script_hex lets a REMOTE arbitrator sign either; delivery must
    succeed before local DISPUTED 2026-08-30; PendingDisputeStore retries
    every 60s via P2POrchestrator.sweepStaleEscrows (5 min when backgrounded))

Party submits evidence ──LXMF "evidence"──▶ arbitrator feed
   (image ≤60KB compressed + description; stored locally in SQLCipher AND
    delivered over LXMF; arbitrator persists the copy, survives reboot)

Arbitrator (admin identity) reviews feed:
   - verifies the tx destinations against the role-signed attestations +
     ResolutionGuard, and that the role key is a key of the redeem script
     BEFORE signing (F2) — an unattested / legacy dispute is refused
   - signs psbt_hex with arbitrator key (m/44'/999'/0'/1/0)
   - EscrowService.arbitratorSignTx() sanity-verifies the sig
   - sends LXMF "resolution" with decision + signature to BOTH parties
     (targets from the dispute row's buyer_peer_id/seller_peer_id — the
     arbitrator has no local escrow row; v23 fix, 2026-09-02)

Winning party receives "resolution":
   - P2POrchestrator verifies the arbitrator's signature BEFORE applying
     (a forged resolution cannot hide the dispute or drop the pending one)
   - persists the seller's refund address (seller_refund_address
     → escrows.refund_destination) BEFORE applying the decision; a non-blank
     local refund destination is never overwritten by an incoming mismatch
   - re-runs ResolutionGuard against the LOCAL attested destinations BEFORE
     broadcasting (F2) — the arbitrator signature is not a license to pay a
     different address — and ReleaseIntegrity (F-3, 2026-09-13) validates the
     exact payout immediately before broadcast, rebuilding the honest payout
     once if a peer poisoned the stored tx
   - storeArbitrationDecision() applies status RELEASED/REFUNDED (idempotent)
   - assembles the 2-of-3 scriptSig (arbitrator sig + the local key filling the
     buyer/seller role slots) and broadcasts the payout/refund via
     ChainMonitor.broadcastTx — funds move immediately, no manual broadcast step
   - RELEASE_TO_BUYER → payout tx sends tradeAmountSats to the BUYER + fee wallet
   - REFUND_TO_SELLER → refund tx returns the deposit to the SELLER's address
     (escrows.refund_destination, carried from the resolution message) — NEVER
     the applying device's own wallet (pre-v20 bug: the refund paid whoever
     applied the decision, so an arbitrator-applied refund paid the arbitrator)
```

Auto-disputes (payment window + grace expiry **and the funded-stall escalation** — the
seller's sweep, 60s foreground / 5 min backgrounded) also deliver a `dispute` event to
`ARBITRATOR_PEER_ID` with per-target durable retry (`PendingDisputeStore.targets`) —
pre-v23 the arbitrator's feed stayed empty and the escrow was unresolvable (funds locked,
no tie-break key available).

**F-1 (2026-09-13) — the only refund route is the arbitrator.** After C1 the buyer and
seller escrow keys are distinct, so the old single-key both-slots refund
(`refundInternal`) was unsatisfiable and has been deleted. A seller's **"Request refund"**
(`EscrowService.refundRequestKind`) either cancels a never-funded escrow locally
(`cancelUnfundedEscrow`, nothing on-chain to spend) or opens a dispute
(`escalateToDispute`) that the arbitrator co-signs. A funded-but-stalled
FUNDED/SIGNED escrow escalates to a dispute after `ESCROW_FUNDED_STALL_TIMEOUT_MS` + `FUNDED_STALL_GRACE_MS`
(2 h + 2 h) instead of attempting an impossible refund. There is **no unilateral seller
on-chain refund**; `storeArbitrationDecision` remains the only refund broadcaster.

## Trust model

- **2-of-3 on-chain:** the payout/refund tx spends the P2SH multisig only with
  2 valid signatures. Arbitrator provides one; the winning party provides the
  other. The losing party's signature is irrelevant.
- **Arbitrator key:** derived at `m/44'/999'/0'/1/0` from the ADMIN's BIP-39
  mnemonic. `NeoP2PConfig.ARBITRATOR_PUBKEY` is the x-only pubkey of that
  path. The app unlocks Arbitrator Mode only when the active identity's
  derived pubkey matches it. The key never exists in an APK or on the wire.
- **Idempotency:** dispute/evidence/resolution messages can be re-delivered
  (LXMF retries, resume-heal); `storeArbitrationDecision` keeps the first
  decision and never downgrades a terminal status.
- **Sender authentication (2026-09-02):** dispute/evidence/resolution ingest is
  sender-authenticated — the dispute opener and evidence submitter must be the
  LXMF sender, and a resolution is only accepted from `ARBITRATOR_PEER_ID`
  with a signature that verifies against `ARBITRATOR_PUBKEY`. A stranger
  cannot open disputes on someone else's escrow, inject evidence, or mark a
  dispute resolved.
- **Peer identity binding (F1, 2026-09-12):** the RNS peerId is only a
  self-asserted announce displayName, so a new `neop2p.identity` announce has
  its appData signed by the libp2p Ed25519 key over
  `neop2p-binding-v1|<peerId>|<rnsIdentityHash>|<identityDestHash>`.
  `PeerBindingRegistry` binds peerId ↔ RNS **identity hash** (NOT the
  destination hash — an identity can own several destinations). Arbitration
  messages (dispute/evidence/resolution) are dropped unless the sender's
  delivery dest maps to the peerId AND its identity hash matches the verified
  binding. Send-side pins to the verified destination and **fails closed** for
  an unverified arbitrator. An unverified announce can never rebind a verified
  peerId.
- **Attested destinations (F2, 2026-09-12):** destinations are bound to the
  escrow role keys by `neop2p-attest-v1|<kind>|<scopeId>|<address>` ECDSA
  attestations — `SELLER_REFUND` signed by the seller escrow key at
  `createEscrow` (scope=escrowId), `BUYER_PAYOUT` signed by the buyer escrow
  key at accept (scope=offerId). `ResolutionGuard` verifies the unsigned tx's
  outputs at **both ends**: the arbitrator before signing, and every party
  before broadcasting. A tx sending the buyer's sats elsewhere is refused even
  if the arbitrator signed it. Legacy/pre-v27 disputes cannot be arbitrated.
- **Escrow script attestation (F3, 2026-09-12):** `EscrowScriptGate` verifies a
  received redeem script is 2-of-3, contains the official arbitrator key, and
  hashes to the advertised funding address. `markPaid` is blocked if the check
  fails and the escrow screen shows a warning banner — a tampered build that
  swaps in a self-controlled tie-break key is caught before any fiat moves.
- **Inbound hardening (F4, 2026-09-12):** the rate limiter is keyed by the
  sender destination (unclaimable), idle buckets are evicted in the sweep,
  evidence is persisted only for a known escrow/dispute, and new disputes are
  capped per sender (`DisputeIngestGate`).
- **Fail-closed upgrade (2026-09-12):** Room v27 added the F2/F3 attestation
  and dispute-role columns. A pre-v27 escrow has no attestations and can no
  longer be arbitrated; both parties must update to the same build before
  trading.
- **Pre-broadcast payout gate (F-3, 2026-09-13):** the stored `psbt_unsigned` is
  never trusted. `ReleaseIntegrity.verdict` re-derives the destination from the
  attested buyer address (anchored in the already-funded redeem script) and runs
  immediately before `ChainMonitor.broadcastTx` — every output must pay the
  buyer, the fee wallet, or the seller's refund address, with the buyer receiving
  ≥ the trade amount. On refusal the honest payout is rebuilt once
  (`releaseGateRecovery`); `EscrowRouter.shouldAdoptRemotePsbt` keeps the
  creator's payout tx sovereign and validates a mirror's remote psbt first.
- **Authenticated escrow_status (F-2, 2026-09-13):** `P2POrchestrator` drops an
  `escrow_status` whose sender lacks a verified identity binding; the router
  requires the sender to BE a party of the local row (`senderIsCounterparty`) and
  the creator's authoritative row refuses a remote `CANCELLED`
  (`applyRemoteStatus(..., localIsCreator)`), so no peer can strand a funded
  escrow by faking a terminal status.
- **Room v28 (2026-09-13):** added `escrows.disputed_at` (when a dispute opened)
  for the dispute age indicator — disputes have no deadline.
- **Evidence is delivered E2EE** (LXMF to the destination identity). Receipts
  are not secret by design — but parties should NOT include anything beyond
  the payment reference.

## Admin access (how to become the arbitrator)

1. The arbitrator restores their ADMIN mnemonic (a mnemonic whose
   `m/44'/999'/0'/1/0` key equals `ARBITRATOR_PUBKEY`).
2. Settings shows **Arbitrator Mode** → **Open Dispute Feed**.
3. Disputes + evidence stream in from LXMF automatically (no pairing,
   no backend). Set `NeoP2PConfig.ARBITRATOR_PEER_ID` to the arbitrator's
   peerId so parties can deliver disputes/evidence to the arbitrator directly.

## Current limitations (accepted)

- **FUNDING is not disputable (2026-09-05):** a dispute may only be opened once the escrow is FUNDED (deposit confirmed on-chain). FUNDING is either not yet broadcast (nothing to arbitrate — the 15-min funding window auto-cancels) or in flight (unconfirmed — the arbitrator's payout/refund would spend an output that does not exist yet and fail to broadcast). The buyer's exit from a stuck FUNDING escrow is the auto-cancel, not a dispute. Pure `EscrowService.canDisputeFromStatus` (mirrored by `P2POrchestrator`).
- **Payout-destination gate (2026-09-07):** a payout must never send the buyer's sats to the platform fee wallet or back into the escrow's own multisig. `PayoutAddressGate.isForbidden` rejects both at accept AND at build; `resolveBuyerPayoutAddress` resolves escrow row → offer row and never falls back to the multisig funding address (that fallback paid the buyer's sats back into the escrow). A re-published MATCHED claim carries the buyer payout address via `OfferFeedGate.lostClaimBuyerAddress` — blank and fee-wallet destinations are dropped.
- **Party-side broadcast after resolution:** the winning party's app applies the
  decision locally and **auto-broadcasts** the 2-of-3 (the arbitrator signature
  is stored on the escrow; the local key fills the buyer/seller role slots). No
  manual "broadcast now" step is needed.
- **Decision semantics:** `RELEASE_TO_BUYER` broadcasts the payout to the buyer;
  `REFUND_TO_SELLER` broadcasts the refund to the seller. The pre-fix names
  (`RELEASE_TO_SELLER`/`REFUND_TO_BUYER`) were inverted and are still accepted
  from already-published messages for backward compatibility.
- The remote-arbitrator path signs the tx embedded in the dispute message; if
  the message predates the psbt_hex field (old disputes), no resolution can be
  signed remotely.
- **Arbitrator delivery requires `ARBITRATOR_PEER_ID`** — blank (default)
  means disputes/evidence reach the arbitrator only if they are a party to
  the escrow (single-key model) or via the counterparty's relay of the message.
- **Resolution delivery requires the dispute event to carry the parties**
  (v23). Disputes opened by older builds (pre-v23, no `buyer_peer_id`/
  `seller_peer_id`) cannot be resolved remotely — the arbitrator has no
  delivery targets. Re-open the dispute with a v23 build to fix.
- **Pre-v27 disputes are not arbitrable (F2, 2026-09-12).** Without the
  role-signed destination attestations the arbitrator refuses to sign and the
  parties refuse to apply a resolution — fail closed by design. Both parties
  must run the same v27 build before opening an escrow.

## Deferred (Phase C)

Split admin APK (`:admin` build) — only after the transport is proven on
mainnet (v0.1.0-beta-1). The main app with the admin mnemonic is the arbitrator today.
