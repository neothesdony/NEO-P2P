package com.neop2p.navigation

/**
 * Routes that own the whole screen. Matched on the leading path segment so both the
 * destination pattern (`trade/{offerId}`) and a filled path (`trade/abc`) hide the
 * bar — the back stack reports the pattern, deep links report the path.
 */
private val IMMERSIVE_SEGMENTS = setOf(
    "escrow",
    "offer_detail",
    "create_offer",
    "edit_offer",
    "chat",
    "trade",
    "dispute_evidence"
)

/** Full-screen gates: onboarding and the versioned terms gate. */
private val FULLSCREEN_ROUTES = setOf(Routes.ONBOARDING, Routes.TERMS)

fun showsBottomBar(route: String?): Boolean {
    if (route == null) return true
    if (route in FULLSCREEN_ROUTES) return false
    val segment = route.substringBefore('/').substringBefore('?')
    return segment !in IMMERSIVE_SEGMENTS
}
