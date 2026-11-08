package com.neop2p.ui.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The kit must render outside `NeoP2PTheme` — the smoke tests compose components
 * under a bare `MaterialTheme { }`. That only works if the accessors fall back to
 * Material instead of throwing or going transparent.
 */
@RunWith(AndroidJUnit4::class)
class NeoThemeFallbackTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun accessors_fall_back_to_the_ambient_material_theme() {
        var ambientPrimary: Color? = null
        var buy: Color? = null
        var moneyHeroSize = 0f
        var screenPadding = 0f
        compose.setContent {
            MaterialTheme {
                ambientPrimary = MaterialTheme.colorScheme.primary
                buy = NeoTheme.colors.buy
                moneyHeroSize = NeoTheme.type.moneyHero.fontSize.value
                screenPadding = NeoTheme.spacing.screenPadding.value
            }
        }
        compose.waitForIdle()
        assertEquals(ambientPrimary, buy)
        // Numeric roles and spacing are token-defined, not Material-defined, so
        // they are identical with or without the provider.
        assertEquals(34f, moneyHeroSize, 0.01f)
        assertEquals(20f, screenPadding, 0.01f)
    }

    @Test
    fun the_provider_supplies_the_neon_vault_scheme() {
        var buy: Color? = null
        var glowAlpha = 1f
        compose.setContent {
            NeoP2PTheme(mode = ThemeMode.DARK) {
                buy = NeoTheme.colors.buy
                glowAlpha = NeoTheme.colors.glow.alpha
            }
        }
        compose.waitForIdle()
        assertEquals(darkScheme.primary, buy)
        assertEquals(0.22f, glowAlpha, 0.001f)
    }

    @Test
    fun light_mode_disables_the_glow() {
        var glowAlpha = 1f
        compose.setContent {
            NeoP2PTheme(mode = ThemeMode.LIGHT) { glowAlpha = NeoTheme.colors.glow.alpha }
        }
        compose.waitForIdle()
        assertEquals(0f, glowAlpha, 0.0001f)
    }
}
