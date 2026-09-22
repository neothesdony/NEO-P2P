package com.neop2p.ui.util

import android.content.ClipData
import android.content.ClipboardManager
import android.os.PersistableBundle

/**
 * C6 (2026-09-23): copy a secret to the clipboard flagged as sensitive, so
 * Android does not surface it in the clipboard preview / autofill history.
 * Shared by the seed-reveal surfaces (onboarding + settings).
 */
fun ClipboardManager.copySensitive(label: String, text: String) {
    val clip = ClipData.newPlainText(label, text)
    clip.description.extras = PersistableBundle().apply {
        putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
    }
    setPrimaryClip(clip)
}
