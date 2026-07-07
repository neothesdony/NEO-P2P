# KMP iOS Platform Source Set — iosMain

## Purpose

iOS-specific implementations of KMP `expect`/`actual` declarations from `commonMain`. Provides the Ktor Darwin engine and platform-specific HTTP client for iOS.

## Ownership

- **Owner:** KMP iOS team
- **Scope:** All files under `iosMain/`

## Local Contracts

- **Networking:**
  - `networking/IosHttpClient.kt` — iOS HTTP client configuration using Ktor Darwin engine

## Work Guidance

- Ktor engine: Darwin on iOS (wraps URLSession)
- This is a minimal platform source set — the full iOS client lives in `iosApp/`
- Must match all `expect` declarations from `commonMain/`

## Verification

- Compile check: `./gradlew :iosMain:compileKotlinIosArm64` from project root (requires macOS with Xcode)

## Child DOX Index

*No children — leaf module.*
