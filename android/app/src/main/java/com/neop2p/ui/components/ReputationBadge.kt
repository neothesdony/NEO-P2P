package com.neop2p.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.neop2p.R
import com.neop2p.data.reputation.ReputationSystem.PeerReputation

/**
 * Counterparty reputation badge (Phase 3, 2026-09-23). Extracted from the
 * inline markup in OfferDetailScreen so the Trade Room can render the same
 * trust signal at match time. A null or zero-trade reputation renders a
 * "new trader" advisory instead of a misleading 0%.
 */
@Composable
fun ReputationBadge(
    reputation: PeerReputation?,
    modifier: Modifier = Modifier
) {
    if (reputation == null || reputation.totalTrades == 0) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small,
            modifier = modifier
        ) {
            Text(
                text = stringResource(R.string.reputation_new_trader),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
        return
    }
    val pct = (reputation.score * 100).toInt()
    val tier = when {
        reputation.score >= 0.9f -> R.string.reputation_excellent
        reputation.score >= 0.5f -> R.string.reputation_fair
        else -> R.string.reputation_poor
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(tier),
                style = MaterialTheme.typography.labelSmall
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "$pct% · " + stringResource(R.string.trades_suffix_format, reputation.totalTrades),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
