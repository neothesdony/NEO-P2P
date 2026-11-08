package com.neop2p.ui.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.neop2p.domain.model.EscrowStatus

/**
 * The only entry point a screen needs. `NeoTheme` reads the token layer, and falls
 * back to Material when a component is composed outside the provider — which the
 * existing smoke tests do deliberately, to prove a kit component never depends on
 * app wiring to render.
 */
object NeoTheme {
    val colors: NeoColors
        @Composable
        @ReadOnlyComposable
        get() = LocalNeoColors.current ?: NeoColors.defaultFrom(MaterialTheme.colorScheme)

    val type: NeoType
        @Composable
        @ReadOnlyComposable
        get() = LocalNeoType.current ?: NeoType.defaultFrom(MaterialTheme.typography)

    val spacing: NeoSpacing
        @Composable
        @ReadOnlyComposable
        get() = NeoSpacing.Default

    val reducedMotion: Boolean
        @Composable
        @ReadOnlyComposable
        get() = LocalReducedMotion.current
}

/**
 * [mode] defaults to SYSTEM: the identity was designed on the dark ink base, but
 * the app follows the device unless the user overrides it in Settings.
 */
/**
 * The scheme in force. Every screen wraps its own content in `NeoP2PTheme { }`
 * (a preview habit), so a nested call must reuse the ambient scheme — otherwise the
 * inner default `SYSTEM` would silently undo a user's DARK/LIGHT override.
 */
val LocalNeoScheme = staticCompositionLocalOf<ColorScheme?> { null }

@Composable
fun NeoP2PTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val ambient = LocalNeoScheme.current
    val scheme = ambient ?: run {
        val dark = resolveDarkTheme(mode, isSystemInDarkTheme())
        if (dark) darkScheme else lightScheme
    }
    val typography = remember { neoTypography() }

    CompositionLocalProvider(
        LocalNeoScheme provides scheme,
        LocalNeoColors provides NeoColors.defaultFrom(scheme),
        LocalNeoType provides NeoType.defaultFrom(typography)
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = typography,
            shapes = NeoShapes,
            content = content
        )
    }
}

// ─── Compatibility layer ─────────────────────────────────────────────────
// These names already have call sites from the pre-overhaul theme. They now
// resolve through NeoTheme, so a screen keeps compiling while it waits for its
// re-skin, and every value still comes from one place. Each is deleted when its
// owning screen moves to the kit.

val ColorScheme.buyColor: Color
    @Composable
    @ReadOnlyComposable
    get() = NeoTheme.colors.buy

val ColorScheme.sellColor: Color
    @Composable
    @ReadOnlyComposable
    get() = NeoTheme.colors.sell

val ColorScheme.escrowColor: Color
    @Composable
    @ReadOnlyComposable
    get() = NeoTheme.colors.escrow

val ColorScheme.warningColor: Color
    @Composable
    @ReadOnlyComposable
    get() = NeoTheme.colors.warning
