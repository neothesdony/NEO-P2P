package com.neop2p.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.neop2p.data.p2p.store.PeerRegistry
import com.neop2p.domain.model.EscrowStatus
import com.neop2p.ui.components.ConnectionQualityChip
import com.neop2p.ui.components.NeoEmptyState
import com.neop2p.ui.screens.escrow.EscrowStatusChip
import com.neop2p.ui.screens.escrow.EscrowStep
import com.neop2p.ui.screens.escrow.StepTracker
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComposeSmokeTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun emptyStateShowsItsTitleAndHint() {
        compose.setContent {
            MaterialTheme {
                NeoEmptyState(
                    title = "Belum ada penawaran",
                    hint = "Tarik untuk menyegarkan"
                )
            }
        }
        compose.onNodeWithText("Belum ada penawaran").assertIsDisplayed()
        compose.onNodeWithText("Tarik untuk menyegarkan").assertIsDisplayed()
    }

    @Test
    fun emptyStateRendersAnActionSlot() {
        compose.setContent {
            MaterialTheme {
                NeoEmptyState(title = "Kosong", actions = { Text("Buat Penawaran") })
            }
        }
        compose.onNodeWithText("Buat Penawaran").assertIsDisplayed()
    }

    @Test
    fun escrowStatusChipRendersForEveryStatus() {
        // setContent may only be called once per test, so every variant is
        // composed in one tree — a crash in any status fails the test.
        compose.setContent {
            MaterialTheme {
                Column {
                    EscrowStatus.entries.forEach { status ->
                        EscrowStatusChip(status = status)
                    }
                }
            }
        }
        compose.onRoot().assertExists()
    }

    @Test
    fun stepTrackerShowsTheCallerSuppliedLabels() {
        val steps = EscrowStep.entries.toList()
        val labels = steps.associateWith { it.name }
        compose.setContent {
            MaterialTheme {
                StepTracker(currentStep = 0, steps = steps, labels = labels)
            }
        }
        compose.onAllNodesWithText(labels.getValue(steps[0])).assertCountEquals(1)
    }

    @Test
    fun connectionQualityChipRendersForEveryState() {
        compose.setContent {
            MaterialTheme {
                Column {
                    PeerRegistry.ConnectionQuality.entries.forEach { quality ->
                        ConnectionQualityChip(quality = quality)
                    }
                }
            }
        }
        compose.onRoot().assertExists()
    }

    @Test
    fun navigationBarTabsExposeContentDescriptions() {
        compose.setContent {
            MaterialTheme {
                com.neop2p.ui.components.AppNavigationBar(
                    current = com.neop2p.ui.components.AppTab.MARKET,
                    onTabSelected = {}
                )
            }
        }
        compose
            .onAllNodes(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription),
                useUnmergedTree = true
            )
            .assertCountEquals(4)
    }

    @Test
    fun emptyStateExposesItsTitle() {
        compose.setContent {
            MaterialTheme {
                NeoEmptyState(
                    title = "No offers yet",
                    hint = "Pull to refresh"
                )
            }
        }
        compose.onNodeWithText("No offers yet").assertIsDisplayed()
    }
}
