package com.neop2p.ui.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NeoSurfaceTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun card_renders_outside_provider_and_shows_content() {
        compose.setContent { MaterialTheme { NeoCard { Text("Escrow") } } }
        compose.onNodeWithText("Escrow").assertIsDisplayed()
    }

    @Test
    fun panel_renders_outside_provider() {
        compose.setContent { MaterialTheme { NeoPanel { Text("Panel") } } }
        compose.onNodeWithText("Panel").assertIsDisplayed()
    }

    @Test
    fun a_clickable_card_reports_the_click() {
        var clicks = 0
        compose.setContent {
            NeoP2PTheme { NeoCard(onClick = { clicks++ }) { Text("Open") } }
        }
        compose.onNodeWithText("Open").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun glow_is_a_no_op_with_a_transparent_colour() {
        // Light mode sets glow to Color.Transparent; the modifier must return the
        // chain unharmed rather than drawing a black halo.
        var composed = false
        compose.setContent {
            NeoP2PTheme(mode = ThemeMode.LIGHT) {
                NeoCard(glow = true) { Text("Glow") }
                composed = NeoTheme.colors.glow.alpha == 0f
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Glow").assertIsDisplayed()
        assertTrue(composed)
    }
}
