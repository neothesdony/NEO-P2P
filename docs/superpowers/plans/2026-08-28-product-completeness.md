# NEO-P2P Product Completeness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the audited product-completeness gaps in dependency order — money-stuck risks first (IDR pay instructions, two-taker race, IDR formatting), then missing product surface (QR invite, block/report, offer TTL, filters), then polish (sticky action bar, chat delivery status, OEM help). All P2P-safe: no backend, no coordinator, no KYC.

**Architecture:** Four batches.
- A (P1 money/trust): FiatFormat util; two-taker CAS claim; pay-instruction card; localized notifications; mempool progress card.
- B (P1/P2 product surface): block peer + evidence export; QR invite (show/scan/paste); offer TTL (DB v21); home filters + unread + sync banner.
- C (P2/P3 polish): sticky next-action bar; chat queued/sent status; OEM notification help; restore warning.
- Every batch ends with build + full unit tests green.

**Tech Stack:** Kotlin 2.1.0, Compose M3, Hilt, Room (SQLCipher, DB v20 → v21), zxing (already a dep for wallet QR), Navigation Compose, WorkManager.

**Spec source:** 2026-08-28 deep-audit session (repo-verified findings at HEAD 4435e6f; references in session digest — Bisq, RoboSats, HodlHodl, Peach, BasicSwap, Briar, SimpleX, Amethyst, dontkillmyapp, BI QRIS, Indodax/Flip kode unik, PUEBI).

## Global Constraints

- **DO NOT commit; user commits.** Do NOT install to devices unless user says so.
- All Gradle commands run from `android/` (`workdir: /home/thesdony/neop2p-btc/android`). Never pipe gradle through tail and trust exit code. Pattern: `./gradlew :app:assembleDebug --console=plain -q; echo "EXIT=$?"`.
- JDK 17 pinned machine-wide via `~/.gradle/gradle.properties` (already set). If `AndroidLocationsException` appears: `env -u ANDROID_PREFS_ROOT ./gradlew ...`.
- All new user-facing text via `stringResource` + entries in BOTH `values/strings.xml` (EN) and `values-in/strings.xml` (ID). Verify parity after each batch.
- All colors via `MaterialTheme.colorScheme.*` — never hardcoded `Color(0x...)` outside `Theme.kt`.
- IDR amounts ALWAYS rendered via the new `formatIdr()` util — never `%,d` / `%,.0f` / hand-rolled.
- No backend, no hosted matching, no server inbox, no KYC. Every added flow must survive on local state + relay events + on-chain proofs.
- Room migration style: existing migrations are explicit `Migration(N, N+1)` objects (see `AppDatabase.kt`). Add `MIGRATION_20_21`, bump `version = 21`. **Do NOT use destructive migration.**

---

# BATCH A — MONEY / TRUST (P1)

## Task A1: FiatFormat util — PUEBI IDR formatting

**Objective:** One `formatIdr()` producing "Rp 1.250.000" (space after Rp, dot thousands, no decimals for whole numbers), replacing every `%,d` / `%,.0f` IDR site.

**Files:**
- Create: `android/app/src/main/java/com/neop2p/ui/util/FiatFormat.kt`
- Test: `android/app/src/test/java/com/neop2p/ui/util/FiatFormatTest.kt`
- Modify:
  - `ui/screens/home/HomeScreen.kt:568` — `home_fiat_amount` call now passes `formatIdr(offer.fiatAmount)`; line 584 `String.format("%,.0f", offer.pricePerUnit)` → `formatIdrNoCurrency(offer.pricePerUnit)` (whole rupiah per BTC)
  - `ui/screens/createoffer/CreateOfferScreen.kt:439,490,493,496` — `totalFiat`, `tradeFiatFormatted`, `buyerFeeFiatFormatted`, `totalFiatPayableFormatted` → `formatIdr(long)`
  - `ui/screens/offerdetail/OfferDetailScreen.kt:284-285` — price + fiat rows → `formatIdrNoCurrency(...)` / `formatIdr(...)`
  - `res/values/strings.xml:358` + `res/values-in/strings.xml:327` — `home_fiat_amount` → `<string name="home_fiat_amount">%1$s</string>` (formatting moves into the util)

**Step 1: Write failing test** `FiatFormatTest.kt`:
```kotlin
class FiatFormatTest {
    @Test fun `formats whole rupiah with dot thousands`() {
        assertEquals("Rp 1.250.000", formatIdr(1_250_000L))
    }
    @Test fun `handles small amounts`() {
        assertEquals("Rp 15.000", formatIdr(15_000L))
    }
    @Test fun `handles zero`() {
        assertEquals("Rp 0", formatIdr(0L))
    }
    @Test fun `never uses comma thousands`() {
        assertFalse(formatIdr(1_250_000L).contains(","))
    }
    @Test fun `price per btc without currency symbol`() {
        assertEquals("1.250.000", formatIdrNoCurrency(1_250_000.0))
    }
}
```

**Step 2: Run to verify failure** — `./gradlew :app:testDebugUnitTest --tests "com.neop2p.ui.util.FiatFormatTest" --console=plain -q; echo "EXIT=$?"` — Expected: FAIL (no class).

**Step 3: Implement**
```kotlin
package com.neop2p.ui.util
import java.util.Locale

/** PUEBI IDR: "Rp 1.250.000" — space after Rp, dot thousands, no decimals for whole numbers. */
fun formatIdr(amount: Long): String {
    val s = String.format(Locale.US, "%,d", amount).replace(",", ".")
    return "Rp $s"
}
/** Same grouping, no "Rp " prefix (for price-per-unit strings). */
fun formatIdrNoCurrency(amount: Double): String =
    String.format(Locale.US, "%,.0f", amount).replace(",", ".")
```

**Step 4: Run tests** — Expected: PASS. Then replace the 8 call sites + 2 string resources. Then `./gradlew :app:testDebugUnitTest --console=plain -q; echo "EXIT=$?"` — expected all green.

**Step 5: String parity check**
```bash
python3 - <<'EOF'
import re
def load(p):
    return set(re.findall(r'<string name="([^"]+)"', open(p, encoding='utf-8').read()))
print("EN only:", sorted(load('app/src/main/res/values/strings.xml') - load('app/src/main/res/values-in/strings.xml')))
print("ID only:", sorted(load('app/src/main/res/values-in/strings.xml') - load('app/src/main/res/values/strings.xml')))
EOF
```

---

## Task A2: Two-taker collision fix — compare-and-set claim

**Objective:** A MATCHED offer can only be claimed by the FIRST taker; a losing taker sees "Tawaran sudah diambil pedagang lain" and is NOT routed into chat for a lost trade.

**Files:**
- Modify: `data/local/dao/Daos.kt` (OfferDao — add claim query)
- Modify: `ui/screens/offerdetail/OfferDetailScreen.kt:677-741` (`acceptOffer` — use claim; on failure surface collision instead of chat)
- Modify: `data/p2p/routing/OfferRouter.kt:96-107` — relay MATCHED events must not overwrite an existing `matched_peer_id` (only fill when blank; already mostly guarded — make the `else if` the ONLY path, never unconditional overwrite)
- Test: `app/src/test/java/com/neop2p/data/local/dao/OfferClaimTest.kt` (pure JVM — DAO test needs Room in-memory; if Robolectric absent, test the guard logic by extracting `claimOffer` decision into a pure function OR test via Router-level logic; fallback: JVM test on a small `OfferClaimGate` object)
- Strings (both locales): `offer_taken_title` "Tawaran sudah diambil", `offer_taken_body` "Pedagang lain sudah mengambil tawaran ini. Silakan pilih tawaran lain."

**Step 1: DAO claim query**
```kotlin
@Query("UPDATE trade_offers SET status = :status, matched_peer_id = :matchedPeerId " +
       "WHERE offer_id = :offerId AND status = 'OPEN' AND (matched_peer_id IS NULL OR matched_peer_id = '')")
suspend fun claimOffer(offerId: String, status: String, matchedPeerId: String): Int
```

**Step 2: `acceptOffer` rewrite (OfferDetailScreen.kt:677)**
```kotlin
val claimed = offerDao.claimOffer(offer.offerId, OfferStatus.MATCHED.name, myIdentity.peerId)
if (claimed != 1) {
    withContext(Dispatchers.Main) { onAccepted(Taken) }  // new result type
    return@launch
}
```
- Change `onAccepted: (String?) -> Unit` to a sealed result: `Accepted(escrowId?)` / `Taken`. On `Taken`: show snackbar/error `offer_taken_body`, do NOT call `onChatClick`.
- Keep publish kind:33336 (idempotent; router now guards overwrite).
- Also re-check status INSIDE the accept dialog commit (read offer fresh before showing confirm; if no longer OPEN, replace button with taken notice).

**Step 3: Router guard** — in the `else if` branch (OfferRouter.kt:102-107), only fill `matched_peer_id` when `existing?.matched_peer_id.isNullOrBlank()`; never update an existing non-blank match from a relay event authored by a DIFFERENT peer.

**Step 4: Tests** — `OfferClaimTest`: (a) claim succeeds once; (b) second claim returns 0; (c) relay MATCHED from peer B does not overwrite peer A's match; (d) stale replay after ESCROWED still rejected.

**Step 5:** Full test run + build.

---

## Task A3: Pay instruction card — buyer sees exact IDR + unique code

**Objective:** Buyer on escrow detail (FUNDED / PAYMENT_PENDING / RECEIPT_SENT) sees one card: exact IDR amount with 3-digit unique suffix, bank/e-wallet account, safety copy, and the receipt composer shows IDR (not sats).

**Files:**
- Modify: `ui/screens/escrow/EscrowScreen.kt` — insert `PayInstructionCard` (new private composable) in the post-funding section, before the action buttons (`when (escrow.status)` block, ~line 790); renders when `isRole == BUYER && paymentDetails.isNotEmpty()`
- Modify: `ui/screens/escrow/ReceiptComposerViewModel.kt:31-68` — UiState gains `fiatAmount: Long = 0`; `load()` sets it from `offer.fiatAmount`
- Modify: `ui/screens/escrow/ReceiptComposerScreen.kt:170` — show `formatIdr(state.fiatAmount)`; keep sats as secondary line
- Strings (both locales):
  - `escrow_pay_instruction_title` "Instruksi Pembayaran"
  - `escrow_pay_amount_exact` "Transfer tepat %1$s — %2$s adalah kode unik transaksi ini"
  - `escrow_pay_amount_mismatch` "Transfer dengan nominal berbeda TIDAK akan dikenali penjual."
  - `escrow_pay_own_account` "Transfer dari rekening/e-wallet atas nama Anda sendiri."
  - `escrow_pay_no_crypto_note` "JANGAN tulis kata 'kripto', 'BTC', atau 'NEO' di catatan transfer."
  - `escrow_pay_qris_note` "QRIS: scan, nominal otomatis terisi, verifikasi nama merchant, masukkan PIN. Maksimal Rp 10.000.000 per transaksi."
  - `escrow_pay_no_details` "Detail pembayaran belum diterima — penjual belum berbagi rekening. Coba lagi sebentar."
  - `escrow_receipt_amount_idr` "Jumlah: %1$s"
- **Unique-code design (P2P-safe, per Indodax/Flip convention):** use the last 3 digits of `fiatAmount + escrowId.hashCode() % 1000`? NO — deterministic per-trade code from a pure local function so both sides can verify: `code = (fiatAmount % 997).toString().padStart(3, '0')`? **Decision: derive code from the escrowId** — `escrowId.takeLast(3).filter(Char::isDigit)` padded; if fewer than 3 digits, use `(fiatAmount % 1000)` zero-padded. Deterministic on both devices (escrowId syncs via kind:33337; fiatAmount syncs via the offer). Displayed amount = `fiatAmount + code` as a copyable string "Rp 1.250.432". Reference: Indodax kode unik (3-digit suffix), Flip/BI-FAST convention.
- Seller side: in the SELLER branch of PAYMENT_PENDING/RECEIPT_SENT add "Yang dibayar: %1$s (termasuk kode unik)" line so the seller checks the tail.

**Verification:** build; both-role rendering checked in code review (buyer sees card, seller sees expected line); receipt composer shows IDR.

---

## Task A4: Localize notifications + identity-safe chat title + onboarding errors

**Objective:** Every notification string in `values`/`values-in`; chat notification title becomes "Pesan baru" (no peerId); onboarding hardcoded English errors become string resources.

**Files:**
- Modify: `data/p2p/P2POrchestrator.kt:358-371` (escrow transition map), `:424-426` (dispute), `:452` (evidence), `:501-503` (resolution), `:161-176` (offer matched/deleted) — replace hardcoded EN pairs with `context.getString(R.string.notif_*)` lookups. Orchestrator needs a `Context` (already has one — verify constructor; it calls `context.getString` pattern elsewhere or inject `@ApplicationContext`).
- Modify: `service/NotificationDispatcher.kt:145` — `setContentTitle(senderLabel.ifBlank { context.getString(R.string.notif_new_message) })`; `:161-162,175-176,252-253` — `context.getString(...)`.
- Modify: `ui/screens/onboarding/OnboardingScreen.kt:705,716,807-809` — replace hardcoded strings with resources: `onb_seed_12_words`, `onb_seed_invalid`, `onb_verify_incomplete`, `onb_verify_mismatch`.
- Strings (both locales): `notif_new_message` "Pesan baru"/"New message"; `notif_offer_matched_title/body`; `notif_offer_deleted_title/body`; `notif_escrow_created/funded/signed/paid/receipt_sent/confirming/payment_grace/refund_grace/released/disputed/resolving/refunded/cancelled` title+body pairs; `notif_dispute_opened`, `notif_evidence_new`, `notif_dispute_resolved`, `notif_wallet_received`; plus the 4 onboarding error strings.
- Also fix parity drift: add EN entries for ID-only `chat_attach`, `chat_input_hint`, `chat_send` (or delete if dead — verify usage first; deep-check found zero R.string refs, so DELETE all three from values-in).

**Verification:** grep `"[A-Z][a-z].*"` in NotificationDispatcher + Orchestrator returns only log lines; string parity script clean.

---

## Task A5: Mempool pending progress card + explorer link

**Objective:** FUNDING-with-txid shows confirmation progress and a "Buka di explorer" deep link; buyer mirror sees the same.

**Files:**
- Modify: `ui/screens/escrow/EscrowScreen.kt` FUNDING branch (~line 690-750): under the txid field add:
  - "Tx terlihat di jaringan — menunggu %1$d konfirmasi"
  - Link: `Intent.ACTION_VIEW, Uri.parse("https://mempool.space/testnet4/tx/$txid")` (network from `BuildConfig.NETWORK`; testnet4 for test builds — reuse the same base the app uses)
  - Warning (buyer side): "Jangan kirim pembayaran IDR sebelum pendanaan terkonfirmasi." (reuse/harmonize with `escrow_payment_instructions` usage)
- Modify: `res/values/strings.xml` + `values-in/strings.xml`: `escrow_tx_in_mempool`, `escrow_open_explorer`, `escrow_dont_pay_until_funded`.
- Reference: Peach `TransactionInMempool.tsx` pattern (mempool → waiting → confirmed states).

**Verification:** build; review that the link only renders when txid non-blank; network base correct per BuildConfig.

---

# BATCH B — PRODUCT SURFACE (P1/P2)

## Task B1: Block peer (local) + evidence export

**Objective:** Block a peer locally (offers hidden), and export dispute evidence as a shareable bundle. No server.

**Files:**
- Create: `data/local/BlockedPeerStore.kt` — SharedPreferences-backed set of blocked peerIds (simple, no DB bump): `isBlocked(peerId)`, `block(peerId)`, `unblock(peerId)`.
- Modify: `data/p2p/routing/OfferRouter.kt:150` (`ingestOfferEvent`) — skip offers whose creator is blocked.
- Modify: `ui/screens/home/HomeScreen.kt` `TradeOfferCard`/card actions — long-press or overflow "Blokir Pedagang" (confirm sheet) → `blockedPeerStore.block(creatorPeerId)`.
- Modify: `ui/screens/profile/ProfileScreen.kt` — in the peer section (or Settings): "Pedagang Diblokir" list with unblock.
- Modify: `ui/screens/escrow/DisputeEvidenceScreen.kt` — "Ekspor Bukti" button: build JSON bundle (escrowId, status, txids, evidence list w/ descriptions, timestamps) → write to `cacheDir/evidence-<escrowId>.json` → `FileProvider` share sheet.
- Modify: `AndroidManifest.xml` — add `<provider android:name="androidx.core.content.FileProvider" ...>` + `res/xml/file_paths.xml` (cache path).
- Strings (both locales): `peer_block_confirm_title/body`, `peer_blocked`, `peer_unblock`, `blocked_peers_empty`, `evidence_export`, `evidence_export_failed`, `evidence_export_done`.
- Add FileProvider dependency if not present (check `app/build.gradle.kts`; androidx.core is already a dep — FileProvider ships in core).

**Verification:** build; unit test for `BlockedPeerStore` (pure JVM, SharedPreferences via mock or Robolectric-free — if not testable without Android, verify by grep + build; keep logic thin).

---

## Task B2: QR invite — show / paste / scan

**Objective:** Connect a peer by QR (show mine / scan theirs) or pasted link. CAMERA permission already declared. zxing already a dependency (wallet QR uses `QRCodeWriter`).

**Files:**
- Create: `ui/screens/invite/InviteScreen.kt` — two tabs: "Tampilkan QR" (QR of `neop2p://peer/<peerId>?relay=<relayUrl>`, rendered via existing `QRCodeWriter` pattern from WalletScreen.kt:459-464, copy-link button) and "Pindai / Tempel" (paste TextField + scan button).
- Create: `ui/screens/invite/InviteViewModel.kt` — parse pasted link / scanned payload; validate `neop2p://peer/<64-hex-or-base58>?relay=...`; on valid → `PeerRegistry` upsert (verify PeerRegistry API — has add/upsert? deep-check showed `store/PeerRegistry.kt` exists; wire accordingly) → success state "Rekan ditambahkan".
- Scan: use `ActivityResultContracts.ScanQrCode` (ZXing integration) if available in the zxing version; else add `com.journeyapps:zxing-android-embedded` (check version catalog first; only add if missing) with `ScanContract`; camera-permission rationale dialog before first scan.
- Modify: `navigation/NavGraph.kt` — `INVITE = "invite"` route; entry from Profile ("Undang Rekan") and Home top-bar or empty-state ("Pindai").
- Strings (both locales): `invite_title`, `invite_show_qr`, `invite_scan`, `invite_paste_placeholder`, `invite_invalid_qr`, `invite_already_connected`, `invite_success`, `invite_camera_denied`, `invite_peer_id_hint`.
- States required: camera denied (with "Buka Pengaturan" action), invalid QR error, already-connected, connecting, success, empty (no link pasted yet).

**Verification:** build; manual flow on two devices/emulators (user drives UI per flow-test convention).

---

## Task B3: Offer TTL — stale badge + accept gate + filters (DB v21)

**Objective:** Offers expire; stale ones are visible-but-blocked; home shows filters (method / min-max IDR).

**Files:**
- Modify: `data/local/entity/Entities.kt` — `TradeOfferEntity` + `expires_at: Long? = null`
- Modify: `data/local/AppDatabase.kt` — `version = 21` + `MIGRATION_20_21` (`ALTER TABLE trade_offers ADD COLUMN expires_at INTEGER`)
- Modify: `domain/model/Models.kt` — `TradeOffer.expiresAt: Long?`
- Modify: `ui/screens/createoffer/CreateOfferScreen.kt` — TTL selector (default 24h; options 6h/12h/24h/48h) + include in offer JSON; `data/p2p/routing/OfferRouter.kt:209-237` parse `expires_at`; `CreateOfferScreen` publish + EditOffer preserve it
- Modify: `ui/screens/home/HomeScreen.kt` — card shows "Berakhir dalam 02:14" when < 1h, "Kedaluwarsa" greyed state when past TTL; accept on OfferDetail blocked past TTL with `offer_expired` copy (check at accept-dialog open AND at claim: `claimOffer` WHERE clause gains `AND (expires_at IS NULL OR expires_at > :now)` — returns 0 → taken/expired copy)
- Modify: `ui/screens/offerdetail/OfferDetailScreen.kt` — expired state
- Strings (both locales): `offer_expires_in`, `offer_expired`, `offer_ttl_label`, `offer_ttl_6h/12h/24h/48h`.
- Reference: BasicSwap "Offer valid (hrs)" + RoboSats order-box expiry countdown.

**Verification:** `./gradlew :app:testDebugUnitTest` green; build; migration tested by install-over-existing-data (user device).

---

## Task B4: Home filters + unread badge + sync banner

**Objective:** Home market tab: filter chips (Semua / BCA / QRIS / e-Wallet / Tunai + min/max IDR), unread-chat badge on the chat quick icon, and a sync-status banner (relay connected / syncing / offline).

**Files:**
- Modify: `ui/screens/home/HomeScreen.kt` — filter chip row under the top bar (local state; filters the already-loaded list by `fiatMethods` intersection + `fiatAmount` range); sync banner above the list: from `HomeViewModel` expose `transportState` (reuse `isTransportActive()` + a new periodic relay check or the existing NostrClient connection state — verify what `NostrClient` exposes; if nothing, add a simple `relayConnected: StateFlow<Boolean>` to HomeViewModel from the existing transport callbacks).
- Modify: `ui/screens/home/HomeViewModel` — unread counts: `chatMessageDao.getUnreadMessages(offerId)` per active offer (or a new DAO query `countUnreadByOffer(offerId)`); expose `activeChatUnread: StateFlow<Int>`; HomeScreen shows badge on the chat quick icon when > 0.
- Modify: `data/local/dao/Daos.kt` — add `@Query("SELECT COUNT(*) FROM chat_messages WHERE offer_id = :offerId AND is_read = 0") suspend fun countUnreadByOffer(offerId: String): Int` (+ mark-as-read already exists).
- Strings (both locales): `home_filter_all`, `home_filter_bca`, `home_filter_qris`, `home_filter_ewallet`, `home_filter_cash`, `home_syncing`, `home_offline`, `home_connected`.
- States: syncing banner shows while first load/refresh; offline banner when transport inactive (retry affordance reuses existing refresh).

**Verification:** build; grep that no hardcoded filter labels; unread badge renders only for the active trade's offer.

---

# BATCH C — POLISH (P2/P3)

## Task C1: Sticky "Langkah Anda selanjutnya" bar (escrow detail)

**Objective:** Bottom sticky bar on EscrowScreen showing the single next action + countdown, role+state resolved; disabled actions show the reason.

**Files:**
- Modify: `ui/screens/escrow/EscrowScreen.kt` — add `NextActionBar(escrow, role)` composable pinned at the bottom (Column with `navigationBarsPadding()`), above/merged with existing action buttons; content per state:
  - FUNDING/seller → "Dana: kirim BTC ke alamat eskro (45:00 tersisa)"
  - FUNDING/buyer → "Menunggu deposit penjual…"
  - FUNDED/buyer → "Bayar: transfer %1$s lalu kirim bukti"
  - FUNDED/seller → "Menunggu pembayaran pembeli…"
  - PAYMENT_PENDING/buyer → "Kirim bukti pembayaran"
  - RECEIPT_SENT/seller → "Konfirmasi: rilis BTC setelah IDR masuk"
  - CONFIRMING/seller → "Menunggu konfirmasi jaringan…"
  - DISPUTED → "Sengketa berjalan — bukti Anda adalah satu-satunya senjata"
- Reuse existing countdown composables (`FundingWindowCountdown`, `PaymentWindowCountdown`, `RefundWindowCountdown`).
- Strings (both locales): `next_action_*` per state above.
- Reference: RoboSats single-action-per-state; Binance ID "Batas waktu pembayaran 30:00".

**Verification:** build; manual: each status renders exactly one primary action with reason when disabled.

---

## Task C2: Chat delivery status — queued/sent indicator

**Objective:** Per-message status on own bubbles: "Mengirim…" → "Terkirim" / "Menunggu rekan online". `delivered_at` column already exists (Entities.kt:57) but is never written.

**Files:**
- Modify: `data/p2p/routing/ChatRouter.kt:49-62` (`sendText`) — after `queue.drainFor`, write `delivered_at = System.currentTimeMillis()` when delivery succeeded; leave null when queued. Also `OfflineQueue.drainFor` (data/p2p/queue/OfflineQueue.kt:33-43) — when a pending row is successfully delivered, mark `delivered_at` on the corresponding chat row (match by offer + peer + created_at window, or by message_id if the queue row carries it — verify PendingMessageEntity shape; if no link, store the chat message_id in the queue payload).
- Modify: `ui/screens/chat/ChatScreen.kt` message bubble — for own messages (sender == myPeerId), show "✓ Terkirim" when `delivered_at != null`, "Menunggu rekan online" (with clock icon) when null and older than ~10s, "Mengirim…" transient state if needed (keep simple: two states).
- Modify: `domain/model` ChatMessage mapping if needed (deliveredAt field).
- Strings (both locales): `chat_sent`, `chat_queued`, `chat_sending`.
- Reference: Briar `MessageStatus.java` (pending/acked), SimpleX delivery events.

**Verification:** build; unit test for the delivered_at write logic if extractable (else manual two-device).

---

## Task C3: OEM notification help screen (Settings)

**Objective:** Settings → "Aktifkan Notifikasi (Ponsel ini)" → per-OEM checklist.

**Files:**
- Create: `ui/screens/settings/OemNotificationHelpScreen.kt` — static checklist by manufacturer (`Build.MANUFACTURER`): Xiaomi/Redmi/Poco → "Security → Izin → Autostart → aktifkan NEO-P2P" + "kunci aplikasi di recents"; Samsung → "Baterai → Batas penggunaan latar → Never sleeping" + "matikan 'Put unused apps to sleep'"; Oppo/Realme → "Izin aplikasi → Izinkan Auto Start-up"; Vivo → "Pengaturan → Aplikasi → Autostart"; Huawei → "Baterai → Peluncuran aplikasi → Kelola manual (semua aktif)"; default → generic "Izinkan notifikasi + nonaktifkan penghemat baterai".
- Modify: `navigation/NavGraph.kt` — route `settings/oem_notifications`; entry row in `SettingsScreen.kt` under a "Notifikasi" section.
- Strings (both locales): `settings_oem_title`, `settings_oem_desc`, per-OEM body strings (or one generic + per-brand titles), `settings_oem_done`.
- Reference: dontkillmyapp.com per-OEM checklists (Xiaomi/Samsung/Oppo/Realme/Vivo/Huawei).

**Verification:** build; review manufacturer switch; no dead strings.

---

## Task C4: Restore warning — "open trades live on this device"

**Objective:** After seed restore, tell the user open trades from another install are NOT on this device.

**Files:**
- Modify: `ui/screens/onboarding/OnboardingScreen.kt:107` — `onRestored = { viewModel.completeOnboarding() }` → route through a new step or a confirm dialog: `OnboardingStep.RESTORED_WARNING` (or a dialog before `completeOnboarding()`): "Identitas dipulihkan. Transaksi yang sedang berjalan di perangkat LAMA tidak muncul di perangkat ini — dana on-chain Anda aman (frasa pemulihan), tetapi riwayat dan transaksi terbuka hanya tersimpan di perangkat asal."
- Add step to `OnboardingStep` enum (check indicator total rendering — `OnboardingStep.entries.size` auto-adapts).
- Strings (both locales): `onb_restore_warning_title`, `onb_restore_warning_body`, `onb_restore_warning_continue`.
- Reference: RoboSats "Don't forget your order / back up your token" recovery copy; Amethyst login error states.

**Verification:** build; flow: restore → warning → FINISH.

---

# VERIFICATION (after every batch and at the end)

```bash
cd /home/thesdony/neop2p-btc/android
./gradlew :app:assembleDebug --console=plain -q; echo "EXIT=$?"
./gradlew :app:testDebugUnitTest --rerun-tasks --console=plain -q; echo "EXIT=$?"
# string parity script (above)
```
Expected: EXIT=0, all tests green (146 existing + new), parity clean (except intentional both-locale additions).

## Acceptance checklist (final)
- [ ] `formatIdr` used at every IDR site; no `%,d`/`%,.0f` remains
- [ ] Two takers: first wins, second sees "Tawaran sudah diambil", no chat routing on loss
- [ ] Buyer pay card shows exact IDR + unique code + safety copy; receipt composer shows IDR
- [ ] All notifications localized; chat title "Pesan baru"; onboarding errors localized
- [ ] Mempool progress + explorer link on FUNDING+txid
- [ ] Block peer hides their offers; evidence export shares a bundle
- [ ] QR invite: show/scan/paste with all error states
- [ ] Offer TTL: badge, expired gate, DB v21 migration, claim guard includes expiry
- [ ] Home filters + unread badge + sync banner
- [ ] Sticky next-action bar per role/state
- [ ] Chat queued/sent indicator (delivered_at written)
- [ ] OEM help screen by manufacturer
- [ ] Restore warning step
- [ ] Build + tests green; string parity clean

## Explicitly NOT in scope (rejected per constraints)
- No backend/coordinator/hosted matching; no KYC/OTP/email login
- No hosted dispute admin; no custodial escrow; no Telegram/email notification channels
- No PIN/biometric app lock (device-auth KeyStore gate already exists — P3 optional, deferred)
- No language toggle (UI is ID-first; EN strings exist for parity)
