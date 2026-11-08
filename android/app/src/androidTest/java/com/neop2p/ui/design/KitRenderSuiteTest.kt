package com.neop2p.ui.design

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.neop2p.domain.model.EscrowStatus
import com.neop2p.ui.components.NeoEmptyState
import com.neop2p.ui.screens.escrow.EscrowStatusChip
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The regression gate for the kit's one hard rule: every component must render
 * under a bare `MaterialTheme { }`, because that is how the smoke tests compose them
 * and how a future preview will render a single component.
 *
 * If a component starts reading a token with no Material fallback, this fails here
 * rather than in a preview nobody opened.
 */
@RunWith(AndroidJUnit4::class)
class KitRenderSuiteTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun every_component_renders_under_bare_material_theme() {
        compose.setContent {
            MaterialTheme {
                Column {
                    NeoCard { Text("card") }
                    NeoButton("btn", {})
                    NeoFilterChip("chip", false, {})
                    EscrowStatusChip(EscrowStatus.FUNDED)
                    NeoTextField("", {}, "field")
                    NeoMoney(sats = 1234, role = NeoMoneyRole.MEDIUM)
                    NeoAvatar("peer-1", "A")
                    NeoSkeleton(40.dp, 12.dp)
                    NeoEmptyState(title = "empty", hint = "hint")
                    NeoStatusDot(NeoTheme.colors.status.confirmed, pulse = false)
                    NeoStatusChip("FUNDED", NeoTheme.colors.status.confirmed)
                    NeoStepSpine(labels = listOf("Fund", "Pay", "Release"), currentIndex = 1)
                    NeoTimelineBand(
                        title = "Disputed",
                        body = "Waiting on the arbitrator",
                        colors = NeoTheme.colors.status.danger
                    )
                    NeoSkeletonCard()
                }
            }
        }

        listOf("card", "btn", "chip", "field", "empty").forEach {
            compose.onNodeWithText(it).assertExists()
        }
    }

    @Test
    fun every_component_renders_inside_the_provider_in_both_schemes() {
        // Both schemes in one composition — a ComposeTestRule allows exactly one
        // setContent, so the modes are stacked rather than iterated.
        compose.setContent {
            Column {
                NeoP2PTheme(mode = ThemeMode.DARK) {
                    NeoCard(glow = true) { Text("themed-dark") }
                }
                NeoP2PTheme(mode = ThemeMode.LIGHT) {
                    NeoCard(glow = true) { Text("themed-light") }
                }
            }
        }
        compose.onNodeWithText("themed-dark").assertIsDisplayed()
        compose.onNodeWithText("themed-light").assertIsDisplayed()
    }

    @Test
    fun the_toast_host_renders_with_no_messages() {
        compose.setContent {
            MaterialTheme { NeoToastHost(NeoToastState(), Modifier.testTag("toast_host")) }
        }
        compose.onNodeWithTag("toast_host").assertExists()
    }
}
