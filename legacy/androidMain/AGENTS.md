# KMP Android Platform Source Set — androidMain

## Purpose

Android-specific implementations of KMP `expect`/`actual` declarations from `commonMain`. Bridges shared business logic to the Android platform: provides the Ktor OkHttp engine, Android UI theme (Material 3), Android-specific DI modules, and platform data source implementations.

## Ownership

- **Owner:** KMP Android team
- **Scope:** All files under `androidMain/`

## Local Contracts

- **UI:**
  - `java/com/neop2p/ui/theme/Theme.kt` — Material 3 theme configuration
  - `java/com/neop2p/ui/onboarding/` — Onboarding screen (if KMP-routed)
  - `java/com/neop2p/ui/home/` — Home screen (if KMP-routed)
- **Data:**
  - `data/identity/IdentityDataSourceActual.kt` — Android `actual` implementation of identity data source
- **Networking:**
  - `java/com/neop2p/networking/KtorClientActual.kt` — Android Ktor engine actual
  - `networking/AndroidHttpClient.kt` — Android HTTP client configuration
- **Entry:**
  - `java/com/neop2p/MainActivity.kt` — Android entry point
- **DI:**
  - `java/com/neop2p/di/AndroidModule.kt` — Android-specific Koin module

## Work Guidance

- This is the older KMP pattern (pre-Compose Multiplatform). The full Android UI is in `android/` module
- Ktor engine: OkHttp on Android
- Must match all `expect` declarations from `commonMain/`

## Verification

- Compile check: `./gradlew :androidMain:compileDebugKotlinAndroid` from project root

## Child DOX Index

*No children — leaf module.*
