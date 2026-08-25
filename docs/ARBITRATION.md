# NEO-P2P Arbitration Protocol (Option 1)

How disputes travel between parties and the arbitrator over the Nostr relay —
zero backend, keys never leave devices.

## Kinds

| Kind | Name | Content | Producer |
|------|------|---------|----------|
| 33386 | Dispute opened | `{escrow_id, opened_by, reason, opened_at, redeem_script_hex, psbt_hex}` | party |
| 33387 | Evidence | `{escrow_id, submitter, description, mime_type, image_base64}` | party |
| 33388 | Resolution | `{escrow_id, decision, arbitrator_sig_hex, notes, decided_at}` | arbitrator |

All signed with the identity key (NIP-01), subscribed only on self-hosted
relays (`neop2p-arbitration`, limit 200), signature-verified on receipt.

## Flow

```
Party opens dispute ──kind:33386──▶ relay ──▶ both parties + arbitrator
   (disputeEscrow publishes; psbt_hex = unsigned payout/refund tx,
    redeem_script_hex lets a REMOTE arbitrator sign)

Party submits evidence ──kind:33387──▶ relay ──▶ arbitrator feed
   (image base64 + description; also stored locally in SQLCipher)

Arbitrator (admin identity) reviews feed:
   - signs psbt_hex with arbitrator key (m/44'/999'/0'/1/0)
   - EscrowService.arbitratorSignTx() sanity-verifies the sig
   - publishes kind:33388 with decision + signature

Winning party receives kind:33388:
   - storeArbitrationDecision() applies status RELEASED/REFUNDED (idempotent)
   - assembles the 2-of-3 scriptSig (arbitrator sig + the local key filling the
     buyer/seller role slots) and broadcasts the payout/refund via
     ChainMonitor.broadcastTx — funds move immediately, no manual broadcast step
   - RELEASE_TO_BUYER → payout tx sends tradeAmountSats to the BUYER + fee wallet
   - REFUND_TO_SELLER → refund tx returns the deposit to the SELLER
```

## Trust model

- **2-of-3 on-chain:** the payout/refund tx spends the P2SH multisig only with
  2 valid signatures. Arbitrator provides one; the winning party provides the
  other. The losing party's signature is irrelevant.
- **Arbitrator key:** derived at `m/44'/999'/0'/1/0` from the ADMIN's BIP-39
  mnemonic. `NeoP2PConfig.ARBITRATOR_PUBKEY` is the x-only pubkey of that
  path. The app unlocks Arbitrator Mode only when the active identity's
  derived pubkey matches it. The key never exists in an APK or on the relay.
- **Idempotency:** dispute/evidence/resolution events can be replayed by the
  relay; `storeArbitrationDecision` keeps the first decision and never
  downgrades a terminal status.
- **Evidence is public on the relay** (base64 in the event content). Receipts
  are not secret by design — but parties should NOT include anything beyond
  the payment reference.

## Admin access (how to become the arbitrator)

1. The arbitrator restores their ADMIN mnemonic (a mnemonic whose
   `m/44'/999'/0'/1/0` key equals `ARBITRATOR_PUBKEY`).
2. Settings shows **Arbitrator Mode** → **Open Dispute Feed**.
3. Disputes + evidence stream in from the relay automatically (no pairing,
   no backend).

## Current limitations (accepted)

- **Party-side broadcast after resolution:** the winning party's app applies the
  decision locally and **auto-broadcasts** the 2-of-3 (the arbitrator signature
  is stored on the escrow; the local key fills the buyer/seller role slots). No
  manual "broadcast now" step is needed.
- **Decision semantics:** `RELEASE_TO_BUYER` broadcasts the payout to the buyer;
  `REFUND_TO_SELLER` broadcasts the refund to the seller. The pre-fix names
  (`RELEASE_TO_SELLER`/`REFUND_TO_BUYER`) were inverted and are still accepted
  from already-published kind:33388 events for backward compatibility.
- The remote-arbitrator path signs the tx embedded in the dispute event; if
  the event predates the psbt_hex field (old disputes), no resolution can be
  signed remotely.
- Evidence published to the relay is readable by any relay subscriber.

## Deferred (Phase C)

Split admin APK (`:admin` build) — only after the transport is proven on
testnet4. The main app with the admin mnemonic is the arbitrator today.
