package com.neop2p.ui.util

import java.util.Locale

/**
 * PUEBI-compliant IDR formatting for Indonesian users.
 *
 * "Rp 1.250.000" — space after Rp, DOT as thousands separator, no decimals
 * for whole rupiah amounts. Never comma-thousands ("Rp 1,250,000" is the
 * en_US locale rendering and reads wrong to Indonesian users).
 *
 * See: BI style, PUEBI rule (dot thousands, comma decimals; trailing ",00"
 * is noise on whole amounts).
 */
fun formatIdr(amount: Long): String {
    val grouped = String.format(Locale.US, "%,d", amount).replace(",", ".")
    return "Rp $grouped"
}

/** Same dot-thousands grouping without the "Rp " prefix (price-per-unit rows). */
fun formatIdrNoCurrency(amount: Double): String =
    String.format(Locale.US, "%,.0f", amount).replace(",", ".")

/**
 * Compact countdown for offer expiry badges: mm:ss up to 59:59, then hh:mm.
 * Used by the home feed's "Berakhir dalam …" label (BasicSwap/RoboSats
 * expiry pattern). Never negative — clamps to 0.
 */
fun formatDurationShort(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d", hours, minutes)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

/**
 * Deterministic 3-digit unique payment code for an escrow.
 *
 * Indodax/Flip convention: the buyer transfers the fiat amount PLUS a unique
 * 3-digit tail, and the seller verifies the tail (bank notes fields are
 * unreliable across Indonesian bank apps; the amount is always readable).
 *
 * Derived from the escrowId + fiat amount so BOTH devices compute the SAME
 * code without any extra message: the escrowId syncs via kind:33337 and the
 * fiat amount lives on the offer row that precedes the escrow. Pure and
 * deterministic — a code that never changes mid-trade is also safer than a
 * random one (the buyer cannot "regenerate" it to dodge the check).
 */
fun uniquePaymentCode(escrowId: String, fiatAmount: Long): Int {
    val digits = escrowId.filter { it.isDigit() }.takeLast(3)
    val base = if (digits.length == 3) digits.toInt() else (fiatAmount % 1000).toInt()
    return base % 1000
}
