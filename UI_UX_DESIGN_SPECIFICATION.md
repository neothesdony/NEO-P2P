
# NEO-P2P UI/UX Design Specification

> **Status (2026-09-02): STALE — scaffold-era spec.** Describes the pre-Phase-4 design (Nostr relay config, TURN/STUN, iOS/Cupertino). The live app is **Android-only** (Compose + Material 3) with **RNS + LXMF as the only transport** (libp2p/Nostr/WebRTC removed in Phase 4, 2026-08-31). Retained for historical reference; the live Settings screen is documented in `docs/FLOW_ANALYSIS.md`.

## 9. Settings Screen Design

The Settings screen manages identity, network, security, appearance, and advanced configuration for the NEO-P2P app. Designed for conciseness in the zero-backend P2P architecture.

### 9.1 Screen Purpose

Manages user preferences including:
- Nostr relay configuration
- TURN/STUN networking settings
- Tor privacy toggle and auto-connect
- Identity reset (danger zone)
- App version and fee wallet info
- Adaptive layout for Material 3 (Android) and Cupertino/HIG (iOS)

### 9.2 Screen Layout

#### Android (Material 3)
```
┌─────────────────────────────────────────────────────────────┐
│  ┌───────────────────────────────────────────────────────┐ │
│  │  [Back]  Settings                    [More]           │ │
│  └───────────────────────────────────────────────────────┘ │
├─────────────────────────────────────────────────────────────┤
│                                                               │
│  ┌───────────────────────────────────────────────────────┐ │
│  │  Nostr Relays                                          │ │
│  │  ┌─────────────────────────────────────────────────┐ │ │
│  │  │  ● connect.nostr.pool:50000  [Connected]        │ │ │
│  │  │  ● hub.neop2p.id:50001       [Connected]        │ │ │
│  │  └─────────────────────────────────────────────────┘ │ │
│  │  [Add Relay]                                           │ │
│  └───────────────────────────────────────────────────────┘ │
│  ┌───────────────────────────────────────────────────────┐ │
│  │  Connectivity                                          │ │
│  │  ┌─────────────────────────────────────────────────┐ │ │
│  │  │  TURN Server                                      │ │ │
│  │  │  Configured     (primary color)                   │ │ │
│  │  │  [turn:neop2p.id:3478?transport=tcp]             │ │ │
│  │  │  (OutlinedTextField, full width)                  │ │ │
│  │  └─────────────────────────────────────────────────┘ │ │
│  │  ┌─────────────────────────────────────────────────┐ │ │
│  │  │  Default STUN                                      │ │ │
│  │  │  stun:stun.l.google.com:19302                     │ │ │
│  │  └─────────────────────────────────────────────────┘ │ │
│  └───────────────────────────────────────────────────────┘ │
│  ┌───────────────────────────────────────────────────────┐ │
│  │  Privacy                                               │ │
│  │  ┌─────────────────────────────────────────────────┐ │ │
│  │  │  Route through Tor        [● On]                 │ │ │
│  │  │  Routes all Nostr/libp2p traffic through Tor     │ │ │
│  │  │                                    [Info icon]    │ │ │
│  │  └─────────────────────────────────────────────────┘ │ │
│  │  ┌─────────────────────────────────────────────────┐ │ │
│  │  │  Auto-connect to relays   [● On]                 │ │ │
│  │  └─────────────────────────────────────────────────┘ │ │
│  └───────────────────────────────────────────────────────┘ │
│  ┌───────────────────────────────────────────────────────┐ │
│  │  About                                                 │ │
│  │  ┌─────────────────────────────────────────────────┐ │ │
│  │  │  Version        v1.0.0-alpha                     │ │ │
│  │  │  Network        Nostr + libp2p                   │ │ │
│  │  │  Escrow         2-of-3 Multisig                 │ │ │
│  │  │  Fee            1%                               │ │ │
│  │  └─────────────────────────────────────────────────┘ │ │
│  └───────────────────────────────────────────────────────┘ │
│  ┌───────────────────────────────────────────────────────┐ │
│  │  Danger Zone                                           │ │
│  │  ┌─────────────────────────────────────────────────┐ │ │
│  │  │  ⚠️ Reset Identity     [Button, error color]   │ │ │
│  │  │  Permanently destroy keypair, lose escrow access │ │ │
│  │  └─────────────────────────────────────────────────┘ │ │
│  └───────────────────────────────────────────────────────┘ │
│  ┌───────────────────────────────────────────────────────┐ │
│  │  Fee Wallet                                            │ │
│  │  ┌─────────────────────────────────────────────────┐ │ │
│  │  │  1% commission goes to:                           │ │ │
│  │  │  bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh     │ │ │
│  │  └─────────────────────────────────────────────────┘ │ │
│  └───────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
```

#### iOS (Cupertino/HIG)
```
┌─────────────────────────────────────────────────────────────┐
│  ┌───────────────────────────────────────────────────────┐ │
│  │  ⟨ Back  Settings                     [􏰿]             │ │
│  └───────────────────────────────────────────────────────┘ │
├─────────────────────────────────────────────────────────────┤
│  ┌───────────────────────────────────────────────────────┐ │
│  │  Nostr Relays (grouped style)                         │ │
│  │  ● connect.nostr.pool:50000  [Connected]              │ │
│  │  ● hub.neop2p.id:50001       [Connected]              │ │
│  │  (Add Relay) - system tint text button                │ │
│  └───────────────────────────────────────────────────────┘ │
│  ┌───────────────────────────────────────────────────────┐ │
│  │  Connectivity (grouped)                                │ │
│  │  ┌─────────────────────────────────────────────────┐ │ │
│  │  │  TURN Server: Configured                          │ │ │
│  │  │  [turn:neop2p.id:3478?transport=tcp]            │ │ │
│  │  └─────────────────────────────────────────────────┘ │ │
│  │  ┌─────────────────────────────────────────────────┐ │ │
│  │  │  Default STUN                                     │ │ │
│  │  │  stun:stun.l.google.com:19302                     │ │ │
│  │  └─────────────────────────────────────────────────┘ │ │
│  └───────────────────────────────────────────────────────┘ │
│  ┌───────────────────────────────────────────────────────┐ │
│  │  Privacy (grouped)                                     │ │
│  │  Route through Tor              [Toggle ON]           │ │
│  │  Auto-connect to relays         [Toggle ON]           │ │
│  └───────────────────────────────────────────────────────┘ │
│  ┌───────────────────────────────────────────────────────┐ │
│  │  About (grouped)                                       │ │
│  │  Version  v1.0.0-alpha                                │ │
│  │  Network  Nostr + libp2p                              │ │
│  │  Escrow   2-of-3 Multisig                            │ │
│  │  Fee      1%                                          │ │
│  └───────────────────────────────────────────────────────┘ │
│  ┌───────────────────────────────────────────────────────┐ │
│  │  Danger Zone (red)                                     │ │
│  │  Reset Identity     (red text, destructive action)   │ │
│  │  Permanently destroy keypair, lose escrow access     │ │
│  └───────────────────────────────────────────────────────┘ │
│  ┌───────────────────────────────────────────────────────┐ │
│  │ Fee Wallet                                             │ │
│  │ 1% commission goes to:                                 │ │
│  │ bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh          │ │
│  └───────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
```

### 9.3 Components & States

#### Settings Sections
| Section | Android | iOS |
|---------|---------|-----|
| **Section Header** | titleMedium, onSurfaceVariant (50% opacity), 24dp/24pt spacing | title, onSurface (60% opacity), 24pt spacing |
| **Card Container** | surfaceVariant, 12dp corners, 1px outline | Grouped background, 12pt corners |
| **Element Row** | bodyMedium/bodySmall, onSurfaceVariant, padding 12dp / 12pt | body, secondary (60% opacity), padding 12pt |
| **Toggle/Switch** | Switch (Material 3) / Switch (iOS) | Toggle (iOS switch) |
| **Link Text** | primary color underline, bodySmall | system tint, underline, body |

#### Relay List
| Element | Android | iOS |
|---------|---------|-----|
| **Relay Entry** | Row, SpaceBetween, padding 4dp vertical | Full-width, padding 12pt vertical |
| **URL** | bodySmall, onSurfaceVariant | body, secondary |
| **Status** | labelSmall, primary color, ● indicator | caption, primary, ● indicator |
| **Spacing** | 8dp between relay entries | 8pt between relay entries |

#### TURN Field
| Element | Android | iOS |
|---------|---------|-----|
| **Field Label** | labelSmall, onSurfaceVariant (50% opacity) | labelSmall, secondary (60% opacity) |
| **Input Field** | OutlinedTextField, full width, 16dp padding | TextField, full width, 12pt padding |
| **Helper Text** | bodySmall, onSurfaceVariant | caption, secondary |
| **Error State** | error color, 1dp border, bottom padding | error color border, shake animation |

#### Privacy Section
| Element | Android | iOS |
|---------|---------|-----|
| **Row** | Toggle + Text on same row, SpaceBetween | Text left, Toggle right, space 12pt |
| **Info** | [ℹ️] icon, trailing, tooltip on long press | [ℹ️] icon, small tooltip on tap |
| **Toggle** | Switch (primary color state) | Toggle (system tint color state) |

#### About Info
| Element | Android | iOS |
|---------|---------|-----|
| **Label** | bodySmall, onSurfaceVariant | body, secondary |
| **Value** | bodySmall, onSurface | body, onSurface |
| ** Mono Font** | `monospace` font family | `Menlo` font family |
| **Padding** | 8dp vertical, 12dp horizontal | 10pt vertical, 12pt horizontal |

#### Danger Zone
| Element | Android | iOS |
|---------|---------|-----|
| **Header** | titleMedium, error color, 16dp top | titleLarge, system_red color |
| **Warning** | bodySmall, onErrorContainer, 12dp padding | body, system_red, 12pt padding |
| **Button** | Button, error container, 48dp height | Text button, system_red, 44pt height |
| **Text** | "Reset Identity", labelLarge | "Reset Identity", title |

### 9.4 States & Behavior

#### Loading State
| State | Android | iOS |
|-------|---------|-----|
| **Relay Loading** | Skeleton: 2 lines (80% / 60% width), surfaceVariant | Skeleton: 2 lines (80% / 60% width), grouped |
| **Save Action** | Centered CircularProgressIndicator (primary, 48dp), overlay | UIActivityIndicatorView (medium), modal |
| **Disabled State** | All forms disabled, 50% opacity | All toggles disabled, 30% opacity |
| **Error Feedback** | Toast: "Failed to save relay", 2s | HUD: "Save failed", auto-dismiss 2s |

#### Success State
| Action | Android | iOS |
|--------|---------|-----|
| **Add Relay** | Success snackbar: "Added relay", 3s | HUD: "Relay added", 1.5s |
| **Toggle Relay** | Chip: "Relay changed", 2s | HUD: "Relay updated", 1.5s |
| **Toggle Tor** | Toast: "Tor enabled", 2s | HUD: "Tor enabled", 1.5s |
| **Reset Identity** | Dialog: "Identity reset", 2 buttons | Alert: "Identity reset", destructive |

#### Network Status
| Status | Color | Indicator |
|--------|-------|-----------|
| Connected | primary | ● green, 8dp |
| Disconnected | onSurfaceVariant | ○ gray, 8dp |
| Error | error | × red, 8dp |

### 9.5 Accessibility Requirements

| Requirement | Specification |
|-------------|---------------|
| **Touch Targets** | Minimum 48dp (Android) / 44pt (iOS) |
| **Contrast Ratio** | 4.5:1 normal text, 3:1 labels |
| **Dynamic Type** | Supports system font scaling |
| **Screen Reader** | "Nostr relays", "Turn server", "Route through Tor" |
| **Form Labels** | Clear labels: "TURN URL (turn://user:pass@host:port)" |
| **Color-Blind** | Status indicators use both color + icons |
| **Keyboard Nav** | Tab: Relay List → Add → TURN → STUN → Tor → Auto → Reset |

### 9.6 Animation & Transitions

| Animation | Android | iOS | Duration |
|-----------|---------|-----|----------|
| Screen Enter | slide_from_right + fade_in | slide_from_right + fade_in | 200ms ease-out |
| Screen Exit | slide_to_left + fade_out | slide_to_left + fade_out | 200ms ease-in |
| Toggle Change | Scale(1.05 → 1.0) | Scale(1.05 → 1.0) | 100ms ease-out |
| Toast | Fade in + translateY(20dp → 0) | Fade in + scale(0.9 → 1.0) | 200ms ease-out |
| Success | Scale(1.0 → 1.1 → 1.0) | Pulse scale(1.0 → 1.2 → 1.0) | 300ms |
| Error | Shake (±4dp, 3 cycles) | Shake (±2pt, 3 cycles) | 200ms |

### 9.7 Dark Mode Support

All components automatically adapt using design tokens:
- Background: `#0D1117`
- Surface: `#161B22`
- SurfaceVariant: `#21262D`
- onBackground/onSurface: `#C9D1D9`
- onSurfaceVariant: `#8B949E`
- primary: `#00E676` (consistent)
- secondary: `#00BCD4` (consistent)
- error: `#FF5252` (consistent)

### 9.8 Implementation Notes

#### Data Flow
1. Screen loads, fetches relay list from NostrClient
2. Loads TURN config from secure storage (if present)
3. Loads Tor preference from device memory
4. Loads About info (version, network, escrow type)
5. Displays Success state with all data populated

#### Performance
- **No heavy assets** - text and vectors only
- **Local Caching** - relay list cached in Jetpack DataStore / UserDefaults
- **Sparse Updates** - only update specific settings, not full re-render
- **Memory** - single instance, ~2MB peak

#### Platform-Specific
- **Android**: Material 3 Components, Switch, OutlinedTextField
- **iOS**: SF Symbols, Form-style grouped layout, System controls
- **Shared**: ViewModel with immutable state, one-way data flow

#### Security & Privacy
- **Local Storage Only** - all settings stored in device secure storage
- **No Sync** - settings are device-specific, no cloud sync
- **Reset Confirmation** - requires 2-step action to avoid accidental reset
- **Tor Privacy** - routes all traffic through Tor when enabled, disables direct connections
- **Feedback Protection** - sensitive actions (reset) require explicit confirmation dialog

---
**END OF SETTINGS SCREEN DESIGN**

**END OF AGENT OUTPUT**