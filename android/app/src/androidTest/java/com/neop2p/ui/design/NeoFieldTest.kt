package com.neop2p.ui.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NeoFieldTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun error_text_is_announced() {
        compose.setContent {
            MaterialTheme { NeoTextField("", {}, "Harga", error = "Wajib diisi") }
        }
        compose.onNodeWithText("Wajib diisi").assertIsDisplayed()
    }

    @Test
    fun label_renders_without_an_error() {
        compose.setContent { MaterialTheme { NeoTextField("", {}, "Judul penawaran") } }
        compose.onNodeWithText("Judul penawaran").assertIsDisplayed()
    }

    @Test
    fun typed_text_reaches_the_callback() {
        var value = ""
        compose.setContent {
            NeoTextField(value, { value = it }, "Catatan")
        }
        compose.onNodeWithText("Catatan").performTextInput("halo")
        assertEquals("halo", value)
    }

    @Test
    fun money_field_strips_non_numeric_input() {
        var value = ""
        compose.setContent { NeoMoneyField(value, { value = it }, "Harga (IDR)", suffix = "IDR") }
        compose.onNodeWithText("Harga (IDR)").performTextInput("12a3.5")
        assertEquals("123.5", value)
    }

    @Test
    fun search_field_shows_its_placeholder() {
        compose.setContent { NeoSearchField("", {}, "Cari penawaran") }
        compose.onNodeWithText("Cari penawaran").assertIsDisplayed()
    }
}
