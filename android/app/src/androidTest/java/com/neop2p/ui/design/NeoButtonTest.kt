package com.neop2p.ui.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.neop2p.ui.util.TestTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NeoButtonTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun primary_button_keeps_its_test_tag() {
        var clicked = false
        compose.setContent {
            MaterialTheme {
                NeoButton("Fund escrow", { clicked = true }, testTag = TestTags.FUND_ESCROW)
            }
        }
        compose.onNodeWithTag(TestTags.FUND_ESCROW).assertIsDisplayed().performClick()
        assertTrue(clicked)
    }

    @Test
    fun loading_button_is_not_clickable() {
        var clicked = false
        compose.setContent {
            NeoButton("Sending", { clicked = true }, loading = true, testTag = "loading_cta")
        }
        compose.onNodeWithTag("loading_cta").assertIsNotEnabled()
        compose.onNodeWithTag("loading_cta").performClick()
        assertFalse(clicked)
    }

    @Test
    fun every_variant_renders_its_label() {
        compose.setContent {
            NeoP2PTheme(mode = ThemeMode.DARK) {
                androidx.compose.foundation.layout.Column {
                    NeoButtonVariant.entries.forEach { variant ->
                        NeoButton(text = "CTA $variant", onClick = {}, variant = variant)
                    }
                }
            }
        }
        NeoButtonVariant.entries.forEach { variant ->
            compose.onNodeWithText("CTA $variant").assertIsDisplayed()
        }
    }

    @Test
    fun disabled_button_reports_its_state() {
        compose.setContent { NeoButton("Nope", {}, enabled = false) }
        compose.onNodeWithText("Nope").assertIsNotEnabled()
    }

    @Test
    fun a_live_cta_reports_a_click_action() {
        compose.setContent { NeoButton("Go", {}) }
        compose.onNodeWithText("Go").assertHasClickAction()
    }
}
