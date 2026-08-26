package com.neop2p.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
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
    const val PROFILE = "profile"
    const val SETTINGS = "settings"
    const val ESCROW = "escrow/{escrowId}"
    const val ESCROW_RECEIPT = "escrow/{escrowId}/receipt"
    const val DISPUTE_EVIDENCE = "dispute_evidence/{escrowId}"
    const val WALLET = "wallet"
    const val DISPUTE_FEED = "dispute_feed"

    fun offerDetail(offerId: String) = "offer_detail/$offerId"
    fun editOffer(offerId: String) = "edit_offer/$offerId"
    fun chat(offerId: String, peerId: String) = "chat/$offerId/$peerId"
    fun escrow(escrowId: String) = "escrow/$escrowId"
    fun escrowReceipt(escrowId: String) = "escrow/$escrowId/receipt"
    fun disputeEvidence(escrowId: String) = "dispute_evidence/$escrowId"
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
    NavHost(
        navController = navController,
        startDestination = startDestination
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
                onProfileClick = { navController.navigate(Routes.PROFILE) },
                onSettingsClick = { navController.navigate(Routes.SETTINGS) },
                onChatClick = { offerId, peerId ->
                    navController.navigate(Routes.chat(offerId, peerId))
                },
                onEscrowClick = { escrowId ->
                    navController.navigate(Routes.escrow(escrowId))
                },
                onWalletClick = { navController.navigate(Routes.WALLET) }
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
                onEscrowCreated = { escrowId ->
                    navController.navigate(Routes.escrow(escrowId))
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
                onBack = { navController.popBackStack() }
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
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.WALLET) {
            WalletScreen(
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
                }
            )
        }

        composable(Routes.DISPUTE_FEED) {
            DisputeFeedScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
