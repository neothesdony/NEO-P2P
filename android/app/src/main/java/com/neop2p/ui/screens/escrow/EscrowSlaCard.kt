package com.neop2p.ui.screens.escrow

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.neop2p.NeoP2PConfig
import com.neop2p.R
import java.text.DateFormat
import java.util.Date

/**
 * C-workstream (Phase 1): the arbitrator SLA + resolution window on a disputed
 * escrow, with the on-chain CLTV maturity as the ultimate fallback. Informational
 * — it never gates a money action.
 */
@Composable
fun EscrowSlaCard(
    disputedAtMs: Long,
    cltvLocktime: Long?,
    modifier: Modifier = Modifier,
    nowMs: Long = System.currentTimeMillis()
) {
    val elapsedHours = (nowMs - disputedAtMs).coerceAtLeast(0L) / 3_600_000L
    val overdue = elapsedHours >= NeoP2PConfig.ARBITRATOR_SLA_HOURS
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.escrow_sla_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.escrow_sla_elapsed, elapsedHours),
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = if (overdue) {
                    stringResource(R.string.escrow_sla_overdue, NeoP2PConfig.ARBITRATOR_SLA_HOURS)
                } else {
                    stringResource(R.string.escrow_sla_target, NeoP2PConfig.ARBITRATOR_SLA_HOURS)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.escrow_sla_window, NeoP2PConfig.RESOLUTION_WINDOW_HOURS),
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = cltvLocktime?.let {
                    stringResource(
                        R.string.escrow_sla_maturity,
                        DateFormat.getDateTimeInstance().format(Date(it * 1000L))
                    )
                } ?: stringResource(R.string.escrow_sla_maturity_legacy),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}
