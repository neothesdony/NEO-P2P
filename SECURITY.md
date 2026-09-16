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
| `v0.1.0-beta-7` | :white_check_mark: |
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
- Chat E2EE (X25519 ECDH + HKDF-SHA256 + ChaCha20-Poly1305) and the pre-key
  handshake
- Arbitration ingest / resolution authentication and destination gates
- RNS/LXMF transport handling (announce parsing, deferred digests, resend
  queue, sender authentication)
- Local data protection (SQLCipher DB, encrypted preferences, backup /
  device-transfer exclusions)

**Out of scope**

- Third-party services (Mempool.space / Blockstream.info explorers, the GitHub
  host, app stores)
- The RNS/LXMF protocol or the upstream Reticulum/LXMF implementations
  (report those upstream to [markqvist/Reticulum](https://github.com/markqvist/Reticulum))
- Social engineering, physical device access, or a rooted/compromised device
- Denial of service against the public transport node
- Issues requiring a debug build, an unlocked bootloader, or a modified APK
- The known, accepted limitations documented in
  [`docs/SECURITY_POSTURE.md`](docs/SECURITY_POSTURE.md), including: no forward
  secrecy in the custom chat scheme, TOFU peer-key trust, and the lack of
  Tor/post-quantum support

## Security Design

The threat model, key hierarchy, E2EE scheme, escrow gates, and accepted
limitations are documented in
[`docs/SECURITY_POSTURE.md`](docs/SECURITY_POSTURE.md). Hard-earned engineering
history and past fixes are recorded in [`CRITICAL.md`](CRITICAL.md).

## Verifying a Build

NEO-P2P is open source — verify what you run:

- The fee wallet address, arbitrator public key/peer id, and RNS transport node
  host/port are hardcoded constants in `NeoP2PConfig.kt`.
- The fee wallet address is signature-protected; any fork that changes it cannot
  create escrow.
- Run the build and test suite yourself (see [`CONTRIBUTING.md`](CONTRIBUTING.md)):

  ```bash
  cd android
  ./gradlew :app:assembleDebug
  ./gradlew :app:testDebugUnitTest
  ```
