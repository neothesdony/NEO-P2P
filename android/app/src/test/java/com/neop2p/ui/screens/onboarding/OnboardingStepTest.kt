package com.neop2p.ui.screens.onboarding

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T-08 (2026-09-15): the onboarding wizard has a fixed number of steps and the
 * progress indicator uses `entries.size`, so the dot count can never drift from
 * the wizard flow. Pin the count so adding/removing a step is deliberate.
 */
class OnboardingStepTest {

    @Test
    fun `onboarding wizard has seven steps`() {
        assertEquals(7, OnboardingStep.entries.size)
    }
}
