package com.neop2p.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.neop2p.ui.components.AppNavigationBar
import com.neop2p.ui.components.AppTab
import com.neop2p.ui.theme.NeoMotion
import com.neop2p.ui.screens.chat.ChatScreen
import com.neop2p.ui.screens.createoffer.CreateOfferScreen
import com.neop2p.ui.screens.createoffer.EditOfferScreen
import com.neop2p.ui.screens.escrow.EscrowScreen
import com.neop2p.ui.screens.escrow.ReceiptComposerScreen
import com.neop2p.ui.screens.escrow.DisputeEvidenceScreen
import com.neop2p.ui.screens.escrow.DisputeFeedScreen
import com.neop2p.ui.screens.home.HomeScreen
import com.neop2p.ui.screens.offerdetail.OfferDetailScreen
import com.neop2p.ui.screens.onboarding.OnboardingScreen
import com.neop2p.ui.screens.profile.ProfileScreen
import com.neop2p.ui.screens.settings.SettingsScreen
import com.neop2p.ui.screens.wallet.WalletScreen

object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val CREATE_OFFER = "create_offer"
    const val OFFER_DETAIL = "offer_detail/{offerId}"
    const val EDIT_OFFER = "edit_offer/{offerId}"
    const val CHAT = "chat/{offerId}/{peerId}"
    const val TRADE_ROOM = "trade/{offerId}"
    const val PROFILE = "profile"
    const val SETTINGS = "settings"
    const val OEM_NOTIFICATIONS = "settings/oem_notifications"
    const val INVITE = "invite"
    const val ESCROW = "escrow/{escrowId}"
    const val ESCROW_RECEIPT = "escrow/{escrowId}/receipt"
    const val DISPUTE_EVIDENCE = "dispute_evidence/{escrowId}"
    const val WALLET = "wallet"
    const val DISPUTE_FEED = "dispute_feed"
    const val HISTORY = "history"
    const val TRADES = "trades"

    fun offerDetail(offerId: String) = "offer_detail/$offerId"
    fun editOffer(offerId: String) = "edit_offer/$offerId"
    fun chat(offerId: String, peerId: String) = "chat/$offerId/$peerId"
    fun escrow(escrowId: String) = "escrow/$escrowId"
    fun escrowReceipt(escrowId: String) = "escrow/$escrowId/receipt"
    fun disputeEvidence(escrowId: String) = "dispute_evidence/$escrowId"
    fun tradeRoom(offerId: String) = "trade/$offerId"
}

@Composable
fun NeoP2PNavGraph(
    startDestination: String = Routes.ONBOARDING,
    navController: NavHostController = rememberNavController(),
    onNavControllerReady: (NavHostController) -> Unit = {}
) {
    // Surface the controller once composition completes (NavHost has
    // registered its destinations) so callers — e.g. MainActivity notification
    // deep links — can navigate without racing the graph setup.
    LaunchedEffect(navController) {
        onNavControllerReady(navController)
    }

    // Track the active top-level tab from the back stack so the bottom bar
    // highlights the destination the user is on.
    val backStackEntry by navController.currentBackStackEntryAsState()
    var currentTab by remember { mutableStateOf(AppTab.MARKET) }
    LaunchedEffect(backStackEntry) {
        currentTab = AppTab.fromRoute(backStackEntry?.destination?.route)
    }

    fun switchTab(tab: AppTab) {
        val currentRoute = backStackEntry?.destination?.route
        if (currentRoute?.startsWith(tab.route) == true) return
        navController.navigate(tab.route) {
            // Standard bottom-nav pattern: pop everything above the start
            // destination, save/restore each tab's own stack.
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        bottomBar = {
            // Onboarding is a full-screen flow — no bottom bar there.
            if (backStackEntry?.destination?.route != Routes.ONBOARDING) {
                AppNavigationBar(current = currentTab, onTabSelected = ::switchTab)
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(innerPadding),
            enterTransition = { NeoMotion.fadeIn + NeoMotion.slideUp },
            exitTransition = { NeoMotion.fadeOut },
            popEnterTransition = { NeoMotion.fadeIn },
            popExitTransition = {
                scaleOut(
                    targetScale = 0.9f,
                    animationSpec = tween(220, easing = NeoMotion.emphasizedEase),
                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                )
            }
        ) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                onOnboardingComplete = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.HOME) {
            HomeScreen(
                onCreateOffer = { navController.navigate(Routes.CREATE_OFFER) },
                onOfferClick = { offerId ->
                    navController.navigate(Routes.offerDetail(offerId))
                },
                onChatClick = { offerId, peerId ->
                    navController.navigate(Routes.chat(offerId, peerId))
                },
                onEscrowClick = { escrowId ->
                    navController.navigate(Routes.escrow(escrowId))
                },
                onNavigate = ::switchTab,
                onOpenOemNotifications = { navController.navigate(Routes.OEM_NOTIFICATIONS) },
                onInvite = { navController.navigate(Routes.INVITE) }
            )
        }

        composable(Routes.HISTORY) {
            com.neop2p.ui.screens.history.HistoryScreen(
                onEscrowClick = { escrowId ->
                    navController.navigate(Routes.escrow(escrowId))
                },
                onTradeRoomClick = { offerId ->
                    navController.navigate(Routes.tradeRoom(offerId))
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.CREATE_OFFER) {
            CreateOfferScreen(
                onOfferCreated = { navController.popBackStack() },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.OFFER_DETAIL,
            arguments = listOf(
                navArgument("offerId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val offerId = backStackEntry.arguments?.getString("offerId") ?: return@composable
            OfferDetailScreen(
                offerId = offerId,
                onBack = { navController.popBackStack() },
                onChatClick = { oid, pid ->
                    navController.navigate(Routes.chat(oid, pid))
                },
                onTradeStarted = { offerId ->
                    navController.navigate(Routes.tradeRoom(offerId))
                },
                onEdit = { navController.navigate(Routes.editOffer(offerId)) }
            )
        }

        composable(
            route = Routes.EDIT_OFFER,
            arguments = listOf(
                navArgument("offerId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val offerId = backStackEntry.arguments?.getString("offerId") ?: return@composable
            EditOfferScreen(
                offerId = offerId,
                onBack = { navController.popBackStack() },
                onEditSaved = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.CHAT,
            arguments = listOf(
                navArgument("offerId") { type = NavType.StringType },
                navArgument("peerId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val offerId = backStackEntry.arguments?.getString("offerId") ?: return@composable
            val peerId = backStackEntry.arguments?.getString("peerId") ?: return@composable
            ChatScreen(
                offerId = offerId,
                peerId = peerId,
                onBack = { navController.popBackStack() },
                onOpenEscrow = { escrowId ->
                    navController.navigate(Routes.escrow(escrowId))
                }
            )
        }

        composable(
            route = Routes.ESCROW,
            arguments = listOf(
                navArgument("escrowId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val escrowId = backStackEntry.arguments?.getString("escrowId") ?: return@composable
            EscrowScreen(
                escrowId = escrowId,
                onBack = { navController.popBackStack() },
                onComplete = { navController.popBackStack() },
                onEvidenceClick = { eid ->
                    navController.navigate(Routes.disputeEvidence(eid))
                },
                onOpenReceipt = { eid ->
                    navController.navigate(Routes.escrowReceipt(eid))
                }
            )
        }

        composable(
            route = Routes.DISPUTE_EVIDENCE,
            arguments = listOf(
                navArgument("escrowId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val escrowId = backStackEntry.arguments?.getString("escrowId") ?: return@composable
            DisputeEvidenceScreen(
                escrowId = escrowId,
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.ESCROW_RECEIPT,
            arguments = listOf(
                navArgument("escrowId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val escrowId = backStackEntry.arguments?.getString("escrowId") ?: return@composable
            ReceiptComposerScreen(
                escrowId = escrowId,
                onBack = { navController.popBackStack() },
                onSent = { navController.popBackStack() }
            )
        }

        composable(Routes.PROFILE) {
            ProfileScreen(
                onBack = { navController.popBackStack() },
                onSettingsClick = { navController.navigate(Routes.SETTINGS) },
                onInviteClick = { navController.navigate(Routes.INVITE) }
            )
        }

        composable(Routes.INVITE) {
            com.neop2p.ui.screens.invite.InviteScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.WALLET) {
            WalletScreen(
                onBack = { navController.popBackStack() }
            )
        }

        // Trades tab: alias of HistoryScreen (the /history deep-link route
        // stays for notification/back-compat). In-flight rows open the trade
        // hub; terminal rows open the escrow detail.
        composable(Routes.TRADES) {
            com.neop2p.ui.screens.history.HistoryScreen(
                onEscrowClick = { escrowId ->
                    navController.navigate(Routes.escrow(escrowId))
                },
                onTradeRoomClick = { offerId ->
                    navController.navigate(Routes.tradeRoom(offerId))
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onIdentityReset = {
                    // Identity wiped: return to onboarding so the user
                    // sets up a fresh identity.
                    navController.navigate(Routes.ONBOARDING) {
                        popUpTo(Routes.HOME) { inclusive = true }
                    }
                },
                onArbitratorFeed = {
                    navController.navigate(Routes.DISPUTE_FEED)
                },
                onOemNotificationsClick = { navController.navigate(Routes.OEM_NOTIFICATIONS) }
            )
        }

        composable(Routes.OEM_NOTIFICATIONS) {
            com.neop2p.ui.screens.settings.OemNotificationHelpScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.DISPUTE_FEED) {
            DisputeFeedScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.TRADE_ROOM,
            arguments = listOf(navArgument("offerId") { type = NavType.StringType })
        ) { backStackEntry ->
            val offerId = backStackEntry.arguments?.getString("offerId") ?: return@composable
            com.neop2p.ui.screens.trade.TradeRoomScreen(
                offerId = offerId,
                onBack = { navController.popBackStack() },
                onOpenEscrow = { escrowId -> navController.navigate(Routes.escrow(escrowId)) },
                onOpenChat = { oid, pid -> navController.navigate(Routes.chat(oid, pid)) },
                onOpenReceipt = { eid -> navController.navigate(Routes.escrowReceipt(eid)) }
            )
        }
        }
    }
}
