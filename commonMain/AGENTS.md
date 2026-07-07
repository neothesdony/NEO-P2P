# KMP Shared Module — commonMain

## Purpose

Kotlin Multiplatform shared source set containing 100% cross-platform business logic, domain models, repository interfaces, use cases, state management (ViewModels), Ktor networking setup, and dependency injection modules. This is the core of the NEO-P2P application — all platform-specific code depends on contracts defined here.

## Ownership

- **Owner:** KMP shared team
- **Scope:** All files under `commonMain/` — domain, data, state, networking, DI

## Local Contracts

- **Domain Layer** (`domain/`):
  - `model/` — Shared data models (`User`, `Offer`, `Escrow`, `Identity`)
  - `repository/` — Repository interfaces (`UserRepository`, `OfferRepository`, `IdentityRepository`)
  - `usecase/` — Business logic use cases (`GetUserProfileUseCase`, `GetActiveOffersUseCase`)
- **Data Layer** (`data/`):
  - `repository/` — Repository implementations (`UserRepositoryImpl`, `OfferRepositoryImpl`, `IdentityRepositoryImpl`)
  - `identity/IdentityDataSource.kt` — Identity data source interface
  - `remote/OfferRemoteDataSource.kt` — Remote data source interface
- **State** (`state/`):
  - ViewModels exposing `StateFlow` for UI consumption (`OnboardingViewModel`, `HomeViewModel`)
- **Networking** (`networking/`):
  - `KtorClient.kt` — Platform-agnostic Ktor HttpClient setup with JSON serialization
- **DI** (`di/`):
  - `SharedModule.kt` — Koin shared DI module

## Work Guidance

- All code must target JVM + iOS (no Android-only APIs)
- Use `kotlinx-coroutines-core` for async, `kotlinx-serialization-json` for serialization
- Koin for DI (not Hilt — Hilt is Android-only)
- Repository pattern with interfaces in domain, implementations in data
- UI state via `StateFlow` in ViewModels — platforms observe reactively
- Platform-specific implementations marked with `expect`/`actual` keyword pattern

## Verification

- Compile check: `./gradlew :compileKotlinMetadata` from project root
- No platform-specific imports allowed (enforced at compile time by KMP)

## Child DOX Index

*No children — leaf module.*
