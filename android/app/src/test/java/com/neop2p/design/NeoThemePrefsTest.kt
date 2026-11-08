package com.neop2p.design

import com.neop2p.ui.design.ThemeMode
import com.neop2p.ui.design.resolveDarkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NeoThemePrefsTest {
    @Test
    fun resolution_table() {
        assertTrue(resolveDarkTheme(ThemeMode.SYSTEM, true))
        assertFalse(resolveDarkTheme(ThemeMode.SYSTEM, false))
        assertTrue(resolveDarkTheme(ThemeMode.DARK, false))
        assertTrue(resolveDarkTheme(ThemeMode.DARK, true))
        assertFalse(resolveDarkTheme(ThemeMode.LIGHT, true))
        assertFalse(resolveDarkTheme(ThemeMode.LIGHT, false))
    }

    @Test
    fun stored_names_are_stable() {
        // The persisted string is a data contract: renaming a member would silently
        // reset every existing user to SYSTEM.
        assertEquals("SYSTEM", ThemeMode.SYSTEM.name)
        assertEquals("DARK", ThemeMode.DARK.name)
        assertEquals("LIGHT", ThemeMode.LIGHT.name)
        assertEquals(ThemeMode.SYSTEM, ThemeMode.valueOf("SYSTEM"))
    }
}
