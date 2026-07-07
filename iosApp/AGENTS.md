# iOS SwiftUI App — iosApp

## Purpose

Standalone iOS peer-to-peer crypto trading client built with SwiftUI. Uses Koin for dependency injection (via koin-spm), Firebase for analytics/auth, and Alamofire for networking. Includes platform-native managers for biometric authentication, Keychain secure storage, Tor connectivity, and WebRTC communication.

## Ownership

- **Owner:** iOS team
- **Scope:** `iosApp/` directory — all Swift files under `Sources/iosApp/`, package config in `Package.swift`

## Local Contracts

- **Entry:** `NeoP2PApp.swift` — `@main` SwiftUI app with Firebase init
- **Screens** (`Screens/`):
  - `HomeScreen.swift`, `OnboardingScreen.swift`, `CreateOfferScreen.swift`
  - `OfferDetailScreen.swift`, `ChatScreen.swift`, `EscrowScreen.swift`
  - `ProfileScreen.swift`, `SettingsScreen.swift`
- **ViewModels** (`ViewModels/`):
  - `HomeViewModel.swift`, `SettingsViewModel.swift`
- **Managers** (`Managers/`):
  - `BiometricAuthenticator.swift` — Face ID / Touch ID
  - `KeychainSecureStorage.swift` — Keychain wrapper for secure credential storage
  - `TorManager.swift` — Tor proxy for anonymous networking
  - `WebRTCManager.swift` — WebRTC data channels
- **Navigation:** Tab-based with 5 tabs (Home, Profile, Escrow, Chat, Settings) via `TabView`

## Work Guidance

- Targets iOS 17+, Swift 5.9, SwiftUI
- Koin-SPM for DI (not Hilt)
- Firebase Analytics + Auth for backend services
- Alamofire for HTTP networking
- Platform-idiomatic SwiftUI with native navigation patterns
- Follows Apple Human Interface Guidelines (Cupertino style)

## Verification

- Build: `xcodebuild -scheme iosApp -destination 'platform=iOS Simulator,name=iPhone 15' build` from `iosApp/`
- Requires Xcode on macOS

## Child DOX Index

*No children — leaf module.*
