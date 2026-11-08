package com.neop2p.ui.design

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.neop2p.ui.components.AppNavigationBar
import com.neop2p.ui.components.AppTab
import com.neop2p.ui.components.NeoTopBar
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Uses the Android rule (not the pure Compose one) because the tab labels are
 * string resources — resolving them needs a real activity context.
 */
@RunWith(AndroidJUnit4::class)
class NavigationBarTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun all_four_tabs_render_and_select() {
        var picked: AppTab? = null
        compose.setContent {
            MaterialTheme { AppNavigationBar(current = AppTab.MARKET, onTabSelected = { picked = it }) }
        }
        AppTab.entries.forEach {
            compose.onNodeWithText(compose.activity.getString(it.labelRes)).assertIsDisplayed()
        }
        compose.onNodeWithText(compose.activity.getString(AppTab.WALLET.labelRes)).performClick()
        assertEquals(AppTab.WALLET, picked)
    }

    @Test
    fun every_tab_has_a_content_description_for_talkback() {
        compose.setContent {
            MaterialTheme { AppNavigationBar(current = AppTab.MARKET, onTabSelected = {}) }
        }
        AppTab.entries.forEach {
            compose.onNodeWithContentDescription(compose.activity.getString(it.cdRes))
                .assertIsDisplayed()
        }
    }

    @Test
    fun the_top_bar_shows_its_title_and_actions() {
        compose.setContent {
            MaterialTheme {
                NeoTopBar(title = "Escrow", actions = { Text("Aksi") })
            }
        }
        compose.onNodeWithText("Escrow").assertIsDisplayed()
        compose.onNodeWithText("Aksi").assertIsDisplayed()
    }

    @Test
    fun a_back_affordance_only_appears_when_requested() {
        var back = 0
        compose.setContent { MaterialTheme { NeoTopBar(title = "Detail", onBack = { back++ }) } }
        compose.onNodeWithContentDescription(
            compose.activity.getString(com.neop2p.R.string.general_back)
        ).performClick()
        assertEquals(1, back)
    }
}
