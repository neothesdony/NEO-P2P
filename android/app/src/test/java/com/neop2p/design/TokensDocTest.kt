package com.neop2p.design

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the design-token source of truth. The JSON file is not read at runtime —
 * every value is copied into `ui/design/` by hand — so this is the only thing that
 * keeps the two in sync.
 */
class TokensDocTest {
    @Test
    fun token_file_declares_the_neon_vault_palette() {
        // Gradle runs unit tests with the module dir as CWD: android/app
        val raw = File("../../design-system/design-tokens.json").readText()
        listOf("#07080B", "#00E676", "#22D3EE", "#F5A524", "#A78BFA").forEach {
            assertTrue("missing $it", raw.contains(it, ignoreCase = true))
        }
        assertFalse("GitHub background must be gone", raw.contains("#0D1117", ignoreCase = true))
    }
}
