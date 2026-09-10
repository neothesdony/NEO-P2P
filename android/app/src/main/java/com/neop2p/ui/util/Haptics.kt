package com.neop2p.ui.util

import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType

/** Money actions that deserve a physical tick. */
enum class MoneyAction { FUND, MARK_PAID, CONFIRM_RELEASE, DISPUTE, SEND_BTC }

/** Irreversible broadcasts get the strongest tick; state transitions get Confirm. */
internal fun hapticFor(action: MoneyAction): HapticFeedbackType = when (action) {
    MoneyAction.FUND, MoneyAction.SEND_BTC, MoneyAction.DISPUTE -> HapticFeedbackType.LongPress
    MoneyAction.MARK_PAID, MoneyAction.CONFIRM_RELEASE -> HapticFeedbackType.Confirm
}

fun HapticFeedback.moneyAction(action: MoneyAction) {
    performHapticFeedback(hapticFor(action))
}
