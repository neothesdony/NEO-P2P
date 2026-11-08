package com.neop2p.design

import com.neop2p.ui.design.DigitSlot
import com.neop2p.ui.design.digitSlots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DigitRollDiffTest {

    @Test
    fun unchanged_digits_stay_still() {
        val s = digitSlots("1.0000", "1.0001")
        assertEquals("1.0001", s.joinToString("") { it.char.toString() })
        assertEquals(1, s.count { it.changed })
    }

    @Test
    fun length_change_is_handled() {
        val s = digitSlots("999", "1.000")
        assertEquals("1.000", s.joinToString("") { it.char.toString() })
        assertEquals(5, s.size)
        // Every surviving position moved against its old neighbour, so all five
        // slots are new — including the separator, which was out of range before.
        assertTrue(s.all { it.changed })
    }

    @Test
    fun the_slots_are_the_new_value_even_when_shrinking() {
        val s = digitSlots("1.0000", "1.0")
        assertEquals("1.0", s.joinToString("") { it.char.toString() })
        assertEquals(3, s.size)
        // Right-aligned: the trailing '0' still lines up with the old trailing '0'.
        assertFalse(s.last().changed)
    }

    @Test
    fun identical_values_change_nothing() {
        val s = digitSlots("0.001", "0.001")
        assertTrue(s.none { it.changed })
    }

    @Test
    fun an_empty_previous_value_marks_every_slot() {
        val s = digitSlots("", "42")
        assertEquals(2, s.count { it.changed })
    }
}
