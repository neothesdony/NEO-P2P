package com.neop2p.data.legal

import com.neop2p.NeoP2PConfig

/**
 * Versioned terms acceptance (Phase 3, 2026-09-23). The app stores the
 * highest accepted [NeoP2PConfig.TERMS_VERSION] on device; a bump forces
 * one re-acceptance before Home. Pure so it is JVM-testable.
 */
object TermsGate {
    fun needsAcceptance(
        acceptedVersion: Int,
        currentVersion: Int = NeoP2PConfig.TERMS_VERSION
    ): Boolean = acceptedVersion < currentVersion
}
