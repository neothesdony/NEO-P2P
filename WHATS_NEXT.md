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

- **Wait for v2.0 production release** — v1.0-alpha has placeholder cryptography
- **Configure your own relays** — self-host for maximum privacy
- **Verify the fee wallet address** — it's in `NeoP2PConfig.kt`, change it
- **Join the community** — (link TBD)

## Key Missing Pieces

### 🔴 Critical (Blocking v2.0)

1. **LDK Lightning integration** — EscrowService creates multisig addresses and pre-signed transactions using placeholder bytes. Need actual LDK SDK: open channel, build payout tx, broadcast.
   - File: `EscrowService.kt`
   - Library: `org.ldk:ldk-android:0.1.0`

2. **Nostr NIP-01 signing** — Events are published with placeholder `"id"` and `"sig"` fields. Need secp256k1 Schnorr signing.
   - File: `NostrClient.kt`
   - Library: `fr.acinq.secp256k1:secp256k1-kmp-jni-android:0.6.0`

3. **BIP-39 full derivation** — Seed phrase generation uses a simplified mapping. Need BIP-39 standard with checksum.
   - File: `IdentityManager.kt`
   - Library: `io.github.novacrypto:BIP39:2024.1.0`

### 🟡 Important (v1.3-v2.0)

4. **WebRTC real ICE exchange** — `WebRTCManager.kt` creates PeerConnection but uses placeholder offer/answer. Need signaling via libp2p.
5. **Bahasa Indonesia localization** — All UI strings are English. Need `values-in/strings.xml`.
6. **Tests** — Zero tests. Critical for escrow and chat reliability.

### 🟢 Nice to Have (v2.1+)

7. **Tor integration** — Settings has toggle but no proxy wiring.
8. **Multi-asset support** — USDT/ETH escrow contracts.
9. **UI animations** — Compose screens are functional but static.

## Architecture Decisions to Review

Before building v1.1, consider these open questions:

1. **LDK vs manual PSBT** — Should we use LDK's full node or just PSBT creation for escrow? LDK is heavier but handles Lightning automatically. Manual PSBT is lighter but needs custom monitoring.

2. **Dispute resolution** — Current implementation has a 7-day timelock. Is 7 days right for Indonesia? Too short = fraud risk, too long = capital locked.

3. **Reputation portability** — Signed attestations on Nostr are good, but should we support NIP-58 badges for cross-app reputation?

4. **Fee wallet rotation** — Hardcoded address is transparent but inflexible. Should we support fee address rotation via Nostr events signed by a master key?

## Quick Commands

```bash
# Build
cd android && ./gradlew assembleDebug

# Check file tree
find neo-p2p -type f | wc -l

# Deploy relays (requires Oracle Cloud)
bash neo-p2p/infrastructure/scripts/deploy.sh your-domain.com

# Check relay status
bash neo-p2p/infrastructure/scripts/status.sh

# Create release tag
git tag v1.0.0-alpha && git push origin v1.0.0-alpha
```

## Need Help?

Check these files first:
- `SKILL.md` — the NEO-P2P full-stack skill has architecture docs
- `ROADMAP.md` — the big picture
- `INFRASTRUCTURE.md` — (pending) relay deployment guide

Or just open an issue.
