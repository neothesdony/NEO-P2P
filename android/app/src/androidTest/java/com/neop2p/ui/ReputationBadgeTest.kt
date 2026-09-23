package com.neop2p.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.neop2p.data.reputation.ReputationSystem.PeerReputation
import com.neop2p.ui.components.ReputationBadge
import com.neop2p.ui.theme.NeoP2PTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReputationBadgeTest {
    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun zeroTrades_rendersNewTraderAdvisory() {
        composeTestRule.setContent {
            NeoP2PTheme {
                ReputationBadge(PeerReputation(peerId = "p", totalTrades = 0))
            }
        }
        composeTestRule.onNodeWithText("New trader", substring = true).assertIsDisplayed()
    }

    @Test
    fun nullReputation_rendersNewTraderAdvisory() {
        composeTestRule.setContent { NeoP2PTheme { ReputationBadge(null) } }
        composeTestRule.onNodeWithText("New trader", substring = true).assertIsDisplayed()
    }
}
