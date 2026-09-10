package com.neop2p.data.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingGateTest {

    @Test
    fun `first_launch_kill_after_generate_does_not_skip_seed_backup`() {
        // Identity exists but the user never finished backup/verify:
        // the app MUST return to onboarding, not home.
        assertTrue(OnboardingGate.shouldShowOnboarding(hasIdentity = true, onboardingComplete = false))
    }

    @Test
    fun `completed onboarding with identity goes home`() {
        assertFalse(OnboardingGate.shouldShowOnboarding(hasIdentity = true, onboardingComplete = true))
    }

    @Test
    fun `no identity always shows onboarding`() {
        assertTrue(OnboardingGate.shouldShowOnboarding(hasIdentity = false, onboardingComplete = true))
    }
}
