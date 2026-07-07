# Design Tokens — design-system/

## Purpose

Single source of truth for NEO-P2P visual design language. JSON-based design tokens defining colors, typography, spacing, shape, elevation, and iconography for both Android (Material 3) and iOS (Cupertino/HIG) platforms. The "cyber-green anonymous trader" aesthetic.

## Ownership

- **Owner:** Design team
- **Scope:** `design-system/design-tokens.json`

## Local Contracts

- **Colors:**
  - Primary: `#00E676` (neon green — active trades)
  - Secondary: `#00BCD4` (cyan — chat/messages)
  - Tertiary: `#FF9800` (orange — escrow/warnings)
  - Full dark/light mode pairs for background, surface, onSurface, outline, error, warning, success
- **Typography:** Material 3 type scale (displayLarge → labelSmall) with platform-specific font families (Roboto Android, SF Pro iOS)
- **Spacing:** 8dp grid system (0–24 scale)
- **Shape:** Rounded corners — small 4px, medium 8px, large 12px, full 50%
- **Elevation:** 5 levels (0px–12px)
- **Iconography:** Outlined style, 3 sizes (small 18px, medium 24px, large 36px)

## Work Guidance

- All platform UI implementations must reference these tokens — never hardcode values
- JSON Schema v2020-12 for validation
- Dark/light theme pairs are required for every surface/background color
- Tokens are platform-agnostic — platform-specific mapping files may exist in respective UI modules

## Verification

- Validate against JSON Schema: `python -m json.tool design-system/design-tokens.json > /dev/null`
- Cross-reference with Android `Theme.kt` and iOS color assets for consistency

## Child DOX Index

*No children — leaf module (single file).*
