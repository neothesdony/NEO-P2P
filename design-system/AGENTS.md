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

## Android Implementation Notes

- Android `Theme.kt` implements the **"Neo Grid"** scheme (token architecture inspired by MostroP2P, rebuilt around NEO-P2P brand hues):
  - Primary `#00E676` (buy/active trades), Secondary `#00BCD4` (chat/network), Tertiary `#FF9800` (escrow/warnings) — matches tokens above.
  - Semantic helpers on `ColorScheme`: `buyColor` (primary green), `sellColor` (`#FF6B6B` red), `escrowColor`/`warningColor` (tertiary orange). Screens MUST use these for buy/sell accents — never `secondary`/`tertiary` directly.
  - Full M3 tonal surface ladder (`surfaceContainerLowest` → `surfaceContainerHighest`) for elevated surfaces.
  - Shapes token-aligned: 4/8/12/16/24dp radii.
- `NeoP2PTheme` **forces dark by default** (`darkTheme = true`) to keep the anonymous-trader look. `LightColorScheme` is now **complete** (all tokens incl. surface ladder + error pairs) — safe to enable if light mode is restored.
- Screens must use `MaterialTheme.colorScheme.background` (not a hardcoded `Color.White`/`0xFF0D1117`) so they track the theme.
- **Localization**: all UI copy must use `stringResource(R.string.*)` — `values/` (EN) and `values-in/` (ID) are both complete. Exceptions (ViewModel error strings, log tags, JSON keys, MIME types) stay literals; never call `stringResource()` outside a `@Composable` context.
- Escrow status chips use fixed Mostro-style pairs (pending amber, funded green, signed blue, disputed red, resolving purple, refunded grey) defined in `EscrowScreen.kt` — these are intentional constants, not theme tokens.

## Verification

- Validate against JSON Schema: `python -m json.tool design-system/design-tokens.json > /dev/null`
- Cross-reference with Android `Theme.kt` and iOS color assets for consistency

## Child DOX Index

*No children — leaf module (single file).*
