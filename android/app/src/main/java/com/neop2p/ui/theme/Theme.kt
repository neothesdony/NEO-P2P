package com.neop2p.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ─── Dark cyber-green theme (anonymous trader aesthetic) ─────
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF00E676),           // Neon green — active trades
    onPrimary = Color(0xFF003300),
    primaryContainer = Color(0xFF005500),
    onPrimaryContainer = Color(0xFFA5D6A7),
    secondary = Color(0xFF00BCD4),         // Cyan — chat/messages
    onSecondary = Color(0xFF00333D),
    secondaryContainer = Color(0xFF00505F),
    onSecondaryContainer = Color(0xFFB2EBF2),
    tertiary = Color(0xFFFF9800),          // Orange — escrow/warnings
    onTertiary = Color(0xFF3E1700),
    background = Color(0xFF0D1117),        // Dark GitHub-style bg
    onBackground = Color(0xFFC9D1D9),
    surface = Color(0xFF161B22),
    onSurface = Color(0xFFC9D1D9),
    surfaceVariant = Color(0xFF21262D),
    onSurfaceVariant = Color(0xFF8B949E),
    error = Color(0xFFFF5252),
    onError = Color(0xFF3D0000),
    outline = Color(0xFF30363D)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF00C853),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB9F6CA),
    onPrimaryContainer = Color(0xFF003300),
    secondary = Color(0xFF00ACC1),
    onSecondary = Color.White,
    background = Color(0xFFF5F5F5),
    onBackground = Color(0xFF1C1B1F),
    surface = Color.White,
    onSurface = Color(0xFF1C1B1F),
    error = Color(0xFFD32F2F)
)

@Composable
fun NeoP2PTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}
