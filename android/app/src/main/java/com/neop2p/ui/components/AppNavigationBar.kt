package com.neop2p.ui.components

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.neop2p.R

/**
 * Top-level destinations (Now in Android pattern). The bottom bar shows
 * exactly these four; every other screen (escrow, chat, offer detail,
 * create offer, settings, dispute feed) is a pushed detail flow on top.
 */
enum class AppTab(
    val route: String,
    @StringRes val labelRes: Int,
    @StringRes val cdRes: Int,
    val icon: ImageVector
) {
    MARKET("home", R.string.tab_market, R.string.tab_cd_market, Icons.Filled.Storefront),
    WALLET("wallet", R.string.tab_wallet, R.string.tab_cd_wallet, Icons.Filled.AccountBalanceWallet),
    TRADES("trades", R.string.tab_trades, R.string.tab_cd_trades, Icons.AutoMirrored.Filled.ReceiptLong),
    PROFILE("profile", R.string.tab_profile, R.string.tab_cd_profile, Icons.Filled.Person);

    companion object {
        /**
         * Resolve the highlighted tab from a destination route. Uses
         * startsWith so detail routes nested under a tab (e.g. trades/…)
         * still highlight their parent — note the current implementation
         * pushes detail flows on top of the tabs instead of nesting them,
         * so the bar simply keeps the tab it was opened from.
         */
        fun fromRoute(route: String?): AppTab =
            entries.firstOrNull { route?.startsWith(it.route) == true } ?: MARKET
    }
}

@Composable
fun AppNavigationBar(
    current: AppTab,
    onTabSelected: (AppTab) -> Unit,
    modifier: Modifier = Modifier
) {
    NavigationBar(modifier = modifier) {
        AppTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab == current,
                onClick = { onTabSelected(tab) },
                icon = { Icon(tab.icon, contentDescription = stringResource(tab.cdRes)) },
                label = { Text(stringResource(tab.labelRes)) }
            )
        }
    }
}
