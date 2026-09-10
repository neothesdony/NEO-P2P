package com.neop2p.ui.screens.home

/** What the Home screen should do about the POST_NOTIFICATIONS permission. */
enum class NotifRationaleDecision { NONE, SHOW_RATIONALE, REQUEST }

/**
 * One-time rationale gate: the system prompt is only shown after the user
 * has seen (and dismissed) the in-app explanation, so a reflexive denial
 * on first launch is less likely. Pure for unit testing.
 */
fun notifRationaleDecision(hasPermission: Boolean, rationaleShown: Boolean): NotifRationaleDecision = when {
    hasPermission -> NotifRationaleDecision.NONE
    !rationaleShown -> NotifRationaleDecision.SHOW_RATIONALE
    else -> NotifRationaleDecision.REQUEST
}
