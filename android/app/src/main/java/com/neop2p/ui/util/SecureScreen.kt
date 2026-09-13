package com.neop2p.ui.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * Forces FLAG_SECURE while [content] is composed (audit P3-5, 2026-09-12).
 *
 * Scoped deliberately: the wallet screen stays screenshot-able (users send
 * their receive QR by screenshot), while the recovery phrase — which cannot be
 * rotated and is the whole wallet — cannot be captured or shown in the recents
 * thumbnail.
 *
 * Compose dialogs render in their OWN window, so [SecureScreen] must be
 * composed INSIDE the dialog content and the Activity is resolved through the
 * context chain (a dialog view's context is often a ContextWrapper, not the
 * Activity itself).
 */
@Composable
fun SecureScreen(content: @Composable () -> Unit) {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    content()
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
