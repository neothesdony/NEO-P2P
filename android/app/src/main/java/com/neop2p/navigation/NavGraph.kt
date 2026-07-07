package com.neop2p.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.neop2p.ui.screens.chat.ChatScreen
import com.neop2p.ui.screens.createoffer.CreateOfferScreen
import com.neop2p.ui.screens.dispute.DisputeEvidenceScreen
import com.neop2p.ui.screens.escrow.EscrowScreen
import com.neop2p.ui.screens.home.HomeScreen
import com.neop2p.ui.screens.offerdetail.OfferDetailScreen
import com.neop2p.ui.screens.onboarding.OnboardingScreen
import com.neop2p.ui.screens.profile.ProfileScreen
import com.neop2p.ui.screens.settings.SettingsScreen

object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val CREATE_OFFER = "create_offer"
    const val OFFER_DETAIL = "offer_detail/{offerId}"
    const val CHAT = "chat/{offerId}/{peerId}"
    const val ESCROW = "escrow/{escrowId}"
    const val DISPUTE_EVIDENCE = "dispute_evidence/{escrowId}/{submitterPeerId}"
    const val PROFILE = "profile"
    const val SETTINGS = "settings"

    fun offerDetail(offerId: String) = "offer_detail/$offerId"
    fun chat(offerId: String, peerId: String) = "chat/$offerId/$peerId"
    fun escrow(escrowId: String) = "escrow/$escrowId"
    fun disputeEvidence(escrowId: String, submitterPeerId: String) = "dispute_evidence/$escrowId/$submitterPeerId"
}

@Composable
fun NeoP2PNavGraph(
    navController: NavHostController = rememberNavController()
) {
    NavHost(
        navController = navController,
        startDestination = Routes.ONBOARDING
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
                onSettingsClick = { navController.navigate(Routes.SETTINGS) }
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
                }
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
                onEscrowCreated = { escrowId ->
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
                onComplete = { navController.popBackStack(Routes.HOME, false) },
                onEvidenceClick = { eid, pid ->
                    navController.navigate(Routes.disputeEvidence(eid, pid))
                }
            )
        }

        composable(
            route = Routes.DISPUTE_EVIDENCE,
            arguments = listOf(
                navArgument("escrowId") { type = NavType.StringType },
                navArgument("submitterPeerId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val escrowId = backStackEntry.arguments?.getString("escrowId") ?: return@composable
            val submitterPeerId = backStackEntry.arguments?.getString("submitterPeerId") ?: return@composable
            DisputeEvidenceScreen(
                escrowId = escrowId,
                submitterPeerId = submitterPeerId,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.PROFILE) {
            ProfileScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
