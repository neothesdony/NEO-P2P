package com.neop2p

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.navigation.NavHostController
import com.neop2p.data.p2p.IdentityManager
import com.neop2p.navigation.NeoP2PNavGraph
import com.neop2p.navigation.Routes
import com.neop2p.ui.theme.NeoP2PTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collect
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

    @Inject
    lateinit var peerRegistry: com.neop2p.data.p2p.store.PeerRegistry

    private var navController: NavHostController? = null
    private var pendingIntent: Intent? = null
    private var startDestination: String = Routes.ONBOARDING
    private var pendingInvitePeerId: String? = null

    /**
     * Snapshot-backed mirror of [navController] so a LaunchedEffect keyed on it
     * restarts once the graph becomes ready (the field is assigned inside the
     * onNavControllerReady callback, which plain remember would not observe).
     */
    private var navControllerReady by mutableStateOf<NavHostController?>(null)
        private set

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
        startDestination = if (com.neop2p.data.local.OnboardingGate.shouldShowOnboarding(
                identityManager.hasIdentity(),
                com.neop2p.data.local.OnboardingStore(applicationContext).isComplete()
            )
        ) {
            Routes.ONBOARDING
        } else {
            Routes.HOME
        }
        setContent {
            NeoP2PTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    NeoP2PNavGraph(
                        startDestination = startDestination,
                        onNavControllerReady = { controller ->
                            navController = controller
                            navControllerReady = controller
                            // A notification tap can arrive before the graph is
                            // composed (cold start); replay it once ready.
                            consumeNotificationIntent(pendingIntent ?: intent)
                            consumeInviteIntent(pendingIntent ?: intent)
                            pendingIntent = null
                        }
                    )
                }
                // Apply a queued invite once onboarding completes and Home is
                // reached (the replay bypasses the stale startDestination guard
                // — the invite was queued mid-onboarding). Keyed on the
                // snapshot-backed controller so the effect (re)starts as soon
                // as the graph is ready; recomposition after onboarding routes
                // HOME re-runs the collector.
                val readyController = navControllerReady
                LaunchedEffect(readyController) {
                    val controller = readyController ?: return@LaunchedEffect
                    snapshotFlow { controller.currentDestination?.route }
                        .collect { route ->
                            if (route == Routes.HOME) {
                                pendingInvitePeerId?.let {
                                    pendingInvitePeerId = null
                                    // keep the nav call off the collector
                                    runCatching { applyPendingInvite() }
                                }
                            }
                        }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (navController != null) {
            consumeNotificationIntent(intent)
            consumeInviteIntent(intent)
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
            route.startsWith("escrow/") ||
            route.startsWith("trade/")
    }

    /**
     * Handle a `neop2p://peer/<peerId>` invite link (tapped in WhatsApp, a
     * browser, or a QR scanner). Records the peer locally and lands on Home.
     * Ignored when the user has not finished onboarding (no identity yet) or
     * the link is malformed / self-referential.
     */
    private fun consumeInviteIntent(intent: Intent?) {
        if (intent == null) return
        val data = intent.data ?: return
        if (data.scheme != "neop2p" || data.host != "peer") return
        if (startDestination != Routes.HOME) {
            // Onboarding not finished: queue the invite and apply it once the
            // user completes onboarding and lands on Home.
            pendingInvitePeerId = data.toString()
            Toast.makeText(
                this,
                getString(com.neop2p.R.string.invite_queued_until_onboarding),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        applyPendingInvite()
    }

    /**
     * Apply a queued (or direct) invite: record the peer locally and land on
     * Home. Reads [pendingInvitePeerId] directly, so it is safe to call from
     * the replay hook without re-creating an Intent.
     */
    private fun applyPendingInvite() {
        val data = pendingInvitePeerId ?: return
        val parsed = com.neop2p.ui.screens.invite.InviteViewModel.parseInvite(data)
        if (parsed == null) {
            Log.w(TAG, "Ignoring malformed invite: $data")
            return
        }
        val peerId = parsed.first
        if (peerId == runCatching { identityManager.myPeerId() }.getOrNull()) {
            Log.w(TAG, "Ignoring self invite: $peerId")
            return
        }
        peerRegistry.recordPeerSeen(peerId)
        Toast.makeText(
            this,
            getString(com.neop2p.R.string.invite_deep_link_added, peerId.take(12)),
            Toast.LENGTH_SHORT
        ).show()
        val controller = navController ?: return
        if (controller.currentDestination?.route != Routes.HOME) {
            controller.navigate(Routes.HOME) {
                popUpTo(Routes.HOME) { inclusive = true }
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
