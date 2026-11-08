package com.neop2p.ui.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NeoToastTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun toast_shows_text_and_action() {
        val state = NeoToastState()
        compose.setContent { MaterialTheme { NeoToastHost(state) } }
        compose.runOnUiThread {
            state.show(text = "Escrow funded", kind = NeoToastKind.SUCCESS, actionLabel = "View", onAction = {})
        }
        compose.onNodeWithText("Escrow funded").assertIsDisplayed()
        compose.onNodeWithText("View").assertIsDisplayed()
    }

    @Test
    fun a_loading_toast_morphs_into_success_in_place() {
        val state = NeoToastState()
        compose.setContent { MaterialTheme { NeoToastHost(state) } }
        var id = 0L
        compose.runOnUiThread { id = state.show("Sending", NeoToastKind.LOADING) }
        compose.onNodeWithText("Sending").assertIsDisplayed()
        compose.runOnUiThread { state.update(id, NeoToastKind.SUCCESS, text = "Sent") }
        compose.onNodeWithText("Sent").assertIsDisplayed()
    }

    @Test
    fun dismissing_removes_the_toast() {
        val state = NeoToastState()
        compose.setContent { MaterialTheme { NeoToastHost(state) } }
        var id = 0L
        compose.runOnUiThread { id = state.show("Gone soon", NeoToastKind.INFO) }
        compose.onNodeWithText("Gone soon").assertIsDisplayed()
        compose.runOnUiThread { state.dismiss(id) }
        compose.onNodeWithText("Gone soon").assertDoesNotExist()
        assertEquals(0, state.messages.size)
    }

    @Test
    fun plain_text_toasts_need_no_action_slot() {
        val state = NeoToastState()
        compose.setContent { MaterialTheme { NeoToastHost(state) } }
        compose.runOnUiThread { state.show("Copied", NeoToastKind.INFO) }
        compose.onNodeWithText("Copied").assertIsDisplayed()
    }
}
