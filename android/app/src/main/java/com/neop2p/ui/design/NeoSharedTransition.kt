package com.neop2p.ui.design

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The nav host owns one `SharedTransitionLayout`; screens read the scope from here so
 * a card and its detail header can share elements without threading the scope through
 * every route signature.
 *
 * Null when a screen is composed outside the host (previews, tests) — callers must
 * degrade to a plain fade rather than crash.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }
