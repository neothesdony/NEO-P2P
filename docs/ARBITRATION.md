# NEO-P2P Arbitration Protocol (Option 1)

How disputes travel between parties and the arbitrator over LXMF (Phase 4 —
the Nostr relay was removed) — zero backend, keys never leave devices.

## Message types (LXMF DIRECT, title = type, FIELD_CUSTOM_DATA = JSON)

| Type | Name | Content | Producer |
|------|------|---------|----------|
| `dispute` | Dispute opened | `{escrow_id, opened_by, reason, opened_at, redeem_script_hex, psbt_hex, refund_tx_hex, deposit_sats, funding_script_type, seller_refund_address}` | party |
| `evidence` | Evidence | `{escrow_id, submitter, description, mime_type}` + image as LXMF file attachment | party |
| `resolution` | Resolution | `{escrow_id, decision, arbitrator_sig_hex, notes, decided_at, seller_refund_address, signed_tx_hex}` | arbitrator |

Delivered DIRECT to the counterparty (and to `NeoP2PConfig.ARBITRATOR_PEER_ID`
when set — blank = RNS arbitration delivery disabled). LXMF messages are
encrypted to the destination identity; the transport node cannot read them.

## Flow

```
Party opens dispute ──LXMF "dispute"──▶ counterparty + arbitrator
   (publish-then-commit: psbt_hex = payout, refund_tx_hex = pre-built refund,
    redeem_script_hex lets a REMOTE arbitrator sign either; delivery must
    succeed before local DISPUTED 2026-08-30; PendingDisputeStore retries
    every 60s via P2POrchestrator.sweepStaleEscrows)

Party submits evidence ──LXMF "evidence"──▶ arbitrator feed
   (image ≤60KB compressed + description; stored locally in SQLCipher AND
    delivered over LXMF; arbitrator persists the copy, survives reboot)

Arbitrator (admin identity) reviews feed:
   - signs psbt_hex with arbitrator key (m/44'/999'/0'/1/0)
   - EscrowService.arbitratorSignTx() sanity-verifies the sig
   - sends LXMF "resolution" with decision + signature

Winning party receives "resolution":
   - P2POrchestrator persists the seller's refund address (seller_refund_address
     → escrows.refund_destination) BEFORE applying the decision
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

## Deferred (Phase C)

Split admin APK (`:admin` build) — only after the transport is proven on
testnet4. The main app with the admin mnemonic is the arbitrator today.
