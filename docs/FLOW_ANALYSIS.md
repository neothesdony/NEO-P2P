# NEO-P2P — App Flow Analysis

**App:** NEO-P2P (`com.neop2p.app`) — zero-backend, peer-to-peer anonymous crypto trading for Indonesia
**Platform:** Android (Compose + Hilt + Room + SQLCipher, RNS/LXMF transport, testnet BTC)
**Version analyzed:** current `android/` source (Room DB v22, Phase 4 RNS-only)
**Target users:** Indonesian P2P BTC traders (buyers shop sell offers; sellers list BTC for IDR via bank/QRIS/e-wallet/cash)
**Core purpose:** match a BTC seller with an IDR buyer, secure the trade in a real on-chain 2-of-3 P2SH multisig escrow, and release funds only when the seller confirms fiat received — all without a backend.
**Business goal:** conversion (offer → match → funded escrow → released trade) + retention (re-engagement via notifications, reputation, saved payment methods).
**Key features to prioritize:** offer feed, offer accept (claim), escrow funding, guided pay/receipt flow, E2EE chat, wallet, arbitration, invite.
**Known constraints:** no backend (RNS transport node + LXMF propagation node only), testnet, notification-dependent market, identity locked behind device auth, offline peers (store-and-forward), 300-byte announce cap, per-destination announce rate limits.

Source of truth: `android/app/src/main/java/com/neop2p/` (NavGraph, MainActivity, screens, services, routers). No screenshots/analytics were provided; everything below is reconstructed from code. Items marked **ASSUMPTION** are inferred.

---

## 1. Executive summary

**What the app does.** A seller creates a sell offer (BTC amount, IDR price, fiat rails with bank/QRIS details, TTL). Buyers browse the RNS-fed offer list, open details, and accept — which atomically locks the offer (CAS claim) and starts a trade. The seller funds a real 2-of-3 P2SH multisig (crypto + 0.5% fee + network fee), the buyer pays IDR with a unique-code amount, sends a structured E2EE receipt (reference + screenshot), and the **seller's "IDR received" confirmation is the only release gate** that broadcasts the payout. Disputes escalate to a built-in arbitrator over LXMF. A personal BIP-44 wallet handles receive/send. Everything is local-first: SQLCipher Room DB, E2EE chat keyed from the BIP-39 seed, notifications via a foreground service.

**Primary user loop.** Browse feed → open offer → accept (enter BTC payout address) → chat (locked until escrow funded) → seller funds escrow → buyer pays IDR + sends receipt → seller confirms → payout broadcast → rate counterparty.

**The 3 most important flows**
1. **Seller: create offer → get matched → fund escrow** (conversion + money-in).
2. **Buyer: accept offer → pay IDR → send receipt** (conversion + money-in).
3. **Seller: confirm receipt → release** (the trust-critical release gate; the whole escrow design exists to make this safe).

**Biggest flow risks**
- **Chat is locked until escrow FUNDED** — the buyer cannot talk to the seller before funding; if the seller stalls in FUNDING, the buyer is blind for up to 45 min (then auto-cancel). High friction for first-time buyers.
- **The market is notification-dependent** — offers/matches/escrow events arrive via LXMF while the app is backgrounded; a denied notification permission or OEM battery-kill silently breaks the loop (mitigated by banners + OEM help screen, but the FGS can still be killed).
- **Two-sided async state machine** — every escrow step depends on the counterparty's device being online; the app has extensive heal machinery (60s sweep, resume-heal, pending stores) but a user who backgrounds mid-trade can miss windows (auto-cancel at 45 min, auto-dispute at 24h+12h).
- **Identity lock (P0-4)** — the seed is auth-gated with a 300s window; a locked phone pauses P2P silently (mitigated: notification + biometric re-arm + 60s transport retry).
- **Dead code paths** — `trade/{offerId}` route is registered but unreachable; `onTabChange` params on Wallet/Profile/History are never invoked; invite links (`neop2p://peer/…`) are not system deep links.

---

## 2. App map

Single-activity Compose app (`MainActivity` : `FragmentActivity`). All destinations are Compose destinations in one `NavHost` (`navigation/NavGraph.kt`). No Fragments, no WebView.

| Screen | Route | Type | Purpose | Entry | Exit | Persistent UI |
|---|---|---|---|---|---|---|
| Onboarding | `onboarding` | Full-screen wizard (7 steps) | Identity creation/restore + seed backup | Cold start when no identity or backup incomplete | `onOnboardingComplete` → HOME (popUpTo inclusive) | Step indicator dots; **no bottom bar** |
| Home (Market) | `home` | Root tab | Offer feed + portfolio header | Start destination; tab | Tab switch; push detail | Bottom bar, top bar (chat/escrow quick access + unread badge), FAB "Buat Tawaran" |
| Wallet | `wallet` | Root tab | BIP-44 balance/receive/send/history | Tab | Tab switch | Bottom bar, top bar |
| Trades | `trades` | Root tab (alias of HistoryScreen) | Escrow list, search, needs-action sections | Tab; notification deep link `trades` | Tab switch; push escrow | Bottom bar, top bar |
| History | `history` | Full-screen (legacy alias) | Same as Trades | Notification deep link (back-compat) | Back | Top bar only |
| Create Offer | `create_offer` | Full-screen form | Sell-only offer creation | Home FAB / empty state | Back / onOfferCreated pop | Top bar |
| Edit Offer | `edit_offer/{offerId}` | Full-screen (wraps CreateOfferScreen) | Edit own OPEN/PAUSED offer | Offer detail → Edit | Back / onEditSaved pop | Top bar |
| Offer Detail | `offer_detail/{offerId}` | Full-screen | Offer facts + role-based actions | Feed card tap; notification | Back; push chat/escrow/edit | Top bar |
| Chat | `chat/{offerId}/{peerId}` | Full-screen | E2EE trade chat | Offer detail / home quick access / notification | Back; push escrow | Top bar (fingerprint + conn chip) |
| Escrow | `escrow/{escrowId}` | Full-screen | Escrow lifecycle + role actions | Offer detail / chat / history / home / notification | Back / onComplete pop; push receipt/evidence | Top bar, sticky NextActionBar |
| Receipt Composer | `escrow/{escrowId}/receipt` | Full-screen | Buyer sends payment receipt (ref + screenshot) | Escrow screen (buyer) | Back / onSent pop | Top bar |
| Dispute Evidence | `dispute_evidence/{escrowId}` | Full-screen | Attach evidence to a dispute | Escrow screen (DISPUTED/RESOLVING) | Back | Top bar |
| Dispute Feed | `dispute_feed` | Full-screen | Arbitrator-only dispute queue + resolve | Settings (arbitrator identity only) | Back | Top bar |
| Profile | `profile` | Root tab | Identity, reputation, invite, settings entry | Tab | Tab switch; push settings/invite | Bottom bar, top bar |
| Settings | `settings` | Full-screen | Transport nodes, privacy, language, methods, blocks, seed, danger zone | Profile | Back; identity reset → ONBOARDING (popUpTo HOME inclusive) | Top bar |
| OEM Notifications | `settings/oem_notifications` | Full-screen | Per-brand background-kill fix guide | Home banner / Settings | Back | Top bar |
| Invite | `invite` | Full-screen (2 tabs) | Show QR / scan-paste peer link | Home empty state / Profile | Back | Top bar |
| Trade Room | `trade/{offerId}` | Full-screen (2 tabs) | Escrow+Chat hub | **Unreachable** (no nav call, not in deep-link whitelist) | Back | Top bar |

Dialogs (not destinations): accept-confirm (with BTC address field), taken-with-alternatives, decline-confirm, delete-confirm, report-reason picker, pause/resume, funding confirm, mark-paid confirm, dispute confirm, relay-gate confirm, refund dialog, reject-receipt dialog, rating dialog, seed reveal, reset identity, destroy data, restore warning, block peer.

---

## 3. Entry points

| Entry | First screen | Why |
|---|---|---|
| Cold start (launcher) | ONBOARDING if `!(hasIdentity && onboardingComplete)` else HOME | `OnboardingGate` — seed-backup completion is the only durable gate; a kill between generate and verify returns to onboarding |
| Warm start (recents) | Last destination (saved state) | Standard NavHost state restore |
| Launcher icon (already running) | Current destination | `launchSingleTop` semantics via launcher intent |
| Notification tap (cold) | Route carried in `Intent.EXTRA_TEXT` replayed via `onNavControllerReady` callback | `MainActivity.consumeNotificationIntent`; unknown routes ignored → HOME/ONBOARDING |
| Notification tap (warm) | Route via `onNewIntent` | Same consumer, `launchSingleTop` prevents duplicates |
| Deep link / App Link | **None registered** | No `intent-filter` for `neop2p://`; no `NavDeepLinkRequest`; invite links only work inside the Invite screen (paste/scan) |
| Share sheet | N/A | No share intent handling |
| Widget | N/A | No widgets |
| Recents | Restored stack | Compose Navigation saved state |
| After force-stop | ONBOARDING or HOME | Same as cold start; FGS restarts via START_STICKY |
| After permission change | Current destination; Home re-requests POST_NOTIFICATIONS on next composition | `LaunchedEffect(Unit)` in HomeScreen |
| After OS update | Current destination | No special handling |

**ASSUMPTION:** notification deep links to `offer_detail/…`, `chat/…`, `escrow/…` land on the destination even when the underlying row was deleted — the screens show their own error/empty states (offer not found, escrow pending→error after 2.5 min).

---

## 4. Information architecture

- **Model:** single Activity + single NavHost; 4 root destinations (bottom bar: Market / Wallet / Trades / Profile) + pushed detail flows. Onboarding is a separate full-screen flow with the bottom bar hidden.
- **Tab switching** (`switchTab`): `popUpTo(startDestination){saveState=true}`, `launchSingleTop`, `restoreState` — the standard Now-in-Android pattern; each tab keeps its own stack.
- **Back stack:** detail screens push on top of the current tab; Back pops to the tab. There is no Up affordance beyond the top-bar back arrow (all detail screens have one).
- **Modal vs hierarchical:** all detail flows are hierarchical pushes; dialogs are modal overlays. No drawer.
- **Root destinations:** `home`, `wallet`, `trades`, `profile` (bottom bar). `onboarding` is a root only when gating.
- **Highlight logic:** `AppTab.fromRoute` uses `startsWith`, so `trades` highlights Trades; detail routes keep the tab they were opened from (the bar does not re-highlight while a detail is on top — **ASSUMPTION**: acceptable, standard).
- **Notable quirks:**
  - `history` and `trades` are two routes rendering the same screen (back-compat alias).
  - `trade/{offerId}` (TradeRoom) is registered but **unreachable** — no caller, not in `isKnownRoute`.
  - `onTabChange` params on Wallet/Profile/History are declared but never invoked (dead params).
  - Identity reset navigates to ONBOARDING with `popUpTo(HOME){inclusive}` — the whole stack is cleared.

---

## 5. Primary user journeys

### J1. First launch / onboarding (identity creation)
- **Goal:** create a BIP-39 identity and prove the seed is backed up.
- **Trigger:** cold start, no identity or backup incomplete.
- **Preconditions:** none.
- **Happy path:** DISCLAIMER (scroll + accept) → WELCOME → CREATE_IDENTITY (nickname optional, "Buat Identitas") → BACKUP_SEED (seed shown, tap-to-copy with 60s clipboard auto-clear, 3 checkboxes gate "Saya Sudah Menyimpannya") → VERIFY_SEED (3 random words) → FINISH → HOME.
- **Alternate:** RESTORE from 12-word seed (from CREATE_IDENTITY link) → restore warning dialog (trades live on old device) → FINISH.
- **Failure:** wrong word count → inline error + `ERR_INVALID_SEED`; wrong words → mismatch error, back to BACKUP_SEED.
- **End state:** identity + seed persisted (SQLCipher, KeyStore auth-gated), `OnboardingStore.markComplete()`, HOME.
- **Success metric:** onboarding completion rate; seed-verify pass rate.
- **Risk:** 7 steps is long; the checklist gate + 3-word challenge are deliberate friction (funds safety) but drop users. **ASSUMPTION:** no analytics exist to measure this.

### J2. Seller: create offer
- **Goal:** list BTC for sale.
- **Trigger:** Home FAB "Buat Tawaran" or empty-state CTA.
- **Preconditions:** identity unlocked (else BiometricPrompt on submit).
- **Happy path:** amount (BTC) → price (IDR, prefilled with live market price) → live fee breakdown card (0.5% fee + network fee + total deposit) → select fiat methods + enter account/holder (QRIS string for qris) → optional saved-method chips prefill → TTL (6/12/24/48h) → "Buat Tawaran" → confirm dialog (full summary) → pop back to feed.
- **Alternate:** edit own offer (same form prefilled; MATCHED shows live-taker warning).
- **Failure:** invalid amount/price (non-whole price rejected by `parseIdrToLong`), incomplete method details → submit disabled; identity locked → biometric prompt then retry; network fee estimate failure → non-fatal (deposit falls back to crypto+fee).
- **End state:** offer in Room + digest registered for the paced re-announce loop (2.5s tick, one digest per tick).
- **Success metric:** offer created → matched within TTL.

### J3. Buyer: accept offer
- **Goal:** lock an offer and start the trade.
- **Trigger:** tap offer card → detail → "Terima Tawaran".
- **Preconditions:** offer OPEN, not expired, not own offer, not blocked peer.
- **Happy path:** accept dialog (SELL offer → enter BTC payout address, validated with bitcoinj) → CAS `claimOffer` → MATCHED → LXMF `offer_status` to creator → route: if accepter is seller (BUY offer) → escrow created → navigate to escrow; else → chat.
- **Alternate:** lost the two-taker race → "sudah diambil" dialog with up to 3 alternative offers (same rail preferred) → pivot in place or back to market.
- **Failure:** expired → accept disabled ("Kedaluwarsa"); locked → card shows lock, tap does nothing (unless creator/matched/arbitrator).
- **End state:** offer MATCHED/ESCROWED on both devices; buyer in chat (locked until funded).
- **Success metric:** accept → escrow FUNDED.

### J4. Seller: fund escrow (money-in)
- **Goal:** deposit crypto + 0.5% fee + network fee into the 2-of-3 P2SH.
- **Trigger:** own MATCHED SELL offer → "Buat Escrow" → escrow screen (FUNDING).
- **Happy path:** choose P2SH/P2WSH (switchable while unfunded) → "Kirim dari Dompet Saya ke Escrow" (one-tap, confirm dialog, irreversible) → wallet sends exact `depositAmountSats` → txid auto-filled → on-chain verify (Mempool, ≥1 conf) → FUNDED → chat unlocks, bank details auto-share to buyer.
- **Alternate:** manual: copy address → external wallet → paste txid → "Verifikasi Pendanaan"; or cancel/refund (only while txid blank).
- **Failure:** insufficient balance / dust / wrong network → inline error + error code; verification fails → stays FUNDING; 45-min timeout → auto-CANCEL (with on-chain deposit check first — a funded-but-unverified escrow is promoted to FUNDED, never orphaned).
- **End state:** FUNDED; buyer notified; 12h+48h refund window starts.
- **Success metric:** FUNDING → FUNDED within 45 min.

### J5. Buyer: pay IDR + send receipt (money-in)
- **Goal:** pay the seller and prove it.
- **Trigger:** escrow FUNDED → buyer sees pay instruction card (exact IDR + unique code suffix, copyable) + bank details card.
- **Happy path:** transfer in bank app (external handoff) → back → "Saya Sudah Bayar" (confirm) → PAYMENT_PENDING → "Kirim Bukti" → ReceiptComposer (reference code prefilled, regenerate allowed, attach screenshot ≤1600px/≤60KB, draft auto-saved) → send → RECEIPT_SENT (escrow transition is source of truth; E2EE chat copy best-effort).
- **Alternate:** seller rejects receipt ("Tolak Bukti", 4 reason codes + note, advisory only) → buyer sees reject card with funds-locked line → fix + resubmit or dispute.
- **Failure:** no screenshot → send refused; payment window 24h+12h → auto-DISPUTED (never silently refunded).
- **End state:** RECEIPT_SENT; seller's confirm gate armed.
- **Success metric:** FUNDED → RECEIPT_SENT within 24h.

### J6. Seller: confirm receipt → release (trust-critical)
- **Goal:** broadcast the payout (buyer gets full BTC, 0.5% to fee wallet).
- **Trigger:** RECEIPT_SENT → seller sees receipt card (reference) → "Konfirmasi IDR Diterima".
- **Happy path:** confirm (relay-gate confirm if not DIRECT) → SIGNED (transient) → CONFIRMING → payout broadcast → RELEASED → rating dialog (once, persistent gate).
- **Alternate:** dispute instead of release (escape hatch at every stage); cancel/refund (CONFIRMING).
- **Failure:** broadcast failure → error surfaced, retry from SIGNED; kill in SIGNED→CONFIRMING window → resume-heal re-publishes on next open.
- **End state:** RELEASED; offer terminal; both devices converge (forward-only router).
- **Success metric:** RECEIPT_SENT → RELEASED; dispute rate.

### J7. Dispute → arbitration
- **Goal:** freeze funds and get a binding ruling.
- **Trigger:** either party taps "Buka Sengketa" (available from any non-terminal state).
- **Happy path:** confirm → publish-then-commit (LXMF dispute to counterparty + arbitrator; auto-build payout if missing; refund tx included) → DISPUTED (frozen banner) → both parties attach evidence (image + description, ack-gated) → arbitrator (Settings → Dispute Feed, identity-gated) reviews → resolve (Release/Refund, signs with arbitrator key, LXMF resolution to both parties) → winning party broadcasts 2-of-3 → RELEASED/REFUNDED.
- **Failure:** delivery fails → `PendingDisputeStore` + 60s sweep retry; resolution delivery fails → `PendingArbitrationStore` retry.
- **End state:** terminal; parties apply resolution idempotently.
- **Success metric:** dispute → resolution time; both parties converge.

### J8. Wallet
- **Goal:** receive/send BTC.
- **Trigger:** Wallet tab.
- **Happy path:** balance card (unconfirmed + locked-in-escrow lines) → receive (Legacy/SegWit toggle, QR, copy) → send (address + amount with inline validation: wrong-network, invalid, dust, insufficient) → send-from selector (auto/legacy/segwit) → fee estimate → confirm dialog (fee + total) → broadcast → history refresh.
- **Failure:** broadcast error → snackbar with error code; fee estimate failure → non-fatal.
- **End state:** tx in history; WalletWatcher (60s poll) notifies on incoming.

### J9. Re-engagement after inactivity
- **Trigger:** notification (chat redacted "Pesan baru", offer matched, escrow transition, wallet receive, identity locked) or cold start.
- **Path:** tap → deep link to chat/escrow/offer/wallet; FGS keeps transport alive (START_STICKY, specialUse).
- **Risk:** OEM battery killers; mitigated by OEM help screen + banners, but **ASSUMPTION:** no push fallback exists (zero-backend by design).

---

## 6. Screen-by-screen flow notes

### Home (Market)
- **First:** skeleton shimmer (5 cards) while DB loads; then portfolio header (if open trades/locked/unread), notif-denied banner (dismissable/session), offline banner (relay not connected), filter chips + min/max IDR fields + sort.
- **Primary CTA:** FAB "Buat Tawaran" (200dp wide, bottom-right).
- **Secondary:** pull-to-refresh (re-announces own digests + re-fetches missed), filter chips, top-bar chat/escrow quick access (badged), empty-state "Buat Tawaran" + "Ajak Teman".
- **Data:** Room offers+peers (SQLCipher) combined flow; ranked by reputation; blocked peers filtered; PAUSED hidden except own; terminal offers hidden.
- **Success:** feed renders; **Error:** full-screen error + retry; **Back:** exits app (root).
- **Background mid-task:** N/A (read-only); transport keeps running.

### Offer Detail
- **First:** loading spinner → offer card (amount/price/fee/total deposit), payment methods, trader card (nickname, reputation % + tier badge, low-rep/trusted chips).
- **Primary CTA (role-dependent):** own → Edit / Pause / Delete (delete gated OPEN/PAUSED with reason text); own MATCHED SELL → "Buat Escrow" + "Tolak" (decline); locked → "Chat dengan Pedagang"; open → "Terima Tawaran" (disabled when expired) + "Lapor".
- **Accept dialog:** SELL → BTC payout address field (bitcoinj validation, wrong-network aware).
- **Taken dialog:** 3 alt offers carousel; "Kembali ke Market" pops.
- **Success:** navigate to escrow (if seller) or chat; **Error:** retry; **Back:** pop.
- **Data:** Room offer + peer + local reputation; identity lock degrades to not-own (screen still renders).

### Escrow (the heart)
- **First:** header (counterparty label, escrow id copy, 8-word fingerprint copy, connection-quality chip, status chip) → dispute frozen banner (if DISPUTED) → role-adaptive step tracker (Seller: Fund→Confirm→Release; Buyer: Pay→Release) → trade details (fee rows seller-only) → bank details card (both roles) → pay instruction card (buyer, unique code) → funding section (seller) → action matrix → sticky NextActionBar (one primary message per role+state + countdown).
- **Funding (seller):** P2SH/P2WSH switch, "Kirim dari Dompet Saya" (confirm dialog), txid field, mempool "in progress" card with explorer link, "Verifikasi Pendanaan", 45-min countdown, cancel/refund (disabled once txid set).
- **Action matrix:** FUNDED → buyer "Saya Sudah Bayar" / seller cancel-refund; PAYMENT_PENDING → buyer "Kirim Bukti" / dispute; RECEIPT_SENT → seller "Konfirmasi IDR Diterima" + "Tolak Bukti" + dispute; CONFIRMING → seller confirm + dispute + cancel-refund; DISPUTED → "Kirim Bukti" (evidence); RELEASED/REFUNDED/CANCELLED → completion card.
- **Gates:** money actions require DIRECT link else relay-gate confirm dialog; role gates by peerId; status gates in service.
- **Success:** state flips live (transitions flow + offer-row observer); **Error:** full-screen error + retry; **Pending:** buyer pre-sync waits up to 2.5 min polling 5s.
- **Back:** pop; **onComplete:** pop (after release).
- **Background mid-task:** resume-heal re-publishes status on next open; sweep auto-cancels/refunds/disputes per timeouts.

### Chat
- **First:** session banner (CONNECTING/OFFLINE/ERROR), escrow status banner (buyer, with "Buka" button), then messages.
- **Locked until escrow FUNDED** — input replaced by lock card; seller sees "wait for funded" hint; after FUNDED: seller "Bagikan Detail Pembayaran" button (auto-share fallback on open), buyer sees payment-details card.
- **Input:** text + file attach (OpenDocument, any type); E2EE via X25519+ChaCha20-Poly1305; offline → queued (OfflineQueue).
- **Top bar:** 8-word fingerprint (copyable), connection chip.
- **Success:** message persists (Room, encrypted); **Error:** send error snackbar; **Back:** pop.
- **Background:** notification suppressed only for the open conversation; redacted body on lock screen.

### Receipt Composer
- **First:** draft-restored banner (if a draft exists — reference + screenshot survive process death), reference code card (regenerate), amount/method prefilled, image attach (GetContent, compressed ≤1600px/≤60KB), send.
- **Success:** RECEIPT_SENT, draft cleared, pop; **Error:** inline; **Back:** draft kept.

### Wallet
- Balance card (unconfirmed, locked-in-escrow with hint) → receive (toggle, QR, copy) → send (inline validation + fee estimate + confirm dialog) → history (direction-colored, net amounts).
- **Success:** snackbar with txid tail + refresh; **Error:** snackbar + error code; **Back:** pop (tab root).

### History / Trades
- Search field (escrowId / offerId / receipt reference), sections: Perlu Tindakan / Menunggu / Selesai; empty states distinct (no trades vs no search results).

### Profile
- Avatar (initial), nickname (edit dialog), peerId + pubkey copy, reputation stats (score/trades/completed/disputes), attestations dialog, Invite, Settings.

### Settings
- Transport nodes (add/remove), privacy (Tor disabled "coming soon", auto-connect), OEM notifications help, language (system/id/en, restart hint), saved payment methods (add/remove), blocked peers (unblock), reported peers (remove), show seed (auth-gated, no-auth fallback dialog), danger zone (reset identity → onboarding; destroy data → type "HAPUS"), fee wallet info, arbitrator feed (identity-gated).

### Invite
- Tab 0: my `neop2p://peer/<id>` QR + copy; Tab 1: scan (camera permission flow with rationale + settings deep link) or paste; result states: validating/success (already-known vs new)/error (`ERR_INVALID_QR`).

---

## 7. State machine

### Identity / onboarding
```
no identity ──onboarding──▶ identity + backup complete ──▶ HOME
   ▲                                                          │
   └── reset identity (Settings) ◀────────────────────────────┘
identity exists but backup incomplete ──▶ ONBOARDING (gate)
identity locked (auth window expired) ──▶ P2P paused; BiometricPrompt re-arm → retry
```

### Offer
```
OPEN ──accept (CAS)──▶ MATCHED ──escrow created──▶ ESCROWED ──release/refund──▶ COMPLETED
  │  ▲                    │
  │  └──decline (creator)─┘
  ├──pause (creator)──▶ PAUSED ──reactivate──▶ OPEN
  └──delete (creator, OPEN/PAUSED)──▶ CANCELLED (tombstoned)
expired (TTL) ──▶ visible but unclaimable
```
Remote transitions are claim-gated (`OfferClaimGate`): only the creator may unlock; raw re-announcements never downgrade.

### Escrow (forward-only router; terminal states locked)
```
FUNDING ──funded+verified──▶ FUNDED ──(SIGNED transient)──▶ PAYMENT_PENDING ──receipt──▶ RECEIPT_SENT ──confirm──▶ CONFIRMING ──broadcast──▶ RELEASED
   │                            │                              │
   ├─45min timeout─▶ CANCELLED  ├─12h+48h─▶ REFUNDED          └─24h+12h─▶ DISPUTED
   └─(deposit found on-chain)─▶ FUNDED (promote, never orphan)
DISPUTED ──arbitrator resolution──▶ RELEASED | REFUNDED
```
- Remote events: only strictly-forward happy-path moves; DISPUTED opens from any non-terminal state; only arbitration outcomes close DISPUTED; terminal (RELEASED/REFUNDED/CANCELLED) never changes.
- **Session/permission states:** logged-in is implicit (identity always exists post-onboarding); no session expiry; permission states: notifications granted/denied (banner), camera granted/denied (rationale card), identity auth armed/expired.

---

## 8. System & Android-specific flow

- **Runtime permissions:** only two requested, both in-context: `POST_NOTIFICATIONS` on Home first composition (Android 13+; denial → dismissable banner + OEM help deep link); `CAMERA` in Invite scan (denial → rationale card + app-settings deep link). Media picking uses `GetContent`/`OpenDocument` (no storage permission). No location.
- **Notification permission (13+):** requested immediately at Home — **premature-ish** (before any user value), but the market genuinely needs it; banner + OEM guide are good recovery.
- **Battery/background:** FGS `specialUse` (not dataSync — Android 15 6h cap), START_STICKY, "Connected to network" low-importance notification; OEM kill risk mitigated by help screen; no exact alarms; timeouts are computed in the 60s sweep (no AlarmManager).
- **Configuration changes:** Compose handles rotation; per-app locale applied in `attachBaseContext` (id/en/system) with restart hint; no special split-screen handling (standard resize).
- **Process death:** Room/SQLCipher persistence; receipt drafts in SharedPreferences (write-through); pending disputes/arbitration in stores; notified-events dedup prefs; resume-heal re-publishes escrow status on load; identity lock re-arms via biometric on Home/CreateOffer; transport self-heal every 60s.
- **Deep links:** notification-only, via `Intent.EXTRA_TEXT` route string + `isKnownRoute` whitelist + `launchSingleTop`; **no** `intent-filter` deep links, **no** `NavDeepLinkRequest` (deliberate — documented in MainActivity). `neop2p://peer/` links are handled only inside Invite (paste/scan), not by the system.
- **External handoffs:** bank app (manual, user-driven), mempool.space explorer (ACTION_VIEW), app-settings (ACTION_APPLICATION_DETAILS_SETTINGS), file pickers, zxing scanner, clipboard.
- **Predictive back/gestures:** default; all detail screens have explicit back arrows; no predictive-back animations configured (**ASSUMPTION**: default behavior).
- **Android 16 Live Updates:** escrow funding states render as `Notification.ProgressStyle` (API 36+), promoted-ongoing not wired (documented limitation).

---

## 9. Data flow

**Accept offer:**
```
Tap "Terima" → dialog (BTC address) → OfferDetailViewModel.acceptOffer
→ offerDao.claimOffer (CAS, Room) → rnsTransport.sendOfferStatus (LXMF, MATCHED + matchedPeerId + buyerBtcAddress)
→ (if accepter is seller) escrowService.createEscrow → Room upsert + publishEscrowSync (LXMF) + transitions.emit
→ onAccepted(Proceed(escrowId)) → navigate escrow | chat
```
**Fund escrow:**
```
"Kirim dari Dompet Saya" → confirm → EscrowViewModel.fundFromWallet
→ walletService.send (UTXO selection, broadcast) → txid → escrowService.onEscrowFunded (Mempool verify ≥1 conf)
→ Room FUNDED + publishEscrowSync → transitions → notification (backgrounded) / snackbar (foreground) → chat unlocks via escrowDao observer
```
**Receipt:**
```
ReceiptComposer.send → escrowService.sendReceipt (Room RECEIPT_SENT + publishEscrowSync) [source of truth]
→ chatRouter.sendReceiptMessage (E2EE, best-effort) → draft cleared
```
**Release:**
```
confirmReceipt → escrowService.confirmReceipt (role gate) → generatePayoutTransaction (SIGNED transient)
→ broadcast (ChainMonitor, Mempool) → CONFIRMING → RELEASED → offer terminal + offer_status sync
```
**Feed:**
```
RNS announce (digest ~200B) → deferral buffer (≤32/identity) → flush on lxmf.delivery announce
→ offer_request (LXMF) → offer JSON → OfferRouter.ingestRnsOffer → Room → Home combine flow → UI
```
**Cached vs live:** everything is Room-first (SQLCipher); network (Mempool fee/balance/tx) is live with 10s/20s timeouts and Blockstream fallback; failures degrade to cached data + error surfaces; money math is integer-only.

---

## 10. Edge cases and breakage points

| Case | Current behavior | Better flow |
|---|---|---|
| Notification permission denied | Banner + OEM guide; market still works while app open | Consider a one-time rationale dialog before the system prompt |
| No internet / relay down | Offline banner; feed stale; chat shows OFFLINE + queue; money actions relay-gated | Already good; add retry affordance on the banner |
| Invalid form (offer) | Submit disabled with no per-field message (only summary) | Add inline field errors |
| Identity locked | Biometric prompt on Home/CreateOffer; P2P paused + notification | Already good; consider prompting on any money action |
| Lost accept race | "Sudah diambil" + 3 alt offers | Excellent recovery — keep |
| Deep link to deleted offer/escrow | Offer: "not found" error; escrow: 2.5-min pending then error | Add "open market" CTA on those errors |
| First-time vs returning mismatch | Onboarding gate handles it; restore warning dialog | Good |
| Back stack loops | Tab switch pops to start; detail Back pops once | Fine; TradeRoom dead route should be removed |
| Duplicate submission | Busy flags on all money actions; CAS claim; idempotent re-sends | Good |
| Payment failure (fiat) | Seller rejects receipt (advisory) or dispute | Good; consider a resubmit shortcut for the buyer |
| Escrow row not yet synced (buyer) | Pending screen, 2.5-min poll | Good; add a "notify seller" hint |
| OEM battery kill | FGS dies silently; identity-locked notification only covers one case | Consider a "connection lost" notification on sweep failure |
| Camera denied permanently | Rationale card + settings deep link | Good |
| Chat before funding | Locked input with explanation | Good, but consider allowing a "why" tooltip |

---

## 11. UX / product critique (ranked)

**Critical**
1. **Chat locked until escrow FUNDED** — the buyer's only channel to the seller is dead during the riskiest window (before their BTC is secured). A buyer who accepted and sees a locked chat may abandon. *Mitigation exists (escrow screen shows status), but the chat lock is a hard wall.*
2. **Notification-dependence with no fallback** — if notifications are off or the FGS is killed, matches/escrow events are missed until the app is opened. The app is honest about it (banners), but the core loop silently degrades.

**High**
3. **Onboarding is 7 steps with a 3-word verification** — necessary for self-custody safety, but the DISCLAIMER + WELCOME steps are pure friction before any value. Consider merging.
4. **Escrow screen density** — one screen carries status chip, fingerprint, connection chip, step tracker, trade details, bank card, pay card, funding section, action matrix, sticky bar, countdowns. Role-adaptive, but a first-time buyer will be overwhelmed. Consider progressive disclosure.
5. **No system deep links for invite links** — `neop2p://peer/…` must be pasted/scanned inside the app; a tapped link in WhatsApp does nothing. This is the primary growth loop and it's broken at the OS level.
6. **Dead code paths** (`trade/{offerId}`, `onTabChange` params) — not user-facing today, but signal unfinished navigation; the TradeRoom concept (escrow+chat hub) is actually the right mental model and should either be wired or removed.

**Medium**
7. **POST_NOTIFICATIONS requested on first Home composition** — before the user has seen value; Android 13+ users may deny reflexively.
8. **Offer form has no inline validation messages** — the submit button is just disabled; users must guess why.
9. **Taken-dialog alt carousel loads in-place** (same route) — the back stack still points at the old offer; Back after pivoting returns to the taken offer.
10. **History search is local-only and exact-substring** — fine, but no filter by status.

**Low**
11. `onTabChange` dead params; `history`/`trades` route duplication; TradeRoom unreachable; rating dialog is local-only (reputation never gossips — by design, but the "rate" affordance implies more than it does).

---

## 12. Recommended improved flows

### R1. Buyer accept → pay journey (J3+J5)
- **Before:** accept → chat (locked) → wait for seller to fund → escrow screen → pay card → mark paid → receipt composer (2 more screens).
- **After:** accept → **trade hub** (the existing TradeRoom concept, wired): one screen with Escrow + Chat tabs, auto-selected to the actionable tab; the pay instruction card + receipt CTA surface inline in the hub; chat unlocks with a "why locked" tooltip.
- **Why better:** fewer hops, one mental model per trade, the buyer always sees "what's next" (the NextActionBar pattern extended to the hub).
- **Keep:** the guided step tracker, unique-code pay card, draft-proof receipt composer, taken-dialog recovery.
- **Implementation:** wire `trade/{offerId}` into `isKnownRoute` + all escrow/chat navigation; make TradeRoom the post-accept destination; move NextActionBar into it; delete the now-redundant `history` alias or keep as deep-link compat.

### R2. Invite as a system deep link
- **Before:** `neop2p://peer/<id>` only works pasted into the Invite screen.
- **After:** register an intent-filter for `neop2p://peer/<id>` on MainActivity; on cold start, parse the peerId, record the peer, and land on Home with a "peer added" snackbar; on warm start, same via `onNewIntent`.
- **Why better:** the primary growth loop (WhatsApp/QR → install → first peer) works from any tap; matches the existing notification deep-link machinery.
- **Keep:** the in-app QR/paste flow as fallback.
- **Implementation:** add `<intent-filter>` with `neop2p` scheme; reuse `consumeNotificationIntent`-style routing with a new `isKnownRoute` entry; guard against self-links.

### R3. Notification permission with rationale
- **Before:** system prompt fires on first Home composition.
- **After:** a one-time in-app rationale sheet ("NEO-P2P needs notifications to tell you when your offer is matched / escrow is funded — no ads, no tracking") with "Allow" / "Not now"; only then the system prompt; "Not now" shows the existing banner.
- **Why better:** converts reflexive denials; the banner + OEM guide remain the recovery path.
- **Keep:** the banner, OEM help screen, per-channel muting.
- **Implementation:** gate the `RequestPermission` launcher behind a `remember`-ed rationale state (or a small prefs flag); no new permissions.

---

## 13. QA test cases from the flow

1. **First-run onboarding:** Given a fresh install, When the user completes DISCLAIMER→WELCOME→CREATE_IDENTITY→BACKUP (3 checkboxes)→VERIFY (3 words), Then HOME shows and the feed loads; a kill between generate and verify returns to ONBOARDING.
2. **Restore:** Given a 12-word seed, When pasted into RESTORE, Then the restore-warning dialog appears and FINISH lands on HOME; a 11-word seed shows `ERR_INVALID_SEED`.
3. **Create offer validation:** Given the create form, When amount/price/method-details are incomplete, Then submit stays disabled; a decimal price shows no error but is rejected at parse (ASSUMPTION: verify `parseIdrToLong`).
4. **Accept race:** Given two devices on the same OPEN offer, When both tap accept, Then exactly one proceeds to chat/escrow and the other sees the "sudah diambil" dialog with alternatives.
5. **Accept with BTC address:** Given a SELL offer, When accepting with an invalid address, Then the confirm button stays disabled; a wrong-network address shows the specific error.
6. **Back navigation:** Given Home→OfferDetail→Chat, When Back is pressed twice, Then Home is restored with the feed state intact; tab switching preserves each tab's stack.
7. **Process death mid-receipt:** Given the buyer composing a receipt with a screenshot, When the app is killed and reopened, Then the draft (reference + image) is restored with the draft banner.
8. **Process death in SIGNED→CONFIRMING:** Given the seller confirmed receipt, When the app dies between persist and broadcast, Then on reopen the escrow re-publishes and the release can be retried (resume-heal).
9. **Offline:** Given no connectivity, When the user opens Home, Then the offline banner shows and the cached feed renders; chat shows OFFLINE and messages queue.
10. **Permission deny:** Given POST_NOTIFICATIONS denied, When Home loads, Then the banner appears and "Perbaiki" opens the OEM guide; the FGS still runs.
11. **Camera deny:** Given CAMERA denied in Invite, When scanning, Then the rationale card appears with a settings deep link.
12. **Deep link to deleted content:** Given a notification for a deleted offer, When tapped, Then the offer-detail error state shows with retry (and ideally a market CTA).
13. **Identity lock:** Given the auth window expired, When the app resumes, Then a BiometricPrompt appears and the transport restarts on success; the sweep retries every 60s.
14. **Funding timeout:** Given an unfunded escrow past 45 min, When the sweep runs, Then it auto-cancels; if a deposit exists on-chain, it promotes to FUNDED instead.
15. **Payment window:** Given PAYMENT_PENDING past 24h+12h, When the sweep runs, Then the escrow auto-DISPUTED (never silently refunded).
16. **Configuration change:** Given the escrow screen mid-funding, When rotated, Then the txid field and state persist (ViewModel survives).
17. **Locale switch:** Given Settings→language=id, When the app restarts, Then all strings render in Indonesian.
18. **Arbitrator gate:** Given a non-arbitrator identity, When opening Dispute Feed, Then access is denied; the arbitrator identity sees the persisted queue.

---

## 14. Deliverables

### A. User flow (text diagram)

```
COLD START
  ├─ no identity / backup incomplete ─▶ ONBOARDING (7 steps) ─▶ HOME
  └─ identity + backup ─▶ HOME
HOME (Market)
  ├─ FAB / empty CTA ─▶ CREATE OFFER ─▶ (confirm) ─▶ back to feed
  ├─ offer card ─▶ OFFER DETAIL
  │     ├─ own: EDIT ─▶ EDIT OFFER ─▶ back
  │     │        PAUSE / DELETE / (MATCHED) CREATE ESCROW ─▶ ESCROW
  │     ├─ open: ACCEPT (BTC addr) ─▶ [race lost] TAKEN + alts
  │     │        └─ [won] ─▶ CHAT (locked) / ESCROW (if seller)
  │     └─ locked: CHAT
  ├─ top-bar chat/escrow icons ─▶ CHAT / ESCROW
  └─ tab: WALLET / TRADES / PROFILE
CHAT (unlocks at FUNDED)
  ├─ seller: SHARE PAYMENT DETAILS ─▶ E2EE card
  └─ escrow banner ─▶ ESCROW
ESCROW
  ├─ seller FUNDING: fund-from-wallet / txid+verify ─▶ FUNDED
  ├─ buyer FUNDED: pay (external bank) ─▶ MARK PAID ─▶ RECEIPT COMPOSER ─▶ RECEIPT_SENT
  ├─ seller RECEIPT_SENT: CONFIRM ─▶ RELEASED ─▶ RATING
  │        └─ REJECT (advisory) / DISPUTE
  └─ DISPUTED ─▶ EVIDENCE ─▶ [arbitrator] DISPUTE FEED ─▶ RESOLVE ─▶ RELEASED/REFUNDED
WALLET: receive (QR) / send (validate → fee → confirm → broadcast)
PROFILE ─▶ SETTINGS (nodes, language, methods, blocks, seed, reset/destroy) / INVITE (QR / scan / paste)
NOTIFICATION tap ─▶ deep link: chat / escrow / offer_detail / wallet / trades
```

### B. Screen inventory table

| # | Screen | Route | Type | Entry | Exit | Bottom bar |
|---|---|---|---|---|---|---|
| 1 | Onboarding | `onboarding` | Wizard | cold start gate | → HOME | no |
| 2 | Home | `home` | Root tab | start / tab | tabs / push | yes |
| 3 | Wallet | `wallet` | Root tab | tab / notif | tabs | yes |
| 4 | Trades | `trades` | Root tab | tab / notif | tabs / push escrow | yes |
| 5 | History | `history` | Full | notif (compat) | back | no |
| 6 | Create Offer | `create_offer` | Full | FAB / empty | back / created | no |
| 7 | Edit Offer | `edit_offer/{id}` | Full | detail→Edit | back / saved | no |
| 8 | Offer Detail | `offer_detail/{id}` | Full | card / notif | back / push | no |
| 9 | Chat | `chat/{oid}/{pid}` | Full | detail / home / notif | back / push escrow | no |
| 10 | Escrow | `escrow/{id}` | Full | many / notif | back / complete | no |
| 11 | Receipt | `escrow/{id}/receipt` | Full | escrow (buyer) | back / sent | no |
| 12 | Evidence | `dispute_evidence/{id}` | Full | escrow (disputed) | back | no |
| 13 | Dispute Feed | `dispute_feed` | Full | settings (arb) | back | no |
| 14 | Profile | `profile` | Root tab | tab | tabs / push | yes |
| 15 | Settings | `settings` | Full | profile | back / reset→onboarding | no |
| 16 | OEM Help | `settings/oem_notifications` | Full | home banner / settings | back | no |
| 17 | Invite | `invite` | Full | home empty / profile | back | no |
| 18 | Trade Room | `trade/{id}` | Full | **unreachable** | back | no |

### C. Journey table

| Journey | Trigger | Steps | End state | Success metric |
|---|---|---|---|---|
| Onboard | cold start | 7-step wizard | HOME | completion % |
| Create offer | FAB | form → confirm → re-announce | offer in feed | created→matched |
| Accept | detail CTA | dialog → CAS → chat/escrow | MATCHED/ESCROWED | accept→funded |
| Fund escrow | own MATCHED | create → fund → verify | FUNDED | FUNDING→FUNDED <45min |
| Pay + receipt | FUNDED (buyer) | pay → mark paid → composer | RECEIPT_SENT | FUNDED→RECEIPT <24h |
| Release | RECEIPT_SENT (seller) | confirm → broadcast | RELEASED | RECEIPT→RELEASED |
| Dispute | any non-terminal | publish → evidence → ruling | RELEASED/REFUNDED | resolution time |
| Wallet send | tab | validate → fee → confirm | tx in history | broadcast success |
| Invite | profile/home | QR / scan / paste | peer recorded | peer added |

### D. Event tracking suggestions (no analytics exist today — zero-backend; these are for a future local-first analytics module or manual QA)

| Event | Trigger | Properties |
|---|---|---|
| `onboarding_step_viewed` | step change | step, has_identity |
| `onboarding_completed` | FINISH | method (create/restore), nickname_set |
| `offer_created` | confirm publish | sats, price_idr, methods, ttl, fee_sats |
| `offer_edited` | edit saved | offer_id, had_live_taker |
| `offer_paused/resumed/deleted` | actions | offer_id |
| `offer_accept_attempted` | accept tap | offer_id, is_seller_acceptor |
| `offer_accept_won/lost` | CAS result | offer_id, alt_count |
| `escrow_created` | create | offer_id, script_type, deposit_sats |
| `escrow_funded` | verify success | escrow_id, method (wallet/manual), confs |
| `escrow_funding_failed` | verify failure | escrow_id, reason |
| `escrow_mark_paid` | buyer action | escrow_id |
| `receipt_sent` | composer send | escrow_id, has_image, reference |
| `receipt_rejected` | seller reject | escrow_id, reason_code |
| `escrow_released` | confirm | escrow_id, broadcast_ok |
| `escrow_disputed` | dispute | escrow_id, opened_by_role |
| `dispute_resolved` | arbitrator | escrow_id, decision |
| `wallet_send_attempted/succeeded/failed` | send | amount_sats, from_type, error |
| `chat_opened` | screen | offer_id, session_state |
| `chat_message_sent` | send | offer_id, has_attachment |
| `payment_details_shared` | share/auto-share | offer_id |
| `notification_tapped` | deep link | route, channel |
| `identity_locked/unlocked` | auth events | source |
| `transport_connected/disconnected` | sweep | node |

### E. Prompt for a designer/engineer next

> "Redesign the post-accept trade experience in NEO-P2P (Android, Compose). Today a buyer who accepts an offer lands in a chat that is locked until the on-chain escrow is FUNDED, then must jump between chat, escrow, and a separate receipt composer. Design a single 'trade hub' screen (Escrow + Chat tabs, role-adaptive) that becomes the destination after accept and after every escrow/chat deep link, with: (1) a persistent 'next action' bar with countdowns, (2) the pay-instruction card with unique code inline for the buyer, (3) the funding section inline for the seller, (4) a 'why is chat locked' tooltip, and (5) the receipt composer as a modal sheet from the hub. Keep the existing step tracker, fingerprint, connection chip, and relay-gate confirm. Also wire the currently dead `trade/{offerId}` route and register `neop2p://peer/<id>` as a system deep link. Deliver: screen mocks for buyer/seller states, a navigation spec (routes, back stack, saved state), and a list of ViewModel state changes."

---

*Analysis generated from source (2026-09-02). Assumptions are marked inline. Dead code noted: `trade/{offerId}` route, `onTabChange` params, `history` alias.*
