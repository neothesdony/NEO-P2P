package com.neop2p.ui.components

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.neop2p.R
import com.neop2p.ui.design.NeoTheme

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
    // The app is edge-to-edge (MainActivity.enableEdgeToEdge), so the bar is
    // responsible for its own system-bar inset. Background first, then the
    // inset: the surface paints behind the navigation bar while the 64dp row
    // of tabs sits above it. Without this the tabs render underneath the
    // 3-button navigation bar and cannot be tapped at all.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        // The hairline is what separates the bar from content — no elevation tint.
        HorizontalDivider(thickness = 1.dp, color = NeoTheme.colors.hairline)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(64.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppTab.entries.forEach { tab ->
                val selected = tab == current
                val tint = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clickable(role = Role.Tab) { onTabSelected(tab) }
                        .padding(vertical = NeoTheme.spacing.sm)
                ) {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = stringResource(tab.cdRes),
                        tint = tint,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.height(NeoTheme.spacing.xxs))
                    Text(
                        text = stringResource(tab.labelRes),
                        style = MaterialTheme.typography.labelSmall,
                        color = tint,
                        maxLines = 1
                    )
                    Spacer(Modifier.height(NeoTheme.spacing.xxs))
                    // The active dot is the only decoration; inactive items get
                    // the same 4dp slot so nothing shifts when the tab changes.
                    Box(
                        Modifier
                            .size(4.dp)
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
                                CircleShape
                            )
                    )
                }
            }
        }
    }
}
