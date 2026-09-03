# NEO-P2P Cross-Platform Architecture Diagrams

> **Status (2026-09-02): STALE — scaffold-era KMM diagrams.** The iOS platform + Kotlin Multiplatform shared module describe the **dead `legacy/` KMM code** (not wired into any build — see `AGENTS.md`). The live app is **Android-only** (Compose + Hilt + Room) with **RNS + LXMF as the only transport** (libp2p/Nostr/WebRTC removed in Phase 4, 2026-08-31). See `ARCHITECTURE_OVERVIEW.md` for the live architecture. Retained for historical reference.

D2 diagrams source: `ARCHITECTURE_DIAGRAMS.d2`

Render with:
```bash
d2 ARCHITECTURE_DIAGRAMS.d2 output.svg
```

---

## Layer Explanation

```d2
direction: right

Android Platform: "" {
  AUI: "Jetpack Compose UI / Material 3"
  AViewModel: "Shared ViewModel (AndroidX Lifecycle)"
  AStateFlow: "StateFlow/Flow / State Observers"
  AUI -> AViewModel
  AViewModel -> AStateFlow
}
iOS Platform: "" {
  IUI: "SwiftUI UI / Cupertino Adaptive"
  IViewModel: "Shared ViewModel / State Observers"
  IState: "Combine/Publishers / State Observers"
  IUI -> IViewModel
  IViewModel -> IState
}
Shared Module: "Kotlin Multiplatform Shared" {
  UIC: "Use Cases (Interactors)"
  REPO: "Repositories (Interfaces + Impl)"
  P2P: "P2P Core Layer / libp2p + Nostr + Signal + WebRTC + LDK"
  DATA: "Data Layer / Entities + DAOs + Local/Remote Sources"
  DI_KMP: "Dependency Injection / Koin KMP or Manual"
  UTILS: "Utilities / Constants + Extensions + Helpers"
  UIC -> REPO
  REPO -> P2P
  P2P -> DATA
  DATA -> DI_KMP
  DI_KMP -> UTILS
}
Android Platform.AStateFlow -> Shared Module.UIC: "State Collection"
iOS Platform.IState -> Shared Module.UIC: "State Subscription"
Shared Module.UIC -> Shared Module.REPO: "Triggers"
Shared Module.REPO -> Shared Module.P2P: "Queries/Commands"
Shared Module.P2P -> Shared Module.DATA: "Events/Data"
Shared Module.DATA -> Shared Module.UIC: "Provides"
```

## Data Flow — Offer Creation

```d2
direction: down
User: "" { shape: person }
AndroidUI: "Android UI (Jetpack Compose)"
iOSUI: "iOS UI (SwiftUI)"
SharedVM: "Shared ViewModel (KMP)"
UseCases: "Use Cases (KMP)"
Repos: "Repositories (KMP)"
P2PCore: "P2P Core (KMP)"
LocalDB: "Local Database (SQLCipher KMP)" { shape: cylinder }
RemoteP2P: "Remote P2P (libp2p / WebRTC / Nostr)" { shape: cloud }
User -> AndroidUI: "Tap Create Offer"
User -> iOSUI: "Tap Create Offer"
AndroidUI -> SharedVM: "triggerCreateOffer()"
SharedVM -> UseCases: "CreateOfferUseCase.execute(params)"
UseCases -> Repos: "OfferRepository.save(offerDraft)"
Repos -> LocalDB: "insert(offerEntity)"
LocalDB -> Repos: "offerId"
Repos -> UseCases: "offerId"
UseCases -> SharedVM: "Result.success(offerId)"
SharedVM -> AndroidUI: "updateUIState(offerId)"
iOSUI -> SharedVM: "triggerCreateOffer()"
# ... (full flow in ARCHITECTURE_DIAGRAMS.d2)
```

## State Management Strategy

*(See `ARCHITECTURE_DIAGRAMS.d2` for full rendering)*

## Dependency Injection

*(See `ARCHITECTURE_DIAGRAMS.d2` for full rendering)*

## Folder Structure

*(See `ARCHITECTURE_DIAGRAMS.d2` for full rendering)*

## Cross-Platform Decision

*(See `ARCHITECTURE_DIAGRAMS.d2` for full rendering)*
