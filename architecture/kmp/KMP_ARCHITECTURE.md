# Kotlin Multiplatform Architecture for NEO-P2P

## Project Structure

The NEO-P2P Kotlin Multiplatform project will be structured with three main source sets:

```
neo-p2p/
├── commonMain/             # Shared Kotlin code (JVM, Android, iOS)
│   ├── di/                 # Dependency injection modules (Koin)
│   ├── domain/             # Business logic, use cases, repositories interfaces
│   │   ├── model/          # Data models (shared)
│   │   ├── repository/     # Repository interfaces
│   │   └── usecase/        # Interactors/use cases
│   ├── data/               # Data source interfaces and mappers
│   │   ├── remote/         # Remote data source interfaces (API)
│   │   ├── local/          # Local data source interfaces (DB, prefs)
│   │   └── mapper/         # Data mappers (remote/local to domain)
│   ├── networking/         # Ktor client setup and API service interfaces
│   ├── state/              # State management (ViewModels, StateFlows)
│   └── util/               # Shared utilities, extensions, constants
├── androidMain/            # Android-specific implementations
│   ├── di/                 # Android-specific DI modules (if needed)
│   ├── data/               # Android implementations of data sources
│   │   ├── local/          # Room database, DataStore implementations
│   │   └── remote/         # Android-specific networking interceptors (if any)
│   └── ui/                 # Android UI layer (Jetpack Compose)
│       ├── screens/        # Compose screens
│       ├── theme/          # Material 3 theme
│       └── navigation/     # NavGraph and navigation helpers
├── iosMain/                # iOS-specific implementations
│   ├── data/               # iOS implementations of data sources
│   │   ├── local/          # SQLite/UserDefaults/Keychain implementations
│   │   └── remote/         # iOS-specific networking (if any)
│   └── ui/                 # iOS UI layer (SwiftUI)
│       ├── screens/        # SwiftUI views
│       ├── theme/          # Cupertino/HIG styling
│       └── navigation/     # SwiftUI navigation
└── build.gradle.kts        # Root Gradle configuration
```

## Dependency Injection

**Approach: Koin for KMP**
- Why Koin over Hilt? Hilt is Android-only. Koin supports multiplatform (Android, iOS, JVM) and provides a lightweight, declarative DI container.
- CommonMain: Define shared modules (interfaces, use cases, repositories) in `commonMain/di/`.
- AndroidMain: Android-specific bindings (e.g., Application context, Android-specific libraries) in `androidMain/di/`.
- iOSMain: iOS-specific bindings (e.g., UIKit/SwiftUI environment) in `iosMain/di/`.
- Usage: ViewModels and state holders in commonMain receive dependencies via constructor injection. Platform-specific UI layers obtain ViewModels via Koin's `getViewModel()` (Android) or SwiftUI property wrappers (iOS).

## State Management

**Approach: StateFlow and Flow in CommonMain**
- Business logic (use cases) return `Flow` for reactive data streams.
- ViewModels (in `commonMain/state/`) expose `StateFlow` for UI consumption.
- Android UI: Collect StateFlows with `collectAsStateWithLifecycle()` in Jetpack Compose.
- iOS UI: Use SwiftUI's `@StateObject` and `.onReceive()` or `async/await` to observe StateFlows via a common wrapper (e.g., `StateFlowOwner`).
- Benefits: Shared reactive streams, testable UI logic, separation of concerns.

## Networking

**Approach: Ktor Client with Kotlinx Serialization**
- CommonMain: Configure Ktor client (without engine) in `commonMain/networking/`.
  - Install JSON serialization feature (`kotlinx-serialization-json`).
  - Define API service interfaces (e.g., `ApiService`) with suspend functions.
- AndroidMain: Provide Android engine (`CIO` or `OkHttp`) and platform-specific interceptors (logging, auth).
- iOSMain: Provide Darwin engine (or `OkHttp` via cocoapods) and iOS-specific interceptors.
- Shared: API response models and request bodies use `@Serializable` annotations in commonMain.
- Benefits: Single networking layer, consistent API calls across platforms.

## Data Layer

**Approach: Repository Pattern with Platform-Specific Implementations**
- CommonMain: Define repository interfaces (`UserRepository`, `OfferRepository`, etc.) and data source interfaces.
- AndroidMain:
  - Local: Room database (`@Database`, `@Dao`, `@Entity`) for persistent data.
  - Local: DataStore (Proto DataStore) for typed key-value preferences.
  - Remote: Ktor client implementation of remote data sources.
- iOSMain:
  - Local: SQLite via GRDB or raw SQLite, UserDefaults for simple prefs, Keychain for secure storage.
  - Remote: Ktor client implementation (shared networking layer).
- Mappers: Convert between data source entities (Room entities, SQLite rows) and domain models in platform-specific `data/mapper/` packages.
- Benefits: Clean separation, testability, ability to optimize per-platform storage.

## UI Layer Connection to Shared Business Logic

- UI layers (androidMain/ui and iosMain/ui) are **thin observers** of shared state.
- ViewModels (in commonMain/state/) expose UI state as `StateFlow` (e.g., `uiState: StateFlow<HomeUiState>`).
- Android (Jetpack Compose):
  ```kotlin
  val viewModel: HomeViewModel = koin.get()
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  ```
- iOS (SwiftUI):
  ```swift
  class HomeViewModelWrapper: ObservableObject {
      @Published var uiState: HomeUiState = .loading
      private let viewModel: HomeViewModel
      init() {
          self.viewModel = koin.get()
          startObserving()
      }
      private func startObserving() {
          viewModel.uiState.subscribe(onCollect: { state in
              DispatchQueue.main.async { self.uiState = state }
          })
      }
  }
  ```
- UI events (button clicks, etc.) are sent to the ViewModel via functions (e.g., `viewModel.onOfferClicked(offerId)`).
- Benefits: UI remains platform-specific (Material 3 vs Cupertino), business logic is 100% shared.

## Technology Choices Justification

| Layer                | Technology          | Justification                                                                 |
|----------------------|---------------------|-------------------------------------------------------------------------------|
| **DI**               | Koin                | Multiplatform support, lightweight, easy to set up, works with Android/iOS.   |
| **State Management** | StateFlow/Flow      | Reactive, lifecycle-aware, shared across platforms, integrates with Coroutines.|
| **Networking**       | Ktor + Serialization| Multiplatform HTTP client, coroutine-based, lightweight, JSON serialization.   |
| **Data Layer**       | Room/DataStore (Android), SQLite/UserDefaults/Keychain (iOS) | Best-in-class local storage per platform; abstracted via repositories. |
| **UI**               | Jetpack Compose (Android), SwiftUI (iOS) | Modern declarative UI frameworks matching platform guidelines (Material 3, Cupertino/HIG). |
| **Overall**          | Kotlin Multiplatform| Enables 60-80% code sharing (business logic, networking, state, DI) while allowing platform-specific UI and deep integrations. |

## Folder Structure Explanation

- **commonMain**: Contains all shareable code. This is where the core application logic lives: use cases, repositories, ViewModels, networking setup, data models, and DI modules. Any platform-independent code should reside here.
- **androidMain**: Implements platform-specific contracts defined in commonMain. Includes Android-specific DI modules, Room database implementations, DataStore, and the Jetpack Compose UI layer. Also includes the AndroidManifest and Gradle configurations for Android.
- **iosMain**: Implements platform-specific contracts for iOS. Includes SQLite/UserDefaults/Keychain implementations, and the SwiftUI UI layer. Also includes the iOS project setup (Info.plist, etc.).
- **build.gradle.kts**: Configures the Kotlin Multiplatform plugin, sets up targets (android, ios), and defines shared dependencies.

## Mermaid Diagram

```mermaid
graph TD
    A[Shared commonMain] --> B[DI Layer: Koin Modules]
    A --> C[Domain Layer: Use Cases, Repository Interfaces]
    A --> D[Data Layer: Remote/Local Data Source Interfaces, Mappers]
    A --> E[Networking: Ktor Client, API Services]
    A --> F[State Management: ViewModels, StateFlows]
    A --> G[Data Models: Shared Kotlin/Serializable Classes]

    H[Android Main] --> I[Android DI: Koin Android Modules]
    H --> J[Android Data: Room Database, DataStore]
    H --> K[Android UI: Jetpack Compose Screens]
    H --> L[Android Networking: Ktor Engine (OkHttp/CIO)]

    M[iOS Main] --> N[iOS DI: Koin iOS Modules]
    M --> O[iOS Data: SQLite/UserDefaults/Keychain]
    M --> P[iOS UI: SwiftUI Views]
    M --> Q[iOS Networking: Ktor Engine (Darwin/OkHttp)]

    I --> B
    J --> D
    K --> F
    L --> E
    N --> B
    O --> D
    P --> F
    Q --> E

    style A fill:#e3f2fd,stroke:#1565c0,stroke-width:2px
    style H fill:#fff3e0,stroke:#ef6c00,stroke-width:2px
    style M fill:#f3e5f5,stroke:#6a1b9a,stroke-width:2px
```

**END OF AGENT OUTPUT**