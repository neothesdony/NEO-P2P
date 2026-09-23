# Security Policy

NEO-P2P handles keys, on-chain funds, and private trade communications. We take
security reports seriously and appreciate responsible disclosure.

> **Do not open a public issue for a security vulnerability.** Follow the private
> reporting process below.

## Supported Versions

NEO-P2P is experimental, pre-1.0 software. Only the latest `main` build is
supported with security fixes.

| Version | Supported |
|---------|-----------|
| `main` (latest) | :white_check_mark: |
| `v0.1.1` | :white_check_mark: |
| `v0.1.0` | :x: |
| Older betas / tags | :x: |

## Reporting a Vulnerability

Please report vulnerabilities **privately**:

- If the GitHub repository provides private vulnerability reporting
  (**Security** tab → **Report a vulnerability**), use it.
- Otherwise, open a minimal public issue that only requests a private channel —
  **do not include exploit details, keys, or reproduction steps** — and a
  maintainer will follow up privately.

Include as much of the following as you can:

- Affected version / commit and the network (mainnet or testnet)
- A clear description of the issue and its security impact
- Reproduction steps or a proof of concept (Do **not** test against other
  people's escrows, wallets, or identities.)
- Whether the issue is already public

## What to Expect

- **Acknowledgement** within 72 hours.
- **Initial assessment** (severity, affected versions, whether it is in scope)
  within 7 days.
- A fix or mitigation plan for confirmed issues, coordinated with you on a
  disclosure timeline.
- Credit in the fix commit / release notes unless you prefer to stay anonymous.

We will not take legal action against researchers who act in good faith under
this policy: test only against your own identity, wallet, and escrows; do not
access, modify, or destroy other users' data or funds; and give us reasonable
time to fix before public disclosure.

## Scope

**In scope**

- Identity / key derivation (BIP-39 / BIP-32, AndroidKeyStore seed protection)
- On-chain escrow: 2-of-3 P2SH/P2WSH construction, funding verification,
  payout/refund destination gating, resolution application
- Transaction signing and the bitcoinj integration (`CVE-2026-44714` class
  script-verification issues)
- Chat E2EE (E2EE v2 double ratchet: X3DH over v2 pre-key bundles, X25519
  chain/DH ratchet steps, AAD-bound ChaCha20-Poly1305) and the pre-key
  handshake
- Arbitration ingest / resolution authentication and destination gates
- RNS/LXMF transport handling (announce parsing, deferred digests, resend
  queue, sender authentication)
- Local data protection (SQLCipher DB, encrypted preferences, backup /
  device-transfer exclusions)

**Out of scope**

- Third-party services (the chain-data explorers — Mempool.space,
  Blockstream.info, mempool.emzy.de, btcscan.org, blockchain.com — the
  CoinGecko/CoinPaprika price feeds, the GitHub host, app stores)
- The RNS/LXMF protocol or the upstream Reticulum/LXMF implementations
  (report those upstream to [markqvist/Reticulum](https://github.com/markqvist/Reticulum))
- Social engineering, physical device access, or a rooted/compromised device
- Denial of service against the public transport node
- Issues requiring a debug build, an unlocked bootloader, or a modified APK
- The known, accepted limitations of the current design — the lack of Tor and
  post-quantum support, and TOFU key trust (narrowed by a verified RNS identity
  binding: chat sessions require one, must match the invite's identity hash when
  present, and pin the first verified identity so a later change is refused).
  Chat is E2EE v2 (double ratchet) with forward secrecy and post-compromise
  security, but it is not NIP-44/59 wire-compatible — interop only between
  NEO-P2P peers on v0.1.1+.

## Security Design

NEO-P2P's security rests on client-side BIP-39/BIP-32 key derivation with an
AndroidKeyStore-protected seed, an E2EE v2 chat double ratchet (X3DH + X25519
chain/DH steps + AAD-bound ChaCha20-Poly1305), an on-chain 2-of-3 P2SH/P2WSH
escrow (with an optional CLTV timelock and payout/refund destination gating),
and SQLCipher-encrypted local storage.

## Verifying a Build

NEO-P2P is open source — verify what you run:

- The fee wallet address, arbitrator public key/peer id, and RNS transport node
  host/port are hardcoded constants in `NeoP2PConfig.kt` (the `:core` module,
  `android/core/src/main/kotlin/com/neop2p/NeoP2PConfig.kt`).
- The fee wallet address is signature-protected; any fork that changes it cannot
  create escrow.
- Run the build and test suite yourself (see [`CONTRIBUTING.md`](CONTRIBUTING.md)):

  ```bash
  cd android
  ./gradlew :app:assembleDebug
  ./gradlew :app:testDebugUnitTest
  ```
