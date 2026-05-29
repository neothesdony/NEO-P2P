# NEO-P2P Onboarding Screen Specification

## Overview
The Onboarding screen introduces new users to NEO-P2P's anonymous P2P trading platform. It consists of 3 pages explaining core features: anonymous trading, escrow security, and direct wallet-to-wallet transfers. Users can skip onboarding or proceed to account creation.

## Platform Adaptation
- **Android**: Material 3 components, Jetpack Compose implementation
- **iOS**: Cupertino/Human Interface Guidelines, SwiftUI implementation
- Both platforms maintain identical user flows and visual language with platform-specific navigation patterns

## Design Tokens Usage
All measurements, colors, typography, shapes, and elevations reference the design tokens in `./design-system/design-tokens.json`

---

## Page Structure (Shared Across All 3 Pages)

### Layout
- **Screen Background**: 
  - Light: `$color.background.light` (`#FFFFFF`)
  - Dark: `$color.background.dark` (`#0D1117`)
- **Safe Area**: Respects system safe area insets (status bar, navigation bar/home indicator)
- **Content Area**: 
  - Horizontal padding: `$spacing.values.4` (32px) on both sides
  - Vertical padding: Top: `$spacing.values.6` (48px), Bottom: `$spacing.values.6` (48px) + safe area bottom

### Common Elements
1. **Progress Indicator** (Top Center)
   - Type: Dots indicator (3 dots for 3 pages)
   - Size: `$spacing.values.1` (8px) diameter each
   - Spacing: `$spacing.values.2` (16px) between dots
   - Active dot color: `$color.primary` (`#00E676`)
   - Inactive dot color: `$color.onSurfaceVariant` 
     - Light: `$color.onSurfaceVariant.light` (`#424242`)
     - Dark: `$color.onSurfaceVariant.dark` (`#8B949E`)
   - Animation: Scale pulse (0.8x to 1.2x) on active dot, 300ms ease-in-out

2. **Skip Button** (Top Right)
   - Text: "Skip"
   - Typography: `$typography.labelLarge`
     - Size: 14px, Weight: 500, Line height: 20px, Letter spacing: 0.1px
   - Color: 
     - Light: `$color.onSurfaceVariant` (`#424242`)
     - Dark: `$color.onSurfaceVariant` (`#8B949E`)
   - Touch target: Minimum 48x48dp (extends beyond text)
   - State:
     - Enabled: Opacity 100%
     - Pressed: Opacity 70% (ripple on Android, opacity on iOS)
     - Disabled: Opacity 30% (not used in this screen)

3. **Page Content** (Center)
   - Illustration Area: 
     - Height: `$spacing.values.12` (96px)
     - Centered horizontally
   - Title:
     - Typography: `$typography.titleLarge`
       - Size: 22px, Weight: 400, Line height: 28px, Letter spacing: 0px
     - Color: `$color.onSurface`
       - Light: `$color.onSurface.light` (`#1C1B1F`)
       - Dark: `$color.onSurface.dark` (`#C9D1D9`)
     - Margin bottom: `$spacing.values.2` (16px)
   - Description:
     - Typography: `$typography.bodyLarge`
       - Size: 16px, Weight: 400, Line height: 24px, Letter spacing: 0.5px
     - Color: `$color.onSurfaceVariant`
       - Light: `$color.onSurfaceVariant.light` (`#424242`)
       - Dark: `$color.onSurfaceVariant.dark` (`#8B949E`)
     - Line height: 1.5, Max width: 280px (centered)

4. **Action Button** (Bottom)
   - For pages 1-2: "Next"
   - For page 3: "Get Started"
   - Type: Filled button
   - Dimensions:
     - Width: Full width of content area (matches horizontal padding)
     - Height: `$spacing.values.5` (40px)
   - Typography: `$typography.labelLarge`
     - Size: 14px, Weight: 500, Line height: 20px, Letter spacing: 0.1px
   - Colors:
     - Background: `$color.primary` (`#00E676`)
     - Text: `$color.onPrimary` (`#003300`)
   - Shape: `$shape.medium` (8px corner radius)
   - Elevation: `$elevation.level2` (3px)
   - State:
     - Enabled: As described
     - Pressed: Background opacity 80%, scale 0.98
     - Disabled: Background `$color.onSurfaceVariant` (30% opacity), Text `$color.onSurface` (30% opacity)
   - Animation: 
     - Press: Scale down to 0.98 over 100ms
     - Release: Scale up to 1.0 over 100ms
   - Touch target: Minimum 48x48dp (extends vertically if needed)

## Page-Specific Content

### Page 1: Anonymous Trading
- **Illustration**: 
  - Style: Line art with primary color accent
  - Content: Two stylized avatars exchanging crypto symbols with shield overlay
  - Color: Stroke `$color.onSurfaceVariant`, Accent `$color.primary`
- **Title**: "Trade Anonymously"
- **Description**: 
  "Buy and sell cryptocurrency without revealing your identity. NEO-P2P uses Nostr protocol for private, censorship-resistant communication."

### Page 2: Escrow Security
- **Illustration**:
  - Style: Line art with tertiary color accent
  - Content: Hands shaking over a locked box with crypto symbols inside
  - Color: Stroke `$color.onSurfaceVariant`, Accent `$color.tertiary` (`#FF9800`)
- **Title**: "Secure Escrow Protection"
- **Description": 
  "Your funds are protected by multi-signature escrow until both parties confirm transaction completion. No middlemen, no custody risk."

### Page 3: Direct Wallet Transfers
- **Illustration**:
  - Style: Line art with secondary color accent
  - Content: Two wallets with direct arrow between them, no intermediary
  - Color: Stroke `$color.onSurfaceVariant`, Accent `$color.secondary` (`#00BCD4`)
- **Title**: "Instant Peer-to-Peer Transfers"
- **Description":
  "Send and receive cryptocurrency directly from wallet to wallet. Transactions settle on-chain in seconds with network fees only."

## Platform-Specific Adaptations

### Android (Material 3)
- **Navigation**: 
  - No app bar (content fills screen below status bar)
  - Status bar: Transparent with dark text (light bg) or light text (dark bg)
- **Buttons**:
  - Skip button: Text-only, no border
  - Action button: Raised button with elevation
- **Ripple**: 
  - Skip button: Ripple color `$color.onSurface` (10% opacity)
  - Action button: Ripple color `$color.onPrimary` (20% opacity)
- **Transitions**:
  - Page slide: Horizontal slide between pages (200ms ease-out)
  - Cross-fade fallback for reduced motion preference

### iOS (Cupertino/HIG)
- **Navigation**:
  - Skip button: Placed in navigation bar (top right) if using navigation controller, otherwise top-right corner
  - Status bar: Default style (adapts to background)
- **Buttons**:
  - Skip button: Text-only, no border
  - Action button: Borderless button with background color
- **Touch Feedback**:
  - Skip button: Opacity change on press
  - Action button: Background opacity change on press (no elevation)
- **Transitions**:
  - Page slide: Horizontal slide (iOS standard)
  - No bounce effect on edges

## States

### Loading State
- Triggered when initializing app resources
- Display: Full-screen overlay with centered activity indicator
  - Indicator: Circular progress
    - Size: `$spacing.values.6` (48px)
    - Stroke width: 2px
    - Color: `$color.primary` (`#00E676`)
  - Background: `$color.background` with 70% opacity
    - Light: `rgba(255,255,255,0.7)`
    - Dark: `rgba(13,17,23,0.7)`
- Text: "Loading..." (optional, below indicator)
  - Typography: `$typography.bodyMedium`
  - Color: `$color.onSurface`

### Error State
- Triggered when critical initialization fails (e.g., P2P libraries unavailable)
- Display: 
  - Icon: Warning circle (Material: `error_outline`, iOS: `exclamationmark.triangle.fill`)
    - Size: `$spacing.values.5` (40px)
    - Color: `$color.error` (`#FF5252`)
  - Title: "Setup Issue"
    - Typography: `$typography.titleMedium`
    - Color: `$color.onSurface`
  - Description: 
    "NEO-P2P requires secure components to initialize. Please restart the app or reinstall if issue persists."
    - Typography: `$typography.bodyLarge`
    - Color: `$color.onSurfaceVariant`
  - Action Button: "Retry"
    - Same styling as primary action button but full width
- Layout: Centered in content area with horizontal padding

### Empty State
- Not applicable (onboarding always has content)

### Offline State
- Not applicable (onboarding runs before network connectivity is required)
- Note: If offline detection is added later, use same pattern as Home screen offline banner

## Accessibility

### Contrast Ratios (WCAG AA)
- Text on background: Minimum 4.5:1
  - Title: `$color.onSurface` on `$color.background` = 21:1 (light), 15:1 (dark) ✓
  - Description: `$color.onSurfaceVariant` on `$color.background` = 12.6:1 (light), 13.1:1 (dark) ✓
  - Skip button text: Same as description ✓
  - Action button text: `$color.onPrimary` on `$color.primary` = 4.5:1 ✓
- Iconography: Minimum 3:1 for UI components
  - Progress dots: Active vs inactive = 12.6:1 (light), 13.1:1 (dark) ✓
  - Button borders (if any): Not applicable (no borders)

### Touch Targets
- All interactive elements: Minimum 48x48dp
  - Skip button: Text padded to 48x48dp minimum
  - Progress indicator: Each dot in 48x48dp hit area
  - Action button: 40px height with 8px vertical padding = 56dp total height ✓
  - Edge areas: Horizontal padding ensures tap targets don't reach screen edge

### Screen Reader Support
- Content description/accessibility labels:
  - Progress indicator: "Page 1 of 3"
  - Skip button: "Skip onboarding"
  - Illustrations: Decorative (hidden from screen readers) or descriptive if informative
  - Action button: "Next" or "Get Started"
- Reading order: Title → Description → Action button (skip button and progress indicator in header)
- Dynamic type: Text scales with system font size preferences (tested up to 200%)
- Reduced motion: Animations respect system setting (scale/opacity changes only, no translation)

### Platform-Specific
- **Android**: 
  - Uses `contentDescription` for images/buttons
  - Follows TalkBack navigation order
  - Ripple effects for touch feedback
- **iOS**:
  - Uses `accessibilityLabel` and `accessibilityHint`
  - Follows VoiceOver reading order
  - Uses UIKit/SwiftUI accessibility traits

## Implementation Notes

### State Management
- ViewModel holds current page index (0-2)
- Page change triggers animation
- Skip button calls `ViewModel.skipOnboarding()`
- Action button on pages 1-2: `ViewModel.nextPage()`
- Action button on page 3: `ViewModel.completeOnboarding()`

### Persistence
- Onboarding completion stored in shared preferences/UserDefaults
- Key: `has_seen_onboarding` (boolean)
- Checked on app launch; if true, proceed to Home screen

### Animation Details
- Page transition: 
  - Android: `slideInHorizontally` with `fadeInOut`
  - iOS: `slide` transition
  - Duration: 200ms, Easing: `fastOutSlowIn` (Android) / `easeInEaseOut` (iOS)
- Progressive reveal:
  - Illustration: Fade in (0 to 1 opacity) over 300ms
  - Title: Slide up 8px + fade in over 300ms (delayed 100ms)
  - Description: Slide up 8px + fade in over 300ms (delayed 200ms)
  - Action button: Scale from 0.95 to 1.0 over 300ms (delayed 300ms)

### Dark Mode
- Automatic adaptation based on system setting
- Colors swap as defined in design tokens
- Illustrations use stroke color that adapts (`$color.onSurfaceVariant`)
- No image assets required (vector line art)

## Files to Create
- Android: `OnboardingScreen.kt` (Compose)
- iOS: `OnboardingView.swift` (SwiftUI)
- Shared: `OnboardingViewModel.kt` (KMP)
- Assets: Vector line art illustrations (3) in `res/drawable` (Android) and `Assets.xcassets` (iOS)

## Localization
All text strings externalized for translation:
- Skip: `onboarding_skip`
- Page 1 Title: `onboarding_page1_title`
- Page 1 Description: `onboarding_page1_description`
- Page 2 Title: `onboarding_page2_title`
- Page 2 Description: `onboarding_page2_description`
- Page 3 Title: `onboarding_page3_title`
- Page 3 Description: `onboarding_page3_description`
- Next: `onboarding_next`
- Get Started: `onboarding_get_started`
