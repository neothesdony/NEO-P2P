# Settings Screen Design

> **Status (2026-09-02): STALE — scaffold-era design doc.** Describes the pre-Phase-4 Settings design (Nostr relay config, TURN/STUN, nat traversal). The live app is **Android-only** with **RNS + LXMF as the only transport** (libp2p/Nostr/WebRTC removed in Phase 4, 2026-08-31); the live Settings screen manages transport nodes, privacy, language, payment methods, blocks, seed, and the danger zone. Retained for historical reference.

Settings screen for NEO-P2P with a focus on conciseness to avoid timeouts in the zero-backend P2P architecture.

## 1. Screen Purpose

The Settings screen provides access to user-configurable options across multiple categories:
- Identity management (nickname, avatar, display name)
- Network configuration (peers, bandwidth, nat traversal)
- Security settings (PIN, biometrics, session timeout)
- Appearance preferences (theme, font size, show/hide
- Advanced options (debug logging, anonymous mode, network cleanup)

Designed for quick access with clear categorization to prevent timeouts in the zero-backend P2P architecture.

## 2. Screen Layout

### Android (Material 3)

```
┌─────────────────────────────────────────────────────────────┐
│  ┌───────────────────────────────────────────────────────┐ │
│  │  [Settings]        [Search] [More]                    │ │
│  └───────────────────────────────────────────────────────┘ │
├─────────────────────────────────────────────────────────────┤
│                                                               │
│  ┌───────────────────────────────────────────────────────┐ │
│  │  Section: Identity              [All]                 │ │ │
│  │  ┌─────────────────────────────────────────────────┐ │ │ │
│  │  │  👤 Your Identity                                │ │ │ │
│  │  │  AnonymousTrader                                 │ │ │ │
│  │  │  Change nickname, avatar, display name          │ │ │ │
│  │  └─────────────────────────────────────────────────┘ │ │ │
│  │  ┌─────────────────────────────────────────────────┐ │ │ │
│  │  │  🛡️ Security                                    │ │ │ │
│  │  │  PIN, Biometrics, Session Timeout               │ │ │ │
│  │  └─────────────────────────────────────────────────┘ │ │ │
│  │  ┌─────────────────────────────────────────────────┐ │ │ │
│  │  │  📱 Appearance                                   │ │ │ │
│  │  │  Theme, Font Size, Layout                       │ │ │ │
│  │  └─────────────────────────────────────────────────┘ │ │ │
│  └───────────────────────────────────────────────────────┘ │
│                                                               │
│  ┌───────────────────────────────────────────────────────┐ │
│  │  Section: Network Settings       [All]                │ │ │
│  │  ┌─────────────────────────────────────────────────┐ │ │ │
│  │  │  🌐 Connection                                   │ │ │ │
│  │  │  Peers, Bandwidth, NAT Traversal                │ │ │ │
│  │  └─────────────────────────────────────────────────┘ │ │ │
│  │  ┌─────────────────────────────────────────────────┐ │ │ │
│  │  │  🔌 Proxy                                        │ │ │ │
│  │  │  Proxy Type, Host, Port                         │ │ │ │
│  │  └─────────────────────────────────────────────────┘ │ │ │
│  └───────────────────────────────────────────────────────┘ │
│                                                               │
│  ┌───────────────────────────────────────────────────────┐ │
│  │  Section: Security               [All]                │ │ │
│  │  ┌─────────────────────────────────────────────────┐ │ │ │
│  │  │  🔐 Authentication                                │ │ │ │
│  │  │  Login PIN, Fingerprint, Face ID                │ │ │ │
│  │  └─────────────────────────────────────────────────┘ │ │ │
│  │  ┌─────────────────────────────────────────────────┐ │ │ │
│  │  │  ⏱ Session Settings                              │ │ │ │
│  │  │  Auto-lock, Keep Alive                          │ │ │ │
│  │  └─────────────────────────────────────────────────┘ │ │ │
│  └───────────────────────────────────────────────────────┘ │
│                                                               │
│  ┌───────────────────────────────────────────────────────┐ │
│  │  Section: Appearance             [All]                │ │ │
│  │  ┌─────────────────────────────────────────────────┐ │ │ │
│  │  │  🎨 Theme                                        │ │ │ │
│  │  │  Light/Dark/System, Color Accent                │ │ │ │
│  │  └─────────────────────────────────────────────────┘ │ │ │
│  │  ┌─────────────────────────────────────────────────┐ │ │ │
│  │  │  👁 Display                                      │ │ │ │
│  │  │  Font Size, Line Height, Layout                 │ │ │ │
│  │  └─────────────────────────────────────────────────┘ │ │ │
│  └───────────────────────────────────────────────────────┘ │
│                                                               │
│  ┌───────────────────────────────────────────────────────┐ │
│  │  Section: Advanced               [All]                │ │ │
│  │  ┌─────────────────────────────────────────────────┐ │ │ │
│  │  │  🔧 Debug & Logging                              │ │ │ │
│  │  │  Verbose Logs, Crash Reporting                  │ │ │ │
│  │  └─────────────────────────────────────────────────┘ │ │ │
│  │  ┌─────────────────────────────────────────────────┐ │ │ │
│  │  │  🌑 Anonymous Mode                               │ │ │ │
│  │  │  Hide Peer ID, Mask Traffic                     │ │ │ │
│  │  └─────────────────────────────────────────────────┘ │ │ │
│  │  ┌─────────────────────────────────────────────────┐ │ │ │
│  │  │  🗑 Network Cleanup                              │ │ │ │
│  │  │  Clear Cache, Reset Peers                       │ │ │ │
│  │  └─────────────────────────────────────────────────┘ │ │ │
│  └───────────────────────────────────────────────────────┘ │
│                                                               │
└─────────────────────────────────────────────────────────────┘
```

### iOS (Cupertino/HIG)

```
┌─────────────────────────────────────────────────────────────┐
│  ┌───────────────────────────────────────────────────────┐ │
│  │  [Settings]         [Search]                         │ │
│  └───────────────────────────────────────────────────────┘ │
├─────────────────────────────────────────────────────────────┤
│                                                               │
│  ┌───────────────────────────────────────────────────────┐ │
│  │  Identity                                   [All]      │ │ │
│  │  ┌─────────────────────────────────────────────────┐ │ │ │
│  │  │  🏠 Your Identity                                 │ │ │ │
│  │  │  AnonymousTrader                                  │ │ │ │
│  │  │  Change nickname, avatar, display name           │ │ │ │
│  │  └─────────────────────────────────────────────────┘ │ │ │
│  │  ┌─────────────────────────────────────────────────┐ │ │ │
│  │  │  🔒 Security                                      │ │ │ │
│  │  │  PIN, Biometrics, Session Timeout                │ │ │ │
│  │  └─────────────────────────────────────────────────┘ │ │ │
│  │  ┌─────────────────────────────────────────────────┐ │ │ │
│  │  │  🎨 Appearance                                    │ │ │ │
│  │  │  Theme, Font Size, Layout                        │ │ │ │
│  │  └─────────────────────────────────────────────────┘ │ │ │
│  └───────────────────────────────────────────────────────┘ │
│                                                               │
│  ┌───────────────────────────────────────────────────────┐ │
│  │  Network Settings                           [All]     │ │ │
│  │  ┌─────────────────────────────────────────────────┐ │ │ │
│  │  │  🌐 Connection                                    │ │ │ │
│  │  │  Peers, Bandwidth, NAT Traversal                │ │ │ │
│  │  └─────────────────────────────────────────────────┘ │ │ │
│  │  ┌─────────────────────────────────────────────────┐ │ │ │
│  │  │  📡 Proxy                                         │ │ │ │
│  │  │  Proxy Type, Host, Port                          │ │ │ │
│  │  └─────────────────────────────────────────────────┘ │ │ │
│  └───────────────────────────────────────────────────────┘ │
│                                                               │
│  ┌───────────────────────────────────────────────────────┐ │
│  │  Security                                   [All]      │ │ │
│  │  ┌─────────────────────────────────────────────────┐ │ │ │
│  │  │  🔑 Authentication                                 │ │ │ │
│  │  │  Login PIN, Touch ID, Face ID                    │ │ │ │
│  │  └─────────────────────────────────────────────────┘ │ │ │
│  │  ┌─────────────────────────────────────────────────┐ │ │ │
│  │  │  ⏱ Session Settings                              │ │ │ │
│  │  │  Auto-lock, Keep Alive                           │ │ │ │
│  │  └─────────────────────────────────────────────────┘ │ │ │
│  └───────────────────────────────────────────────────────┘ │
│                                                               │
│  ┌───────────────────────────────────────────────────────┐ │
│  │  Appearance                                 [All]      │ │ │
│  │  ┌─────────────────────────────────────────────────┐ │ │ │
│  │  │  🌓 Theme                                         │ │ │ │
│  │  │  Light/Dark/System, Color Accent                 │ │ │ │
│  │  └─────────────────────────────────────────────────┘ │ │ │
│  │  ┌─────────────────────────────────────────────────┐ │ │ │
│  │  │  👁 Display                                        │ │ │ │
│  │  │  Font Size, Line Height, Layout                  │ │ │ │
│  │  └─────────────────────────────────────────────────┘ │ │ │
│  └───────────────────────────────────────────────────────┘ │
│                                                               │
│  ┌───────────────────────────────────────────────────────┐ │
│  │  Advanced                                   [All]      │ │ │
│  │  ┌─────────────────────────────────────────────────┐ │ │ │
│  │  │  🛠 Debug & Logging                               │ │ │ │
│  │  │  Verbose Logs, Crash Reporting                   │ │ │ │
│  │  └─────────────────────────────────────────────────┘ │ │ │
│  │  ┌─────────────────────────────────────────────────┐ │ │ │
│  │  │  🌑 Anonymous Mode                                │ │ │ │
│  │  │  Hide Peer ID, Mask Traffic                      │ │ │ │
│  │  └─────────────────────────────────────────────────┘ │ │ │
│  │  ┌─────────────────────────────────────────────────┐ │ │ │
│  │  │  🗑 Network Cleanup                               │ │ │ │
│  │  │  Clear Cache, Reset Peers                        │ │ │ │
│  │  └─────────────────────────────────────────────────┘ │ │ │
│  └───────────────────────────────────────────────────────┘ │
│                                                               │
└─────────────────────────────────────────────────────────────┘
```

## 3. Components & States

### 3.1 Screen Shell

| Element | Android | iOS |
|---------|---------|-----|
| **Top Bar** | Surface color, elevation 4dp | Secondary system background, hairline border |
| **Title** | titleLarge, onSurface | Title, onSurface |
| **Search** | Search icon (24dp), onClick opens search dialog | Search icon (22pt), tap opens search overlay |
| **More Menu** | Three-dot icon (24dp), opens dropdown menu | Three-dot icon (22pt), action sheet on tap |
| **Back Navigation** | Back arrow (24dp) or Settings icon | Back arrow or Settings icon |
| **Bottom Padding** | 16dp | 20pt |

### 3.2 Section Headers

| Element | Android | iOS |
|---------|---------|-----|
| **Container** | None (grouped style) | Secondary system background, 12dp/12pt corners |
| **Title** | titleSmall, onSurfaceVariant (50% opacity), 24dp side padding | Group caption, secondary (50% opacity), 20pt side padding |
| **"All" Link** | bodyMedium, primary color, 16dp from title | body, system tint, 16pt from title |
| **Spacing** | 48dp between sections (Material 3 density) | 20pt between sections (Cupertino) |
| **Padding** | 16dp horizontal, 24dp vertical | 16pt horizontal, 24pt vertical |

### 3.3 Settings Items (Cards)

| Element | Android | iOS |
|---------|---------|-----|
| **Container** | surface, 12dp corners, 1px outline, elevation 1dp | grouped background, 8pt corners, 0.5pt border |
| **Icon** | 24dp, primary color (or onSurfaceVariant for disabled) | 22pt, system tint (or secondary for disabled) |
| **Title** | titleMedium, onSurface | title, onSurface |
| **Subtitle** | bodySmall, onSurfaceVariant (50% opacity), 4dp below title | body, secondary (50% opacity), 4pt below title |
| **Leading** | Icon (24dp), 24dp from container start | Icon (22pt), 16pt from container start |
| **Trailing** | chevron_right (24dp), onSurfaceVariant | chevron_right (22pt), secondary |
| **Content Padding** | 24dp start, 16dp end, vertical 16dp | 16pt start, 16pt end, vertical 12pt |
| **Spacing** | 8dp between settings items | 8pt between settings items |
| **Accessibility** | contentDescription: "Navigate to {title}" | accessibilityLabel: "{title}, navigate" |

### 3.4 Settings Categories

#### Identity Section

| Element | Android | iOS |
|---------|---------|-----|
| **Title** | "Identity" | "Identity" |
| **Settings Items** | | |
| &nbsp;&nbsp;- Your Identity | Avatar with initials, nickname, button to change | Avatar with initials, nickname, button to change |
| &nbsp;&nbsp;- Display Name | Text: "AnonymousTrader", bodyMedium, trailing chevron | Text: "AnonymousTrader", body, trailing chevron |
| &nbsp;&nbsp;- Avatar | Text: "Tap to change", bodySmall, trailing icon camera | Text: "Tap to change", body, trailing camera icon |
| &nbsp;&nbsp;- Profile Privacy | Switch, label: "Hide from public", subtitle | Toggle Switch, label: "Hide from public" |
| **Bottom Divider** | 1px, surfaceVariant | 0.5pt, separatorTint |

#### Network Settings Section

| Element | Android | iOS |
|---------|---------|-----|
| **Title** | "Network Settings" | "Network Settings" |
| **Settings Items** | | |
| &nbsp;&nbsp;- Connection | Peers list, bandwidth slider, NAT toggle | Peers list, bandwidth picker, NAT switch |
| &nbsp;&nbsp;- Proxy | Proxy type dropdown, host, port fields | Proxy type picker, host, port text fields |
| &nbsp;&nbsp;- Bandwidth | Slider: "Download: 5 MB/s", "Upload: 2 MB/s" | Picker: "Download", "Upload" with values |
| &nbsp;&nbsp;- NAT Traversal | Switch: "Enable UPnP/NAT-PMP" | Toggle Switch: "Enable NAT traversal" |
| **Helper Text** | bodySmall, onSurfaceVariant | body, secondary |

#### Security Section

| Element | Android | iOS |
|---------|---------|-----|
| **Title** | "Security" | "Security" |
| **Settings Items** | | |
| &nbsp;&nbsp;- PIN & Biometrics | PIN set status, biometric toggle | PIN set status, Touch ID/Face ID toggle |
| &nbsp;&nbsp;- Session Timeout | Dropdown: "15 min", "30 min", "1 hour", "Never" | Picker: timeout options |
| &nbsp;&nbsp;- Auto-lock | Switch: "Lock when screen off" | Toggle Switch: "Lock when screen off" |
| &nbsp;&nbsp;- Login Requirements | List: "PIN required", "Biometric optional" | List: "PIN required", "Biometric optional" |
| **Status Indicators** | success color for enabled, warning for incomplete | system green for enabled, system orange for incomplete |

#### Appearance Section

| Element | Android | iOS |
|---------|---------|-----|
| **Title** | "Appearance" | "Appearance" |
| **Settings Items** | | |
| &nbsp;&nbsp;- Theme | Three options: Light, Dark, System default | Three options: Light, Dark, System |
| &nbsp;&nbsp;- Accent Color | Color wheel picker, preview circle | Color picker sheet with preview |
| &nbsp;&nbsp;- Font Size | Slider: "Small" to "Large" with current | Picker: "Small", "Medium", "Large" |
| &nbsp;&nbsp;- High Contrast | Switch: "Increase contrast" | Toggle Switch: "Increase contrast" |
| **Theme Preview** | Circle with sample text: "Preview" | Preview text below picker |

#### Advanced Section

| Element | Android | iOS |
|---------|---------|-----|
| **Title** | "Advanced" | "Advanced" |
| **Settings Items** | | |
| &nbsp;&nbsp;- Debug Logging | Switch: "Verbose logging" + level selector | Switch: "Verbose logging" |
| &nbsp;&nbsp;- Crash Reporting | Switch: "Send crash reports" | Toggle Switch: "Send crash reports" |
| &nbsp;&nbsp;- Anonymous Mode | Switch: "Hide Peer ID in UI" + subtitle | Switch: "Hide Peer ID in UI" |
| &nbsp;&nbsp;- Network Cleanup | Buttons: "Clear Cache", "Reset Peers" | Buttons: "Clear Cache", "Reset Peers" |
| **Warning Text** | bodySmall, warning color, 12dp padding | body, system orange, 12pt padding |

## 4. States & Behavior

### 4.1 Loading States

| State | Android | iOS |
|-------|---------|-----|
| **Initial Load** | Circular progress (primary, 48dp), centered, 16dp from top | UIActivityIndicatorView (medium, primary), centered |
| **Section Skeletons** | 3 cards each: title (80% width), subtitle (60% width), icon (24dp placeholder) | Same, using grouped background color |
| **Settings Item Loading** | Icon placeholder (24dp), title placeholder (100dp), trailing placeholder (24dp) | Similar layout with placeholder rectangles |

### 4.2 Error States

| Error | Android | iOS |
|-------|---------|-----|
| **Connection Error** | Toast: "Network error, retrying..." + floating action button (retry) | Red banner at top: "Connection failed. Tap to retry." |
| **Save Failed** | Toast error or snackbar with primary color | HUD: "Failed to save" (2s) |
| **Invalid Input** | Text field error color, icon: error, message below field | Field border shake, red border, error message below |
| **Timeout** | Dialog: "Connection timeout", bodyMedium, retry/cancel buttons | Alert: "Connection timeout", titleLarge, confirm button |

### 4.3 Success States

| State | Android | iOS |
|-------|---------|-----|
| **Settings Saved** | Success snackbar: "Settings saved", primary container, 3s duration | HUD: "Settings saved" (1.5s), then dismiss |
| **PIN Changed** | Success snackbar: "PIN updated", primary container | HUD: "PIN updated" (1.5s) |
| **Network Config Saved** | Success snackbar: "Network settings saved" | HUD: "Network saved" (1.5s) |
| **Cache Cleared** | Success snackbar: "Cache cleared" | HUD: "Cache cleared" (1.5s) |

### 4.4 Empty/Initial States

| State | Android | iOS |
|-------|---------|-----|
| **No Settings** | Empty state: "No settings found", icon (48dp), center | Empty state: "No settings", centered |
| **First Install** | Message: "Configure your NEOP2P settings", icon [⚙️] | Message: "Configure your NEO-P2P settings", icon [⚙️] |

## 5. Accessibility Requirements

| Requirement | Specification |
|-------------|---------------|
| **Touch Targets** | Minimum 48dp (Android) / 44pt (iOS) for all interactive elements |
| **Contrast Ratio** | 4.5:1 normal text (WCAG AA), 7:1 for status indicators |
| **Color Blind Support** | Status icons + text (not color alone), warning color with ⚠️ icon |
| **Dynamic Type** | Android: Text scales with system settings, iOS: Dynamic Type support |
| **Screen Reader** | Proper reading order: Section headers → Settings items |
| **Content Descriptions** | All icons: "Search settings", "More options", "Go back" |
| **Read-only Fields** | Marked as clickable, announce "Tap to configure" |
| **Form Fields** | Error messages announced clearly with field label |
| **Keyboard Nav** | Tab order follows visual order |
| **Reduced Motion** | Respect system settings, no bounce animations |

## 6. Animation & Transitions

| Animation | Android | iOS | Duration |
|-----------|---------|-----|----------|
| **Screen Enter** | slide_from_right + fade_in | slide_from_right (modal) + fade_in | 200ms ease-out |
| **Screen Exit** | slide_to_left + fade_out | slide_to_left + fade_out | 200ms ease-in |
| **Settings Item Click** | Ripple effect (40% alpha) | Scale 0.95, subtle opacity change | 100ms linear |
| **Switch Toggle** | Smooth fade (no tick) | Smooth fade with system default | 200ms ease-in-out |
| **Bottom Sheet/Sheet** | slide_from_bottom + fade_in | slide_from_bottom + scale(0.9 → 1.0) | 300ms ease-out |
| **Loading Spinner** | CircularProgressIndicator (cubic-bezier) | UIActivityIndicatorView (system) | Continuous |

## 7. Dark Mode Support

All components automatically adapt using design tokens:

| Element | Light Mode | Dark Mode |
|---------|------------|-----------|
| **Background** | `#FFFFFF` | `#0D1117` |
| **Surface** | `#FFFFFF` | `#161B22` |
| **SurfaceVariant** | `#F5F5F5` | `#21262D` |
| **OnBackground** | `#1C1B1F` | `#C9D1D9` |
| **OnSurface** | `#1C1B1F` | `#C9D1D9` |
| **OnSurfaceVariant** | `#424242` | `#8B949E` |
| **Primary** | `#00E676` (consistent) | `#00E676` |
| **Secondary** | `#00BCD4` (consistent) | `#00BCD4` |
| **Tertiary** | `#FF9800` (consistent) | `#FF9800` |
| **Error** | `#FF5252` (consistent) | `#FF5252` |
| **Outline** | `#E0E0E0` | `#30363D` |
| **OutlineVariant** | `#CCCCCC` | `#424242` |

## 8. Design Token Reference

### Typography (from design-tokens.json)

| Android | iOS | Size | Color |
|---------|-----|------|-------|
| titleLarge | Title | 22px | onSurface |
| titleMedium | Title | 16px | onSurface |
| bodyLarge | Body | 16px | onSurface |
| bodyMedium | Body | 14px | onSurface |
| bodySmall | Body | 12px | onSurfaceVariant |
| labelLarge | Label | 14px | onSurface |

### Spacing (8dp Grid)

| Value | Pixels | Use Case |
|-------|--------|----------|
| 1 | 8px | Smallest spacing |
| 2 | 16px | Card padding, section spacing |
| 3 | 24px | Section headers, large spacing |
| 4 | 32px | Screen padding, large cards |
| 6 | 48px | Screen margins, hero spacing |

### Shapes

| Size | Radius | Use Case |
|------|--------|----------|
| small | 4px | Chips |
| medium | 8px | Buttons |
| large | 12px | Cards, sheets |
| full | 50% | Avatars, circular buttons |

### Colors (from design-tokens.json)

| Token | Value | Use |
|-------|-------|-----|
| primary | `#00E676` | Primary actions, success states |
| secondary | `#00BCD4` | Secondary actions, links |
| tertiary | `#FF9800` | Warnings, escrow |
| error | `#FF5252` | Error states |
| warning | `#FFA726` | Warning states |
| success | `#66BB6A` | Success states |
| onSurface | `#1C1B1F` (light), `#C9D1D9` (dark) | Primary text |
| onSurfaceVariant | `#424242` (light), `#8B949E` (dark) | Secondary text |

## 9. Implementation Notes

### Data Flow
1. Screen loads, fetches current settings from local storage
2. Display initial values from cache (instant)
3. Update sync status indicator (background)
4. On save: validate input, show loading state, save to storage
5. On error: show error state, offer retry
6. On success: show success feedback, update UI

### Performance Optimizations
- **8dp Grid**: All spacing follows 8dp/8pt grid system
- **Local Cache**: Settings cached in Room (Android) / UserDefaults (iOS)
- **Lazy Loading**: Settings items loaded on-demand for advanced section
- **Image Constraints**: Only icons used - no heavy assets
- **Memory**: Single instance screen, minimal memory footprint
- **Timeout Handling**: 10s connection timeout, immediate local response

### Platform-Specific
- **Android**: Material 3 Components, SwitchCompat, TextInputLayout, BottomSheetDialog
- **iOS**: SF Symbols, Form-style layout, System controls, Sheet presentations
- **Shared**: Business logic in ViewModel, immutable data classes for settings

### Security & Privacy
- **Local Settings**: Encrypted at rest using platform keychain/keystore
- **PIN Handling**: PIN input masked, never stored in plaintext
- **Biometric**: Use platform biometric API (FingerprintManager/LocalAuthentication)
- **No Server Storage**: Zero-backend architecture - all settings stored locally

---

**END OF SETTINGS SCREEN DESIGN**

**END OF AGENT OUTPUT**
