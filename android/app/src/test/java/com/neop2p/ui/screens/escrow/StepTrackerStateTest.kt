package com.neop2p.ui.screens.escrow

import org.junit.Assert.assertEquals
import org.junit.Test

class StepTrackerStateTest {

    @Test
    fun `past steps are done`() {
        assertEquals(StepVisual.DONE, stepVisual(index = 0, currentStep = 2))
    }

    @Test
    fun `current step is active`() {
        assertEquals(StepVisual.ACTIVE, stepVisual(index = 1, currentStep = 1))
    }

    @Test
    fun `future steps are pending`() {
        assertEquals(StepVisual.PENDING, stepVisual(index = 3, currentStep = 1))
    }

    @Test
    fun `first step active at start`() {
        assertEquals(StepVisual.ACTIVE, stepVisual(index = 0, currentStep = 0))
    }
}
