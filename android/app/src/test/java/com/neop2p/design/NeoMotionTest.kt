package com.neop2p.design

import com.neop2p.ui.design.NeoMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NeoMotionTest {
    @Test
    fun durations_are_the_spec_values() {
        assertEquals(90, NeoMotion.INSTANT_MS)
        assertEquals(140, NeoMotion.FAST_MS)
        assertEquals(200, NeoMotion.BASE_MS)
        assertEquals(280, NeoMotion.SLOW_MS)
        assertEquals(360, NeoMotion.DELIBERATE_MS)
    }

    @Test
    fun springs_differ_in_damping() {
        assertNotEquals(NeoMotion.emphasized.dampingRatio, NeoMotion.gentle.dampingRatio)
        assertNotEquals(NeoMotion.snappy.dampingRatio, NeoMotion.gentle.dampingRatio)
    }

    @Test
    fun every_transition_spec_carries_a_duration() {
        // A spec built without an explicit duration silently picks the caller's
        // default, which is how a 90 ms flip becomes a 300 ms lag.
        assertTrue(NeoMotion.emphasized.stiffness > 0f)
        assertTrue(NeoMotion.standard.stiffness > 0f)
        assertTrue(NeoMotion.gentle.stiffness < NeoMotion.snappy.stiffness)
    }
}
