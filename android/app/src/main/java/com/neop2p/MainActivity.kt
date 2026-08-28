package com.neop2p

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.navigation.NavHostController
import com.neop2p.data.p2p.IdentityManager
import com.neop2p.navigation.NeoP2PNavGraph
import com.neop2p.navigation.Routes
import com.neop2p.ui.theme.NeoP2PTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Deep-link entry point for notification taps.
 *
 * [NotificationDispatcher] builds its content intents with the target route
 * string in [Intent.EXTRA_TEXT] (a route like "chat/{offerId}/{peerId}" or
 * "escrow/{escrowId}" — the ids are path segments, so the deep link carries
 * everything needed). This activity consumes that route both on cold start
 * ([onCreate] via the nav-graph-ready callback) and on warm start
 * ([onNewIntent]) and navigates to the matching screen.
 *
 * Unknown or malformed routes are ignored and the app falls back to the
 * default start destination (home / onboarding).
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject
    lateinit var identityManager: IdentityManager

    @Inject
    lateinit var localeStore: com.neop2p.data.local.LocaleStore

    private var navController: NavHostController? = null
    private var pendingIntent: Intent? = null

    /**
     * Apply the per-app language override (system / id / en) before any
     * resource lookup. FragmentActivity has no AppCompatDelegate, so the
     * locale is applied via a Configuration override — the standard Compose
     * pattern for non-AppCompat activities.
     */
    override fun attachBaseContext(newBase: android.content.Context) {
        val code = runCatching { localeStore.locale() }.getOrDefault("system")
        val base = if (code == "id" || code == "en") {
            val config = android.content.res.Configuration(newBase.resources.configuration)
            config.setLocale(java.util.Locale(code))
            newBase.createConfigurationContext(config)
        } else {
            newBase
        }
        super.attachBaseContext(base)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NeoP2PTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    NeoP2PNavGraph(
                        startDestination = if (identityManager.hasIdentity()) {
                            Routes.HOME
                        } else {
                            Routes.ONBOARDING
                        },
                        onNavControllerReady = { controller ->
                            navController = controller
                            // A notification tap can arrive before the graph is
                            // composed (cold start); replay it once ready.
                            consumeNotificationIntent(pendingIntent ?: intent)
                            pendingIntent = null
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (navController != null) {
            consumeNotificationIntent(intent)
        } else {
            pendingIntent = intent
        }
    }

    /**
     * Navigate to the route carried in [Intent.EXTRA_TEXT]. The dispatcher
     * sends concrete route strings (e.g. "offer_detail/offer_123" or
     * "chat/offer_123/12D3KooW...") that match the composable route patterns
     * registered in [NeoP2PNavGraph] directly.
     *
     * NOTE: this navigates by route string, NOT via [NavDeepLinkRequest] —
     * no destination registers `deepLinks`, so a deep-link request can never
     * match and navigation fails ("Navigation destination that matches
     * request ... cannot be found"). Route-string navigation resolves the
     * `{offerId}`-style placeholders from the path segments.
     */
    private fun consumeNotificationIntent(intent: Intent?) {
        if (intent == null) return
        val controller = navController ?: return
        val route = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        if (!isKnownRoute(route)) {
            Log.w(TAG, "Ignoring unknown notification route: $route")
            return
        }
        try {
            controller.navigate(route) {
                // Tapping a notification for a screen already on top (e.g. the
                // open chat) must not push a duplicate on the back stack.
                launchSingleTop = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Deep link navigation failed for $route: ${e.message}")
        }
    }

    private fun isKnownRoute(route: String): Boolean {
        return route == Routes.HOME ||
            route == Routes.CREATE_OFFER ||
            route == Routes.PROFILE ||
            route == Routes.SETTINGS ||
            route == Routes.WALLET ||
            route == Routes.TRADES ||
            route.startsWith("offer_detail/") ||
            route.startsWith("edit_offer/") ||
            route.startsWith("chat/") ||
            route.startsWith("escrow/")
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
