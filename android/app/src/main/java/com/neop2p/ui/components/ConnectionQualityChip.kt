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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.neop2p.R
import com.neop2p.data.p2p.store.PeerRegistry.ConnectionQuality

/**
 * Per-peer connection quality chip (F05b).
 *
 * Honest about what the transport can do: RELAYED = messages travel via the
 * WS relay (may die mid-trade); DIRECT = libp2p secure session; OFFLINE = no
 * recent contact. There is NO holepunch pipeline in this stack (jvm-libp2p
 * has no DCUtR), so no "punching" state is shown.
 */
@Composable
fun ConnectionQualityChip(
    quality: ConnectionQuality,
    modifier: Modifier = Modifier
) {
    val (labelRes, color) = when (quality) {
        ConnectionQuality.DIRECT -> R.string.conn_direct to Color(0xFF2E7D32)
        ConnectionQuality.RELAYED -> R.string.conn_relayed to Color(0xFFF9A825)
        ConnectionQuality.OFFLINE -> R.string.conn_unreachable to Color(0xFF9E9E9E)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .background(
                color = color.copy(alpha = 0.12f),
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
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
