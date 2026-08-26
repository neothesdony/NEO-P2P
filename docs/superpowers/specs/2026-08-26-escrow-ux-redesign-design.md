# NEO-P2P Escrow UX Redesign — Guided Role-Adaptive Trade Flow

Date: 2026-08-26
Status: Approved (brainstorming)
Scope: On-chain escrow UX redesign only. Lightning Network explicitly deferred (future phase). No changes to crypto/signing/2-of-3/fee math.

## 1. Problem

The current escrow flow is clunky across three axes:

- **Fiat payment leg (core trust gap):** buyer pays bank/QRIS out-of-app, marks PAID, and the seller has no structured evidence to confirm against. The release gate currently hinges on a bare "paid" claim.
- **Funding leg:** even with one-tap auto-fund, the seller faces a manual txid paste + verify path as a first-class option, and the screen carries the whole state machine.
- **Structure:** EscrowService.kt is a 1,517-line god object and EscrowScreen.kt is 1,435 lines; the flow is scattered across EscrowScreen, ChatViewModel, OfferRouter.

## 2. Trust Model (design principle)

**Verification is the seller checking their own bank app. The app structures the evidence trail; it never fakes verification.**

- The seller taps "IDR received" — that is the release gate.
- The buyer's "I paid" claim does NOT transition the trade. It only unlocks the receipt composer.
- The payment receipt (reference code + amount + bank method + timestamp, optionally with an E2EE screenshot) is the evidence trail for disputes, not the release trigger.
- Reputation is post-trade only — it never gates runtime behavior.

## 3. State Machine

New statuses (replacing the current linear chain):

```
FUNDING → FUNDED → PAYMENT_PENDING → RECEIPT_SENT → CONFIRMING → RELEASED
                ↘ DISPUTED (any non-terminal)
                ↘ CANCELLED / REFUNDED (grace-based, never hard)
```

- FUNDING: escrow created, funding tx pending. (existing)
- FUNDED: funding confirmed on-chain (existing).
- PAYMENT_PENDING (new): escrow funded; buyer's turn. Buyer may mark paid → opens receipt composer.
- RECEIPT_SENT (new): buyer sent receipt card (+ optional screenshot). `receipt_sent_at`, `receipt_reference` persisted.
- CONFIRMING (replaces semantic role of PAID): seller reviewing receipt; seller confirms → RELEASED (broadcast payout), seller disputes → DISPUTED.
- DISPUTED / CANCELLED / REFUNDED: unchanged semantics, softened timing (below).

### Softened timeouts (no hard auto-actions)

| Window | Old | New |
|---|---|---|
| Funding timeout (unfunded) | 30 min | 45 min + warning at 30 |
| Funded-stalled refund | 6 h | 12 h + grace reminders at 24 h / 48 h |
| Payment window | 2 h | 24 h + countdown + 12 h grace before DISPUTED |

`expireStaleEscrows()` keeps its existing on-chain deposit check before any cancel/refund.

## 4. Guided Role-Adaptive Flow

One EscrowScreen rebuild with a step tracker (1 Fund → 2 Pay → 3 Confirm → 4 Release), steps shown/hidden by role:

- **Seller:** 1 Fund (auto-fund one-tap + broadcast confirm) → 3 Confirm (view receipt, tap "IDR received") → 4 Release (auto-broadcast after confirmation). Chat CTA for "Share payment details" moves into step 3 as a pre-receipt action.
- **Buyer:** 2 Pay (fiat instructions + bank details card, then "Send payment receipt") → wait for seller confirm → 4 Released (receives BTC address + rating prompt).

Auto-fund flow: accept → app auto-creates escrow + funds from wallet in one action; seller sees a single confirmation dialog with amount + fee breakdown, then one tap. Manual txid-paste remains as fallback only.

## 5. E2EE Receipt Screenshot (chat capability)

- New message payload type `payment_receipt` in the E2EE chat:
  - `receipt_text` JSON card: reference code, amount, bank method, timestamp.
  - `receipt_image` optional: compressed JPEG ≤ 640px, ≤ 60KB → base64, encrypted with the existing NIP-44-inspired E2EE (X25519 ECDH + HKDF-SHA256 + ChaCha20-Poly1305) before relay publish — NOT raw kind:33387 style.
- If relay event-size limit bites, chunk the payload (fallback only; evidence-kind images already travel via kind:33387 today, so size fits).
- On dispute, the receipt image is automatically copied into the evidence channel (kind:33387) — one press, no re-upload.

## 6. Reputation (post-trade only)

No runtime gating. After RELEASED both rate; scores feed:
- Offer ranking (higher-rep sellers rank higher on home list)
- Warning badges on low-rep peers in offer detail
- Dispute scoring (arbitrator sees both parties' history)

Reuse existing ReputationSystem.

## 7. Files Touched

| File | Change |
|---|---|
| `domain/model/Escrow.kt` + `EscrowEntity` | new statuses, `receipt_sent_at`, `receipt_reference`; DB v17→v18 migration |
| `data/escrow/EscrowService.kt` | status transitions, softened timeouts/grace, auto-fund orchestration |
| `ui/screens/escrow/EscrowScreen.kt` + VM | guided step-tracker rebuild |
| `ui/screens/chat/ChatScreen.kt` + `ChatRouter` | receipt card + E2EE image message type |
| `data/reputation/` | offer ranking + badge wiring (reuse existing system) |
| Tests | new: receipt flow, timeout grace, role-gated steps, image E2EE round-trip |

No crypto/sec changes — signing, 2-of-3, fee math untouched (already tested).

## 8. Out of Scope

- Lightning Network escrow (deferred to future phase)
- Bitcoin scripting/crypto changes
- Server/backend work (zero-backend unchanged)
- Full EscrowService decomposition (architecture, not this UX iteration)
