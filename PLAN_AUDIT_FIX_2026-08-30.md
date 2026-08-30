# NEO-P2P Audit Fix Plan — 2026-08-30
Scope: close P0 money / stuck-funds gaps and the top missing product surface found in the 2026-08-30 audit + double-check. No new backend, no KYC/OTP vendor, no hosted matcher. All trades remain local encrypted state + signed peer messages + on-chain proof.

> Repo truth: `android/` is the only Gradle project (`./gradlew :app:testDebugUnitTest`). DB `v21`, JDK 17, min26/target36. Inventory in §1 below is the baseline this plan patches.

## 1) Baseline (read from `android/` this session)
- **Stack:** Compose + Hilt + Room/SQLCipher 4.17.0 (16KB .so) + Ktor WS + BouncyCastle + bitcoinj + jvm-libp2p `1.3.6-RELEASE` (Tcp+Ws+Noise+Mplex+Identify+Autonat+RelayTransport/CircuitHop+Stop) + WebRTC (Stream SDK) + zxing. No DCUtR/holepunch in jvm-libp2p — `PeerRegistry.ConnectionQuality` 6 states is honest.
- **Transports:** `HybridP2PTransport` → `LibP2PManager` (pinned ports `41234/tcp`+`41235/ws`, `RelayTransport.setRelayCount(1)`) → `P2PTransportManager` (ws-relay `wss://relay1.custom-minipc.com:4003/ws`, heartbeat 60s, `error.to`→`RELAY_QUOTA`). `OfferRouter` publishes `currentMultiaddrs()` with LAN-IP substitution.
- **Escrow:** 2-of-3 P2SH *or* P2WSH (`BitcoinAddressType`), `deposit=crypto+0.5%+networkFee` (`FEE_NUM=5/FEE_DEN=1000/MIN_FEE_SATS=546`, `networkFee=fastest*~220-298 vB`, min 250). Verified via `ChainMonitor` (mempool.space→emzy.de, tip+`block_height`→confirmations). Statuses 11, guided flow `FUNDING→FUNDED→[SIGNED]→PAYMENT_PENDING→RECEIPT_SENT→CONFIRMING→RELEASED` plus `DISPUTED/RESOLVING/CANCELLED/REFUNDED`, resume-heal on `getEscrow` + `OfferRouter/EscrowRouter` forward-only + no-downgrade.
- **Screens/routes:** `NavGraph.kt` 14 routes — Onboarding(6-step)→Home→Create/EditOffer(sell-only)→OfferDetail→Chat→TradeRoom(thin)→Escrow→Escrow/receipt→DisputeEvidence→Wallet→History/Trades→Profile→Settings→OemNotifications→Invite→DisputeFeed.
- **Verification cmd:** `workdir: android` `./gradlew :app:testDebugUnitTest` (41 suites, 0 fail 2026-08-30), `assembleDebug`, `lintDebug`.

## 2) Goals & non-goals
- **P0 must close:** G.M.01 Double-free money, stale-DIRECT 0-30s relay-gate bypass, TOFU accept-without-compare, 1-conf tip-null fallback, fee doc drift, seed clipboard history. See §4.
- **P1 must ship:** F05b AutoNAT/reservation sheet, F08 per-rail instruction + QRIS QR, F07 unified TradeRoom (or delete thin route), F15 spendable line, F02/F03 feed health timestamp, F01/F16 PIN, F20 fork warning. §5 spec does `entry/actions/states/Bahasa/next/reference` for each.
- **Non-goals:** No server-side matcher/chat/taker queue, no KYC/phone OTP/email, no LDK hold-invoice in this pass (tracked separately), no full NIP-44/59 rust-nostr (tracked).

## 3) Research to keep
- Circuit Relay v2 + Autonat docs — keep RelayTransport wiring, do **not** claim `Memperkuat koneksi…` (no DCUtR). Reference `LibP2PManager.kt:370` mirror.
- go-libp2p `Limited` vs `Direct` issues — keep monotonic DIRECT + explicit downgrade, never show Limited as Direct.
- RoboSats/Haveno/Bisq offer TTL + disable-offer + disable-on-taker, Flip/Indodax kode-unik, BI QRIS limits — keep `OfferClaimGate`+`uniquePaymentCode`+QRIS notes, upgrade rendering.

## 4) Phase 0 — P0 fixes (land first, smallest root cause, rerun failing rows)

| # | Title | Root cause file:line | Patch | Verify row |
|---|---|---|---|---|
| **P0-1** | Price `Double` survives money path | `domain/model/Models.kt:29` `pricePerUnit: Double` + `Entities.kt:28` + `CreateOfferScreen.kt:528-537,573-582,752-757,766,941-946,952` Double fallback + `FiatFormat.kt:21` `formatIdrNoCurrency(Double)` | **Migrate** `pricePerUnit`→`priceIdrPerBtc: Long` (whole `Rp`/BTC). Room `Migration 21→22`: add `price_idr_per_btc INTEGER`, backfill `(price_per_unit+0.5).toLong`, keep old column for compat 1 release then drop. Remove `else Double` fiat branch → `require(btcSatsExact()!=null && priceIdrExact()!=null) else error "Harga harus Rp bilangan bulat (cth 1.500.000)"`. Collapse `totalFiat/tradeFiat` to `formatIdr((sats*price)/100M)`. Add `Math.multiplyExact(sats,price)` overflow guard → `ERR_AMOUNT_MISMATCH`. Delete `formatIdrNoCurrency(Double)` or retype to `Long`. | G.M.01 FAIL→PASS; add `PriceIntegerTest` (decimal `1,500.00`→reject, overflow→ERR, `1.500.000`→exact) |
| **P0-2** | Fee/doc drift `0.3%` vs `0.5%` | `NeoP2PConfig.kt:34,38` `0.5% 5/1000` vs `SECURITY_POSTURE.md:91` + `CHANGELOG.md:10` `3/1000` | Align docs+changelog to `5/1000 0.5%` (single source `NeoP2PConfig`). Add CI grep: fail if `SECURITY_POSTURE` mentions `3/1000` or `FEE_NUM` drifts. | `EscrowFeeMathTest:25` stays green |
| **P0-3** | Stale `DIRECT` 0-30 s → relay-gate skip | `LibP2PManager.kt:149-155` 30s poll only; `PeerRegistry.markPeerOffline:119` only via poll; `HybridP2PTransport.send:133` does not downgrade | Downgrade **synchronously**: (a) `LibP2PManager` `host.network:listen` close callback → `markPeerOffline`; (b) any `send`/`dial` `IOException` → `downgradeClosedConnections()` immediately; (c) register `ConnectivityManager.NetworkCallback.onLost` → `markAllOffline` + sweep. Reduce sweep to `15s` (cheap). | `F10-lock-relayed-gated PASS` with 0-window; add `PeerRegistryOfflineTest` (disconnect→quality OFFLINE <1s) |
| **P0-4** | TOFU release without fingerprint compare | `EscrowScreen.kt:146 confirmReceipt` gated only by peer-readiness; `PeerFingerprint` renders in `ChatScreen:115`+`EscrowScreen:628` but never required | Gate `confirmReceipt` behind explicit checkbox `Saya sudah cocokkan 8 kata sidik jari di luar aplikasi` + input of 2 of 8 words (or “Ketik 4 kata pertama sidik jari”). Store `fingerprintVerifiedAt` per escrow; warn banner if not verified. | Manual test: reject path → confirm disabled until checked |
| **P0-5** | 1-conf fallback on unknown tip | `ChainMonitor.kt:64-81` fallback `1L` when `tipHeight==null` | When `required==1` and `tip==null` and `status.confirmed==true`, return `Result.failure("Tip unavailable — coba lagi")` instead of `1`; UI shows retry (`R.string.escrow_tx_in_mempool` “coba lagi”). For `required>1` already fails closed. | `ChainMonitorTxInfoTest` new case `tipNull+confirmed → 0 pending` |
| **P0-6** | Seed clipboard history | `OnboardingScreen.kt:77-89` `newPlainText` no sensitive flag + trailing `""` | Replace clear with `ClipData.newPlainText("", "")` + `ClipDescription.EXTRA_IS_SENSITIVE` via `PersistableBundle`, and prefer **no clipboard** path: primary action `Tulis & Verifikasi` (already has 3-word challenge `OnboardingScreen.kt:632`); demote Copy to secondary + `isSensitive=true` when available (API 33). On `values-in` keep `R.string.onb_seed_copied` but reduce dwell to `30s`. | Lint grep `EXTRA_IS_SENSITIVE` present |

Acceptance for Phase 0: `P0` list empty, `testDebugUnitTest` + `lintDebug` green, scenario matrix rows above flip to PASS, and one emulator run of `G.C.02` kill-after-send (create offer → kill → restart → offer still in feed; fund → kill before `FUNDING→FUNDED` → reopen → resume-heal republish).

## 5) Phase 1 — Top 10 missing UI (`entry → actions → states → Bahasa → next → ref`, all P2 unless noted)

### 1) `ConnectionHonestySheet` (F05b, P0)
- **Entry:** Home `Menyinkronkan…` / any `ConnectionQualityChip` tap.
- **Actions:** Pipeline `OFFLINE → Cek jaringan… (CONNECTING) → Belum bisa diterima (AutoNAT unknown / tanpa reservasi) → Siap dihubungi (reservasi relay aktif) → Terhubung (lambat) RELAYED → Menyambung ulang… RECONNECTING → Kuota relai habis → Terhubung langsung DIRECT`. Copy `peerId` long-press, **never** `multiaddrs/IP`.
- **States:** loading(AutoNAT probing via `AutonatProtocol`), empty(no peers), error `ERR_RELAY_UNREACHABLE`, offline, timeout.
- **Bahasa:** `Cek jaringan… / Belum bisa diterima — relay reservasi / Siap dihubungi / Terhubung (lambat — lewat relai) / Kuota relai habis`.
- **Next:** money actions disabled or `gateRelayed` sheet (`EscrowScreen.kt:238 relay_confirm`) when not `DIRECT`.
- **Ref:** libp2p Circuit Relay reservation (2min, `setRelayCount(1)`) + Autonat v2; `jvm-libp2p` Autonat bound but not surfaced.

### 2) `PayInstructionSheet` per 13 rails (F08, P1)
- **Entry:** Escrow `FUNDED|PAYMENT_PENDING` buyer → `Cara bayar` per selected `FiatMethod` (`NeoP2PConfig.kt:224`).
- **Actions:** Per-method steps — BCA(m-BCA/Klik/ATM), Mandiri/Livin, BNI, BRI/BRImo, CIMB/OCTO, Jago, SeaBank, QRIS(render `qrisString`→`QrCode.generateQrCode` 400px, `Rp 10jt/ trx` cap), GoPay/OVO/DANA/ShopeePay/LinkAja(deep-link hint+vAccount), Cash(meetup checklist+map placeholder, no location in notif). Copy amount+`kode unik` (already `uniquePaymentCode`+`formatIdr`), copy QRIS/account.
- **States:** no-details `R.string.escrow_pay_no_details`, rail mismatch `escrow_pay_rail_mismatch`, BI-FAST note `escrow_pay_bifast_note`, timeout `24h+12h` countdown.
- **Bahasa:** `Transfer tepat Rp 1.250.432 — 432 kode unikmu` + `JANGAN tulis kripto/BTC/NEO di berita`.
- **Next:** amount-entry compare `expected vs entered` (already `EscrowScreen.kt:1605`) lifted into sheet.
- **Ref:** BI QRIS/PUEBI, Flip `kode unik`, Peach rail cards.

### 3) TradeRoom unification (F07, P1) — **decide**: either beef up **or delete** thin `trade/{offerId}` (`TradeRoomScreen.kt:76` 2-button shell)
- **If keep:** head = sticky `EscrowStatusChip`+countdown(`formatDurationShort`)+`ConnectionQualityChip`+`PeerFingerprint`+next-action line; body = last-3 chat + pay-card excerpt; CTAs `Lihat Eskro`→`escrow/{id}` / `Buka Obrolan`→`chat/{offer}/{peer}`. Back never mutates state.
- **If delete:** remove `Routes.TRADE_ROOM` and deep-links, route `Trades` tab straight to `EscrowScreen`/`ChatScreen`.

### 4) History receipt redacted + share + explorer links (F14, P2)
- **Entry:** `HistoryScreen.kt:162` row → Escrow completion (`EscrowScreen.kt:1715 share`).
- **Actions:** Redacted share text: `TradeID`, `tanggal`, `BTC`, `Rp`+`kode unik`, `fee=0.5%`, `fundingTxId`, `payoutTxId`, **no** `accountNumber`/`accountHolder`. Buttons `Salin TxID` + `Buka di explorer` (`escrow_open_explorer` already wired `EscrowScreen.kt:1041`) + `Simpan/Bagikan bukti` (`ACTION_SEND` already `1803`). Evidence ZIP export (`DisputeEvidenceEntity` images+manifest) via `ACTION_CREATE_DOCUMENT`.
- **Bahasa:** `Simpan / Bagikan Bukti` / `Bukti terenkripsi di perangkat — hanya arbiter yang menilai`.
- **Ref:** Bisq evidence export.

### 5) PIN app-lock + backup nag (F01/F16, P1)
- **Entry:** Onboarding after `VERIFY_SEED` + Settings `Kunci Aplikasi`.
- **Actions:** 6-digit PIN create/confirm/change, biometrik toggle; PIN wraps seed-wrap key (`KeyStoreAesGcmCipher`, `5-min` window `KeyStoreAesGcmCipher.kt:76`); tries→cooldown; nag `Lihat Frasa Lagi` every 24h until `OnboardingStore` verified.
- **Bahasa:** `Buat PIN 6 digit` / `PIN lemah — jangan 123456` / `ERR_INVALID_PIN`.
- **Ref:** Peach nag pattern.

### 6) Feed health header (F02/F03, P2)
- **Entry:** Home top.
- **Actions:** `Terhubung ke relay / Menyinkronkan… / Luring — daftar mungkin basi. Tarik untuk segarkan. (Diperbarui 14:05 WITA)` — pull→`HomeViewModel.refresh()`. Persist `lastPeerListAt` from `P2PTransportManager.peer_list`.
- **Bahasa:** `home_offline/home_connected` + device `Asia/Jakarta|Makassar|Jayapura` (`G.N.09`).
- **Ref:** Mostro stale-feed warning.

### 7) Funding countdown sheet (F10/F12, P1)
- **Entry:** Escrow `FUNDING` seller bottom sheet.
- **Actions:** Live `45m` countdown (`escrow_funding_window`+`formatDurationShort`+warning at `FUNDING_WARNING_MS 30m`), address QR `generateQrCode`, `Salin alamat`, `networkFee` line already `escrow_funding_miner_fee_estimate`, `Legacy(2…)↔SegWit(bc1…)` switch before `FUNDED` (`EscrowScreen.kt:853`).
- **Bahasa:** `Waktu pendanaan: 12:03 tersisa` / `Terkunci setelah didanai — alamat final`.

### 8) Wallet spendable line (F15, P1)
- **Entry:** `WalletScreen.kt:230`.
- **Actions:** Show `Saldo total + Belum dikonfirmasi + Terkunci di escrow + Dapat dikirim (=confirmed - lockedActive)` where `lockedActive` = `escrowDao.getAllEscrowsSync` filtered `seller_peer_id==myPeerId && status in FUNDED…RESOLVING` (`WalletScreen.kt:792`). Inline per-`sendFrom` hint (Legacy/SegWit/Auto).
- **Bahasa:** `Dapat dikirim: 0,0031 BTC` / `Saldo terkunci — tunggu escrow selesai`.

### 9) Dispute deadlock bar (F13, P2)
- **Entry:** Escrow `DISPUTED|RESOLVING`.
- **Actions:** Frozen banner already `escrow_dispute_frozen_* EscrowScreen.kt:674` + evidence count + `kind:33388` resolution banner showing `refund_destination`/`decision`. Evidence list uses `DisputeEvidenceDao`.
- **Bahasa:** `Dana dibekukan — sengketa dibuka. Jangan kirim transfer lagi.`

### 10) Restore fork warning (F20, P1)
- **Entry:** Before `IdentityManager.restoreFromSeedPhrase` + after `onb_restore_warning_body`.
- **Actions:** Pre-flight checklist `Perangkat lama masih punya chat/escrow — ekspor bukti dulu?`; fork detection if same `peerId` seen with divergent `conversation_keys` count → banner `Deteksi 2 perangkat — jangan trading di keduanya, yang terakhir menang`; gate with `RestoreGuard.allowRestore`.
- **Bahasa:** `Jangan trading di 2 HP bersamaan`.

## 6) Global scenario gate (every implemented flow, before merge)

```
G.H.01 happy two peers
G.N.01 unreachable, G.N.02 drop mid-flow, G.N.03 unilateral disconnect, G.N.04 WiFi↔cellular,
G.N.05 captive portal, G.N.06 loss/high RTT, G.N.10 punch-fail stay RELAYED,
G.N.12 relay quota, G.C.01 double tap, G.C.02 kill after send before persist,
G.C.03 kill after persist before ack, G.D.01 OEM kill, G.D.02 airplane, G.U.01 required surface,
G.U.02 Bahasa Rp 1.250.000, G.M.01 integer-only
```
- Mark `PASS` only with emulator evidence (screen video or log `P2POrchestrator` + `ChainMonitor`). Otherwise `UNTESTED` — do not infer. Current audit left `G.D/G.N.04-06/G.N.05/G.A` as `UNTESTED`; Phase 0 must re-run at least `G.H.01`, `G.C.02`, `G.N.10`, `G.N.12`, `F10-lock-relayed-gated` on device.

Per-flow extras: `F01 skip backup blocked / bad seed`; `F03 expired cache`; `F05 invalid QR/camera denied/already-connected`; `F06 double accept (ClaimOffer)`; `F08 underpay/overpay/wrong rail/QRIS expired/two trades same amount (kode unik)`; `F09 screenshot not auto-release`; `F10 wrong network / stuck tx / double release`; `F11 offline queue`; `F12 fiat-sent cancel is dispute`; `F19 two trades distinct refs` — already verified in code, keep gated by `claimOffer`+`DustThreshold`+`uniquePaymentCode`.

## 7) Execution plan

**Week 0 (P0 only, 2-3 days):**
1. Land `P0-1` price migration (branch `fix/GM01-price-long`). Files: `Models.kt:29`, `Entities.kt:28`+`Migration 21→22`, `OfferRouter.kt:314`, `CreateOfferScreen.kt:528f,752f`, `FiatFormat.kt:21`, `NostrClient.kt` (price parse). Add `PriceIntegerTest`, `FiatCalculationOverflowTest`. Verify `testDebugUnitTest` green, bench `formatIdr(1_250_000)=="Rp 1.250.000"`.
2. Land `P0-3` stale-DIRECT (branch `fix/direct-window`). Files: `LibP2PManager.kt:149`, `PeerRegistry.kt:119`, `HybridP2PTransport.kt:133`, `EscrowScreen.kt:146`. Add `PeerRegistryOfflineTest`.
3. Land `P0-5` tip gate, `P0-6` clipboard, `P0-2` docs in one docs-only PR.

**Week 1 (P1 UI):**
4. `ConnectionHonestySheet` + `FeedHealthHeader` (read-only, no money risk).
5. `PayInstructionSheet` (13 rails) + QRIS QR render.
6. `FundingCountdownSheet` + `Wallet spendable` line.
7. `History receipt redacted` + explorer link + ZIP export.

**Week 2 (Polish & fork/nag):**
8. Decide `TradeRoom` keep-vs-delete (product call; default keep beefed).
9. `PIN app-lock` + backup nag (depends on `SqlCipherPassphraseManager` + `KeyStoreAesGcmCipher`).
10. `Restore fork warning` + TOFU checkbox gate (`P0-4`) + OEM `NetworkCallback` hardening.

Each PR includes: unit test where pure (`FEE_NUM overflow`, `parseIdr`, `uniquePaymentCode`, `ChainMonitor.parseTxInfo`, `OfferClaimGate`), one emulator scenario video (G.H.01 or P0-specific), and `error_code_line` Bahasa string.

## 8) Risks & guardrails
- **Money is integer only:** no `Double` survives `fiatAmount`/`feeSats`/`depositAmountSats`; overflow via `multiplyExact`. CI lint: grep `pricePerUnit.*Double` fails after P0-1.
- **Circuit Relay is not Direct:** `PeerRegistry` monotonic DIRECT + synchronous downgrade; never map `RELAYED/Limited→Connected`; escrow `gateRelayed` stays for any `!=DIRECT`.
- **Screenshot ≠ proof:** `confirmReceipt` remains seller-only (`EscrowService.canReleaseFromStatus`); reject path advisory only, funds stay `CONFIRMING` until seller confirms or `PAYMENT_WINDOW+GRACE`→`DISPUTED` auto.
- **Never print secrets:** seeds/keys/mnemonics/.env stay `Read` in test only; plan adds `isSensitive` flag.
- **Stop condition:** `P0` list non-empty blocks `P1` merge.

## 9) Done when
- `P0 1-6` closed, `testDebugUnitTest`+`lintDebug` green, expanded scenario matrix rows above flipped `UNTESTED→PASS` with attached evidence, and Indonesian rail smoke test (BCA+Mandiri+BRI+QRIS GoPay/OVO/DANA + Cash meetup) happy-path completed on two emulators (API 30 + 34) without exposing `multiaddrs/IP` in notifications.

---
*Generated from repo reads 2026-08-30. Patch smallest root cause first; rerun the failing row after each fix — no batch land.*
