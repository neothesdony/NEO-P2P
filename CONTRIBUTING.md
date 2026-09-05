# Contributing to NEO-P2P

First off, thanks for taking the time to contribute! 🎉

## Code of Conduct

- Be respectful and inclusive
- Focus on what's best for the community
- Show empathy towards other community members

## How Can I Contribute?

### Reporting Bugs

1. Check if the bug has already been reported in Issues
2. Use the Bug Report template
3. Include:
   - Android version and device
   - Network carrier (Telkomsel, Indosat, XL, Tri)
   - Steps to reproduce
   - Expected vs actual behavior
   - Screenshots (if applicable)

### Suggesting Features

1. Check if the feature is already on the [Roadmap](ROADMAP.md)
2. Create a feature request issue
3. Explain:
   - What problem does this solve?
   - How should it work?
   - Why is it important for P2P trading?

### Code Contributions

#### Prerequisites
- Android Studio Hedgehog (2024.3.1+) or IntelliJ IDEA
- JDK 17+
- Android SDK 34+

#### Development Setup

```bash
# Clone
git clone https://code.neop2p.io/thesdony/neo-p2p.git
cd neo-p2p

# Build
cd android
./gradlew assembleDebug

# Install on device
./gradlew installDebug
```

#### Coding Standards

- **Kotlin**: Follow official [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html)
- **Compose**: Use Material 3 components, prefer `Modifier` chains
- **Architecture**: MVVM + Clean Architecture (data/domain/ui layers)
- **DI**: Dagger Hilt for all dependency injection
- **Naming**: `FeatureNameScreen`, `FeatureNameViewModel`
- **Imports**: No wildcard imports, organize by layer (Android → Compose → Project)

#### Git Workflow

```
main        ← Production-ready
  └─ develop ← Integration branch
       └─ feature/your-feature
       └─ fix/your-bugfix
```

1. Branch from `develop`
2. Commit messages: `type(scope): description`
   - `feat(escrow): add 2-of-3 multisig generation`
   - `fix(chat): decrypt crash on null session`
   - `docs(readme): update NAT traversal section`
3. PR to `develop`
4. Squash merge on approval

#### Testing

- Write tests for ViewModels and UseCases
- Integration tests for critical flows (escrow, chat)
- UI tests for screens using Compose Test Rule

```bash
# Run all tests
./gradlew test

# Run instrumented tests
./gradlew connectedAndroidTest
```

## Infrastructure Contributions

### Adding an RNS Transport Node

1. Deploy the `rns-transport` service (see `infrastructure/AGENTS.md` and `INFRASTRUCTURE.md`) on a VPS
2. Open port 42420 in the cloud firewall
3. Share `host:port` with users — they add it in Settings (transport nodes, live-apply)
4. Ensure the node config sets `announce_rate_target=1`, `announce_rate_grace=20`, `announce_rate_penalty=0` on every interface (REQUIRED — the Python rnsd default blocks app destinations)

> **Note**: The transport node is a packet ferry, not a trust anchor — traffic stays end-to-end encrypted and announces are signed, so more nodes = more reach, never less security.

### Adding a Fiat Method

1. Add entry to `FiatMethod` enum in `NeoP2PConfig.kt`
2. Add icon mapping in UI screens
3. Update `FiatMethod.fromId()` if needed

## Security

**Do not** commit real API keys, wallet addresses, or private keys.

- Fee wallet address should be changed before your own build
- All secrets go in `gradle.properties` or env vars
- Report security vulnerabilities confidentially via issues

## Questions?

Open a discussion issue or join our community (link TBD).

---

*This project exists thanks to all the people who contribute.*
