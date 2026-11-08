package com.neop2p.design

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fonts are bundled, not downloaded at runtime, so a missing or truncated
 * file fails here instead of on a user's device.
 */
class NeoFontsTest {
    @Test
    fun every_font_resource_exists() {
        val expected = listOf(
            "space_grotesk_medium", "space_grotesk_bold",
            "inter_regular", "inter_medium", "inter_semibold",
            "jetbrains_mono_regular", "jetbrains_mono_medium"
        )
        expected.forEach {
            val f = File("src/main/res/font/$it.ttf")
            assertTrue("missing $it", f.exists() && f.length() > 20_000)
        }
    }

    @Test
    fun total_font_payload_stays_under_the_budget() {
        // Spec §5.2: seven static files ≈ 700 KB. The APK accepts it; a regression
        // that adds a family or an extra weight should surface here.
        val total = File("src/main/res/font").listFiles()?.filter { it.extension == "ttf" }
            ?.sumOf { it.length() } ?: 0L
        assertTrue("font payload is ${total / 1024} KB", total in 1..(800 * 1024))
    }
}
