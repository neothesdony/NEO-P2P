# NEO-P2P User Manual

**Version:** v1.0.27 (RNS/LXMF transport live)
**Platform:** Android (min SDK 26, target SDK 36)
**Network:** Bitcoin **testnet4** — this is experimental software. Do not trade real money.

---

## 1. What is NEO-P2P?

NEO-P2P is a **zero-backend, peer-to-peer anonymous crypto trading app for Indonesia**. It connects buyers and sellers of Bitcoin directly — no company server, no account, no KYC, no phone number, no email.

- **Identity** = a cryptographic keypair derived from a 12-word seed phrase. That's it.
- **Discovery & messaging** = Reticulum Network Stack (RNS) + LXMF. Phones connect to a community transport node (like a packet ferry) — the node cannot read your messages or touch your funds.
- **Escrow** = a real **on-chain 2-of-3 multisig**. The seller deposits BTC into an address that requires **2 of 3 signatures** (seller, buyer, arbitrator) to spend. Nobody can run away with the money.
- **Chat** = end-to-end encrypted (X25519 + ChaCha20-Poly1305). Only you and your peer can read it.
- **Fee** = **0.5%, paid by the seller only**. The buyer pays no fee and receives the full BTC amount.

> ⚠️ **Testnet warning:** the app currently runs on Bitcoin **testnet4**. BTC shown has no real value. Treat every trade as a test.

---

## 2. Installation

1. Build the APK (developer) or install the provided `neop2p-app-debug.apk`.
2. `adb install neop2p-app-debug.apk` or copy the APK to the phone and tap it.
3. Android may warn about unknown sources — allow it.
4. Open **NEO-P2P**.

**First launch requirements:**
- Internet connection (to reach the RNS transport node).
- A device screen lock (PIN/pattern/fingerprint) — the app uses it to protect your keys. Without one, the identity stays locked and P2P won't start.
- Notifications permission — the app needs it to tell you when an offer is matched, escrow is funded, or payment is confirmed.

---

## 3. First Run: Onboarding

### 3.1 Risk disclaimer
Read the warning carefully. It is not a formality: P2P trading carries real risks (scams, fake receipts, chargebacks, frozen accounts). Tap **I Understand and Accept** to continue.

### 3.2 Create your identity
- Tap **Generate Identity**. A 12-word seed phrase is created **on your device only**.
- Optional nickname (e.g. `trader_42`) — shown to peers. You can change it later in Profile.

### 3.3 Backup your seed phrase — THE MOST IMPORTANT STEP
- Write the **12 words on paper**. Store it offline, NOT as a screenshot.
- The seed phrase is the **ONLY** way to recover your identity and your wallet funds. If you lose it, your money is gone forever.
- NEO-P2P will **NEVER** ask you for your seed phrase. Anyone who asks is a scammer.
- Tick the three confirmation checkboxes, then verify by entering the requested words.

### 3.4 Restore (if you already have a seed)
On the welcome screen tap **"Already have a seed phrase? Restore"** and enter your 12 words. Your identity and wallet are recovered. Note: open trades/history from the old device do **not** transfer — they live only on the device where the trade happened. On-chain funds are safe because they come from the seed.

---

## 4. Main Screens (bottom tabs)

| Tab | What it does |
|-----|--------------|
| **Market** | Live offer feed (sell offers from all peers). Pull to refresh. |
| **Wallet** | Your personal Bitcoin wallet: balance, receive, send, history. |
| **Trades** | All your trades: needs-action, waiting, completed. Tap to re-enter a trade. |
| **Profile** | Your peer ID, public key, nickname, reputation, settings entry. |

Top-right menu on Market: Profile, Settings, Chat, Escrow, History, Wallet.

---

## 5. Selling BTC (Create Offer)

NEO-P2P is **sell-only** — you publish an offer to sell BTC; buyers find you in the Market.

1. Tap **Create Offer** (FAB on Market, or the empty-state button).
2. Fill in:
   - **Amount (BTC)** — what you want to sell. Minimum trade is **Rp 5,000,000** equivalent; max 1 BTC.
   - **Price per BTC (IDR)** — whole rupiah only (no decimals).
   - **Valid for (TTL)** — 6h / 12h / 24h / 48h / no limit. The offer expires after this.
   - **Payment methods** — Bank (BCA, Mandiri, BNI, BRI), E-Wallet (GoPay, OVO, Dana, ShopeePay, LinkAja), or Cash meetup. For each method enter your **account number + account holder name** (or QRIS ID). These details are stored on your device and **never published to the public feed** — they are shared with the buyer over encrypted chat only after the escrow is funded.
3. Check the **Fee Breakdown**: trade amount, 0.5% seller fee, estimated network fee, total deposit.
4. **Publish Offer**. Your offer is announced to the network and appears in everyone's Market.

**Managing your own offer:**
- **Edit** — change price/amount/methods (warning if a buyer is already waiting).
- **Pause / Resume** — hide it from the market temporarily (only while OPEN, no live taker).
- **Delete** — permanent, irreversible, broadcast to all peers. Only possible while OPEN/PAUSED. A locked offer (buyer matched) cannot be deleted — finish or dispute the trade first.

**Saved payment methods:** your entered bank/QRIS/e-wallet details are saved automatically. Settings → **My Payment Methods** lets you manage them; new offers prefill from them.

---

## 6. Buying BTC

1. Browse the **Market**. Filter by min/max IDR, sort by newest or expiring soon.
2. Tap an offer → **Offer Details**: amount, price, total fiat, fee, trader reputation, payment methods.
3. Tap **Accept Offer** → confirm. Enter your **BTC receive address** (where the payout will be sent — `tb1…` on testnet).
4. The offer locks (MATCHED). You land in the **Trade Room** — the hub for this trade with Escrow and Chat tabs.

**What happens next (buyer's view):**
- The seller creates the escrow and deposits BTC. You wait (you do **not** send BTC — your payment is a bank transfer).
- Once funded, the seller's bank details arrive automatically over encrypted chat.
- You transfer the IDR, then send a payment receipt (see §8).

---

## 7. The Escrow Flow (how money stays safe)

The escrow is a **2-of-3 multisig on the blockchain**. Statuses:

```
FUNDING → FUNDED → PAYMENT_PENDING → RECEIPT_SENT → CONFIRMING → RELEASED
   └→ CANCELLED (unfunded, 45 min)        └→ DISPUTED → RESOLVING → RELEASED/REFUNDED
```

### Seller's steps
1. **Fund the escrow** — send `crypto + 0.5% fee + network fee` to the escrow address.
   - **One-tap:** "Send from my wallet to escrow" — the app sends the exact amount from your wallet, auto-fills the txid, verifies on-chain. Irreversible — confirm dialog first.
   - **Manual:** copy the escrow address (Legacy `2…` or SegWit `bc1…/tb1…` — locked after funding), send from any wallet, paste the txid, tap **Verify Deposit On-Chain**.
   - Funding is verified on-chain (default 1 confirmation). If you deposit **more** than required, the excess is returned to you on payout/refund. If you deposit **less**, the partial deposit is recorded — cancel & refund it, then create a fresh escrow (top-ups are not supported).
2. **Share payment details** — after funding, the chat unlocks. Tap **Share payment details** in the chat to send your bank number + holder name as an encrypted card.
3. **Wait for the buyer's payment + receipt.**
4. **Confirm "IDR received"** — this is the **ONLY release gate**. When the money is really in your account, tap **IDR Received — Release**. The pre-signed payout broadcasts: full BTC → buyer, 0.5% → fee wallet.
   - **Reject receipt** ("Tolak Bukti") — if the amount/name is wrong or nothing arrived, send a rejection with a reason (wrong amount / name mismatch / not received / other). Advisory only — funds stay locked, status does not change.

### Buyer's steps
1. Wait for funding (you'll be notified).
2. **Pay exactly the displayed amount** — the last 3 digits are a **unique code (kode unik)** for this trade. A different amount will NOT be recognized by the seller.
   - Use only the payment methods the seller shared. Transfer from an account in **your own name**.
   - Do NOT write "crypto", "BTC", or "NEO" in the transfer note.
   - BI-FAST has per-bank limits — large amounts may need RTGS.
3. Tap **I've Sent the Payment** (marks PAYMENT_PENDING).
4. **Send Payment Receipt** — reference code (auto-generated, share it with the seller) + screenshot of the transfer. This moves the trade to RECEIPT_SENT.
5. Wait for the seller to confirm. If the seller rejects, read the reason, fix it, resend.

### Timeouts (automatic safety)
| Situation | What happens |
|-----------|--------------|
| Escrow not funded within **45 min** (warning at 30) | Auto-cancelled |
| Funded but stalled **12 h + 48 h grace** | Auto-refund to seller (reminder at 12 h) |
| Buyer paid but seller doesn't confirm within **24 h + 12 h grace** | Auto-**dispute** — never silently refunded |

### Trade Room
The post-accept hub shows: status header, role-adaptive next-action shortcut (Fund → Pay → Confirm → Release), escrow details, and chat. The Trades tab re-enters it for in-flight trades.

---

## 8. Chat

- **E2EE:** every message is encrypted end-to-end. Keys derive from your seed; peer keys are exchanged via a pre-key handshake over LXMF.
- **Locked until funded:** chat input unlocks only after the escrow is FUNDED (bank details are never sent before money is locked).
- **Fingerprint (TOFU):** an **8-word BIP-39 fingerprint** of your peer shows in the chat top bar and escrow header. Copy it and **compare out-of-band** (WhatsApp, phone call) before large trades — this is how you detect a man-in-the-middle.
- **Offline queue:** if the peer is offline, messages queue and send automatically when they reconnect.
- **Attachments:** send files (e.g. receipts) as encrypted attachments.
- **Ratings:** after a completed trade (RELEASED/REFUNDED), a "Rate your counterparty" dialog appears once. Your signed rating is sent to the counterparty over encrypted LXMF and feeds both sides' reputation scores.

---

## 9. Disputes & Arbitration

If something goes wrong — seller never confirms, buyer never pays, fake receipt — **open a dispute**:

1. Escrow screen → **Open Dispute** (available from FUNDING / PAYMENT_PENDING / RECEIPT_SENT for the buyer; seller can dispute too).
2. Funds stay **frozen on-chain**. Do NOT send another transfer.
3. **Submit evidence** — bank receipt screenshot + description (bank name, amount, reference). The receipt reference pre-fills automatically.
4. The arbitrator (a third key holder) reviews the evidence and signs a resolution: **Release to Buyer** or **Refund to Seller**. The winning party broadcasts it (2-of-3 complete).
5. The arbitrator's decision is **binding** — evidence is the only thing that matters.

**Arbitrator Mode** (Settings → Dispute Feed) unlocks only for the designated arbitrator identity. Disputes arrive over LXMF and persist in the dispute feed.

---

## 10. Wallet

- **Receive** — QR + address (Legacy `1…/m…` or SegWit `bc1…/tb1…`). Copy or scan.
- **Send** — destination address (paste or scan QR), amount in BTC, estimated network fee shown before confirm. UTXOs selected automatically.
- **Balance** — confirmed + unconfirmed, plus **Locked in escrow** (funds you can't touch until the trade finishes).
- **History** — confirmed/pending, received/sent/self.

---

## 11. Inviting Peers

Market → **Invite Peer**:
- **Show QR** — the other person scans it to add you.
- **Scan** — point at their invite QR.
- **Paste** — invite link (`neop2p://peer/<id>`) or raw peer ID.
- Invite links also work as **system deep links** — tap a `neop2p://` link in any app to add the peer.

---

## 12. Settings

| Section | What you can do |
|---------|-----------------|
| **RNS Transport Node** | See connection status; add/remove **extra transport nodes** (host:port). More nodes = more reach, never less security — every node is just a packet ferry. |
| **Language** | Follow device / Bahasa Indonesia / English (applies after restart). |
| **Enable Notifications (This Phone)** | OEM-specific steps (Xiaomi, Samsung, OPPO, Vivo, Huawei) so the phone doesn't kill the P2P service. **Do this** — otherwise you'll miss payments and offers. |
| **My Payment Methods** | Manage saved bank/QRIS/e-wallet details. |
| **Reported Traders** | Local-only reports (scam / harassment / fake receipt / other). Never leaves your device, never changes trade state. |
| **Blocked Traders** | Hide offers from specific peers (device-only). |
| **View Recovery Phrase** | Re-check your seed (device unlock required). Never share it. |
| **Danger Zone — Destroy Local Trade Data** | Deletes every offer, trade, chat, saved method on THIS device. Identity and seed are KEPT; on-chain funds stay safe. Type **HAPUS** to confirm. |
| **Danger Zone — Reset Identity** | Permanently destroys your keypair. You lose access to active escrows. Irreversible. |

---

## 13. Fees (transparent, no server)

- **0.5% of the trade, paid by the seller only.** The buyer pays nothing and receives the full BTC amount.
- The fee wallet address is **hardcoded in the open-source app** — verify it in the code before trusting any build.
- Network (miner) fees are estimated dynamically and shown before you confirm any transaction.

---

## 14. Troubleshooting

| Problem | Fix |
|---------|-----|
| "P2P transport is offline" | Check internet. Tap Retry. The app reconnects automatically every 60 s. A transport-down banner + notification also appear when the node is unreachable. |
| "Identity locked" notification | Unlock your device screen — the app resumes P2P automatically. |
| No offers visible | Pull to refresh (re-announces your feed). Offers are ephemeral — a peer who joined before an offer was announced needs a refresh. |
| Peer unreachable / messages queued | Peer is offline. Messages queue and deliver when they return (LXMF store-and-forward). |
| Missed notifications | Settings → Enable Notifications (This Phone) and follow the OEM steps. |
| Slow/relayed connection | You're connected via the transport node. Funds stay safe on-chain; trade sync may lag. |
| "Offer taken" when accepting | Another buyer grabbed it first. Pick another offer. |
| Can't delete my offer | It's locked (buyer matched or escrow live). Finish or dispute the trade first. |
| Wrong amount on payment | The last 3 digits are the unique code — transfer the EXACT total shown. |

---

## 15. Security Checklist (read this)

1. **Seed phrase:** paper, offline, never typed into any website or app. NEO-P2P never asks for it.
2. **Fingerprint:** compare the 8-word peer fingerprint out-of-band before releasing or paying large amounts.
3. **Release only when money is in YOUR account** — a screenshot is not money. Fake receipts are the #1 scam.
4. **Own-name transfers only** — transfers from third-party accounts are a red flag and hard to prove.
5. **No crypto words in transfer notes** — banks freeze accounts for crypto-related transfers.
6. **Dispute early, not late** — if the counterparty stalls, open a dispute while evidence is fresh.
7. **This is testnet** — no real value. Treat it as a test of the flow, not an investment.

---

## 16. Known Limitations

- E2EE is custom (NIP-44-inspired) — interoperable only between NEO-P2P peers, no forward secrecy, TOFU key trust (mitigated by fingerprints).
- Market price is a static default — no live BTC/IDR feed yet.
- The transport node is a single point of failure for internet peers (mitigated by LAN discovery + extra nodes).

---

*NEO-P2P is experimental software provided "as is". Crypto trading carries financial risk. Use at your own risk.*
