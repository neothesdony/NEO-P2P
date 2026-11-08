package com.neop2p.ui.design

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.neop2p.domain.model.EscrowStatus
import com.neop2p.ui.screens.escrow.EscrowStatusChip
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@Suppress("DEPRECATION") // EscrowStatus.RESOLVING is a DB-compat alias
@RunWith(AndroidJUnit4::class)
class NeoChipTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun every_status_chip_renders() {
        // Assert on tags, not labels: the label copy lives in string resources and
        // is allowed to change without breaking this gate.
        compose.setContent {
            NeoP2PTheme(mode = ThemeMode.DARK) {
                Column {
                    EscrowStatus.entries.forEach {
                        EscrowStatusChip(status = it, modifier = Modifier.testTag("chip_$it"))
                    }
                }
            }
        }
        EscrowStatus.entries.forEach {
            compose.onNodeWithTag("chip_$it").assertIsDisplayed()
        }
    }

    @Test
    fun a_status_chip_is_not_interactive() {
        compose.setContent {
            MaterialTheme {
                NeoStatusChip(
                    text = "FUNDED",
                    colors = NeoColors.defaultFrom(MaterialTheme.colorScheme).status.confirmed
                )
            }
        }
        compose.onNodeWithText("FUNDED").assertIsDisplayed()
    }

    @Test
    fun a_filter_chip_reports_its_click() {
        var picked = false
        compose.setContent {
            NeoFilterChip(text = "Bank Transfer", selected = true, onClick = { picked = true })
        }
        compose.onNodeWithText("Bank Transfer").performClick()
        assertTrue(picked)
    }

    @Test
    fun selected_and_unselected_chips_both_render() {
        compose.setContent {
            NeoP2PTheme(mode = ThemeMode.DARK) {
                Column {
                    NeoChip("On", selected = true)
                    NeoChip("Off", selected = false)
                }
            }
        }
        compose.onNodeWithText("On").assertIsDisplayed()
        compose.onNodeWithText("Off").assertIsDisplayed()
    }
}
