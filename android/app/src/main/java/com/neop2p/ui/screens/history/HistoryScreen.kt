package com.neop2p.ui.screens.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import com.neop2p.ui.components.NeoEmptyState
import com.neop2p.ui.theme.escrowStatusColors
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import com.neop2p.R
import com.neop2p.data.local.dao.EscrowDao
import com.neop2p.data.local.toDomain
import com.neop2p.domain.model.Escrow
import com.neop2p.domain.model.EscrowStatus
import com.neop2p.ui.util.formatBtc
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Trade history: every escrow this device participated in (buyer or seller),
 * newest first, with a status chip. Tapping a row opens the existing
 * EscrowDetail screen. Terminal trades (RELEASED/REFUNDED/CANCELLED) and
 * in-flight ones (FUNDING/FUNDED/…) are all listed — nothing is deleted.
 */
@Composable
fun HistoryScreen(
    onEscrowClick: (String) -> Unit,
    onBack: () -> Unit,
    onTabChange: (com.neop2p.ui.components.AppTab) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val viewModel: HistoryViewModel = hiltViewModel()
    val escrows by viewModel.escrows.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.history_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.general_back)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        if (escrows.isEmpty()) {
            NeoEmptyState(
                icon = Icons.AutoMirrored.Filled.ReceiptLong,
                title = stringResource(R.string.history_empty),
                modifier = Modifier.fillMaxSize().padding(innerPadding)
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(escrows, key = { it.escrow.escrowId }) { row ->
                    HistoryRow(escrow = row.escrow, fiatAmount = row.fiatAmount, onClick = { onEscrowClick(row.escrow.escrowId) })
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(escrow: Escrow, fiatAmount: Long?, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(
                        when (escrow.status) {
                            EscrowStatus.FUNDING, EscrowStatus.FUNDED,
                            EscrowStatus.PAYMENT_PENDING, EscrowStatus.RECEIPT_SENT,
                            EscrowStatus.CONFIRMING, EscrowStatus.SIGNED -> R.string.history_active
                            EscrowStatus.RELEASED -> R.string.history_success
                            EscrowStatus.DISPUTED, EscrowStatus.RESOLVING -> R.string.history_dispute
                            EscrowStatus.CANCELLED, EscrowStatus.REFUNDED -> R.string.history_cancelled
                        }
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.common_btc_amount, formatBtc(escrow.tradeAmountSats)),
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace
                )
                if (fiatAmount != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.home_fiat_amount, fiatAmount),
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.history_date, formatDate(escrow.createdAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            StatusChip(escrow.status)
        }
    }
}

private fun formatDate(epochMillis: Long): String =
    SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(epochMillis))

@Composable
private fun StatusChip(status: EscrowStatus, modifier: Modifier = Modifier) {
    val (container, content) = MaterialTheme.colorScheme.escrowStatusColors(status)
    Surface(shape = CircleShape, color = container, modifier = modifier) {
        Text(
            text = stringResource(
                when (status) {
                    EscrowStatus.FUNDING -> R.string.escrow_status_pending
                    EscrowStatus.FUNDED -> R.string.escrow_status_funded
                    EscrowStatus.PAYMENT_PENDING -> R.string.escrow_chip_payment_pending
                    EscrowStatus.RECEIPT_SENT -> R.string.escrow_chip_receipt_sent
                    EscrowStatus.SIGNED -> R.string.escrow_status_signed
                    EscrowStatus.CONFIRMING -> R.string.escrow_paid_status
                    EscrowStatus.RELEASED -> R.string.profile_completed
                    EscrowStatus.DISPUTED -> R.string.escrow_status_disputed
                    EscrowStatus.RESOLVING -> R.string.escrow_status_resolving
                    EscrowStatus.CANCELLED -> R.string.escrow_status_cancelled
                    EscrowStatus.REFUNDED -> R.string.escrow_status_refunded
                }
            ),
            style = MaterialTheme.typography.labelMedium,
            color = content,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@HiltViewModel
class HistoryViewModel @Inject constructor(
    escrowDao: EscrowDao
) : ViewModel() {
    val escrows: StateFlow<List<HistoryRowData>> = escrowDao.getAllEscrowsWithFiat()
        .map { list ->
            list.map { row ->
                HistoryRowData(
                    escrow = row.escrow.toDomain(),
                    fiatAmount = row.offerFiatAmount
                )
            }.sortedByDescending { it.escrow.createdAt }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

/** Escrow plus the fiat amount of its originating offer (may be null if the offer row is gone). */
data class HistoryRowData(
    val escrow: Escrow,
    val fiatAmount: Long?
)
