package com.neop2p.ui.screens.settings

import com.neop2p.ui.design.ThemeMode
import com.neop2p.ui.design.next
import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeModeSettingTest {

    @Test
    fun theme_setting_cycles_system_dark_light() {
        assertEquals(ThemeMode.DARK, ThemeMode.SYSTEM.next())
        assertEquals(ThemeMode.LIGHT, ThemeMode.DARK.next())
        assertEquals(ThemeMode.SYSTEM, ThemeMode.LIGHT.next())
    }

    @Test
    fun the_cycle_is_closed_and_visits_every_mode() {
        var mode = ThemeMode.SYSTEM
        val seen = mutableSetOf<ThemeMode>()
        repeat(ThemeMode.entries.size) {
            seen += mode
            mode = mode.next()
        }
        assertEquals(ThemeMode.entries.toSet(), seen)
        assertEquals(ThemeMode.SYSTEM, mode)
    }
}
