package com.neop2p.data.local

/**
 * Pure start-destination policy. The seed-backup step is the ONLY durable
 * proof that the user can recover their identity; an identity that exists
 * but was never backed up must return to onboarding (a kill between
 * generate and verify must not skip the backup screen).
 */
object OnboardingGate {
    fun shouldShowOnboarding(hasIdentity: Boolean, onboardingComplete: Boolean): Boolean =
        !(hasIdentity && onboardingComplete)
}
