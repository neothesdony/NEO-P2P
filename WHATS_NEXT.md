# What's Next

## Immediate Next Steps

### If You're a Developer

| Task | Skill Level | Time |
|------|-------------|------|
| Set up relays on Oracle Cloud Free Tier | Intermediate | 1 hour |
| Build and install the Android app | Beginner | 15 min |
| Write integration tests for escrow flow | Intermediate | 1 week |
| Implement LDK real transaction signing | Advanced | 2 weeks |

### If You're a User

- **The app is live for testing** — v1.0.27 (RNS/LXMF transport, on-chain escrow, E2EE chat, trade hub, reputation over LXMF)
- **Configure your own relay / transport node** — self-host for maximum privacy (see `infrastructure/`)
- **Verify the fee wallet address** — it's in `NeoP2PConfig.kt`, change it
- **Join the community** — (link TBD)

## Key Missing Pieces

### 🔴 Critical (Blocking v2.0)

1. **Signal Protocol E2EE** — DONE 2026-08-24: custom NIP-44-inspired scheme live (X25519 ECDH + HKDF + ChaCha20-Poly1305), two-shot pre-key handshake over the relay, sessions persist in SQLCipher, decrypt truncation fixed, history loads. Remaining gaps: no forward secrecy (static-static ECDH), TOFU key trust.

### 🟡 Important (v1.3-v2.0)

2. ~~**WebRTC real ICE exchange**~~ — **REMOVED 2026-08-31 (Phase 4)** — WebRTC/libp2p/Nostr/ws-relay are gone; RNS + LXMF is the only transport. File attachments (payment proofs, evidence images) travel as LXMF file attachments (auto-Resource for >319B).
3. **Wallet send live test** — wallet page is live (receive QR, balance, history, send form), and the escrow auto-fund flow ("Send from my wallet to escrow") now drives an outbound `WalletService.send` + on-chain verification. A fully-confirmed outbound broadcast on Testnet4 still needs to be observed end-to-end.
4. **Live market price feed** — Create Offer defaults to a static placeholder (`DEFAULT_BTC_MARKET_PRICE_IDR`); a live BTC/IDR feed is not wired up.
5. ~~**Relay DNS**~~ — **DONE 2026-08-31 (Phase 4)** — `relay1.custom-minipc.com` resolves to the VPS transport node (port 42420).
6. ~~**Bahasa Indonesia localization**~~ — **DONE 2026-08-28**: full `values-in/strings.xml` parity (697 EN = 697 ID, script-checked), including all notification copy, onboarding errors, escrow pay instructions, and the OEM notification help screen. **DONE 2026-08-28 (batch 2)**: per-app ID/EN language toggle in Settings (manual Configuration override, applies on restart).
7. **Tests** — 375 unit tests green (escrow signing, funding binding + over/underpayment, two-taker claim gate, receipt flow + reject payload, saved payment methods, peer fingerprint, error codes, timeout sweep, format utils, RNS session, attestation codec, two/three-JVM harness, load + soak, trade-hub state, dispute redelivery gate). More integration coverage still welcome (payment-detail sharing, auto-fund broadcast ack).

### 🟢 Nice to Have (v2.1+)

8. **Tor integration** — Settings has toggle but no proxy wiring.
9. **Multi-asset support** — USDT/ETH escrow contracts.
10. **UI animations** — Compose screens are functional but static.

## Architecture Decisions to Review

Before building v1.1, consider these open questions:

1. **Dispute resolution** — 2-of-3 arbitration is live (LXMF `dispute`/`evidence`/`resolution` signaling — the kind:33386/33387/33388 Nostr events were replaced by LXMF DIRECT in Phase 4). The old 7-day timelock claim was removed from code and copy — the payout is a plain 2-of-3 spend. Review whether the 24h+12h payment window / 12h+48h refund grace is right for Indonesia (too short = fraud risk, too long = capital locked).

2. **Reputation portability** — Signed attestations are exchanged with the counterparty over LXMF since 2026-09-04 (sender-authenticated, BIP-340 verified, persisted + deduped). Still no gossip layer — a new peer has no reputation history until you trade with them. Consider a future gossip/portability phase.

3. **Fee wallet rotation** — Hardcoded address is transparent but inflexible. Should we support fee address rotation via signed announcements over the RNS transport node?

## Quick Commands

```bash
# Build
cd android && ./gradlew assembleDebug

# Check file tree
find neo-p2p -type f | wc -l

# Deploy the RNS infrastructure (requires VPS)
bash neo-p2p/infrastructure/scripts/deploy.sh your-domain.com

# Check service status
bash neo-p2p/infrastructure/scripts/status.sh

# Transport-node logs (look for "Listening on 0.0.0.0:42000" + "Registered interface: VPS TCP Server/client-N")
sudo docker compose -f docker-compose.amd64.yml logs rns-transport --tail 30

# Create release tag
git tag v1.0.0-alpha && git push origin v1.0.0-alpha
```

## Need Help?

Check these files first:
- `SKILL.md` — the NEO-P2P full-stack skill has architecture docs
- `ROADMAP.md` — the big picture
- `INFRASTRUCTURE.md` — (pending) relay deployment guide

Or just open an issue.
