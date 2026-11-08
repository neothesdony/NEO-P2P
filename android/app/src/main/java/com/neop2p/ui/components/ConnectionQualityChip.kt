package com.neop2p.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.neop2p.R
import com.neop2p.data.p2p.store.PeerRegistry.ConnectionQuality
import com.neop2p.ui.design.NeoTheme

/**
 * Per-peer connection quality chip (F05b).
 *
 * Honest about what the transport can do: DIRECT = live libp2p secure session;
 * RELAYED = messages travel via the WS relay (may die mid-trade); RECONNECTING =
 * the relay dropped and is backing off; RELAY_QUOTA = the relay refused
 * delivery to this peer; CONNECTING = first contact, no transport evidence yet;
 * OFFLINE = no recent contact. There is NO holepunch pipeline in this stack
 * (jvm-libp2p has no DCUtR), so no "punching" state is shown.
 */
@Composable
fun ConnectionQualityChip(
    quality: ConnectionQuality,
    modifier: Modifier = Modifier
) {
    // Six states, four families: "live" is confirmed, "degraded" is the amber
    // action-required family, "refused" is danger, "unknown" is neutral.
    val (labelRes, family) = when (quality) {
        ConnectionQuality.DIRECT -> R.string.conn_direct to NeoTheme.colors.status.confirmed
        ConnectionQuality.RELAYED -> R.string.conn_relayed to NeoTheme.colors.status.actionRequired
        ConnectionQuality.RECONNECTING -> R.string.conn_reconnecting to NeoTheme.colors.status.actionRequired
        ConnectionQuality.RELAY_QUOTA -> R.string.conn_relay_quota to NeoTheme.colors.status.danger
        ConnectionQuality.CONNECTING -> R.string.conn_checking to NeoTheme.colors.status.neutral
        ConnectionQuality.OFFLINE -> R.string.conn_unreachable to NeoTheme.colors.status.neutral
    }
    val color = family.content
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .background(
                color = family.container,
                shape = RoundedCornerShape(50)
            )
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Spacer(
            Modifier
                .size(6.dp)
                .background(color = color, shape = CircleShape)
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}
