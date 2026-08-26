package com.neop2p.ui.screens.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import com.neop2p.R
import com.neop2p.data.local.dao.EscrowDao
import com.neop2p.data.local.toDomain
import com.neop2p.domain.model.Escrow
import com.neop2p.domain.model.EscrowStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
            Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.history_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(escrows, key = { it.escrowId }) { escrow ->
                    HistoryRow(escrow = escrow, onClick = { onEscrowClick(escrow.escrowId) })
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(escrow: Escrow, onClick: () -> Unit) {
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
                    text = "%.8f BTC".format(escrow.tradeAmountSats / 100_000_000.0),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = escrow.escrowId.take(16) + "…",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            StatusChip(escrow.status)
        }
    }
}

@Composable
private fun StatusChip(status: EscrowStatus, modifier: Modifier = Modifier) {
    val (container, content) = when (status) {
        EscrowStatus.FUNDING -> Color(0xFF854D0E) to Color(0xFFFCD34D)
        EscrowStatus.FUNDED -> Color(0xFF065F46) to Color(0xFF6EE7B7)
        EscrowStatus.PAYMENT_PENDING -> Color(0xFF78350F) to Color(0xFFFDE68A)
        EscrowStatus.RECEIPT_SENT -> Color(0xFF1E3A8A) to Color(0xFF93C5FD)
        EscrowStatus.SIGNED -> Color(0xFF1E3A8A) to Color(0xFF93C5FD)
        EscrowStatus.CONFIRMING -> Color(0xFF1E3A8A) to Color(0xFF93C5FD)
        EscrowStatus.RELEASED -> Color(0xFF065F46) to Color(0xFF6EE7B7)
        EscrowStatus.DISPUTED -> Color(0xFF7F1D1D) to Color(0xFFFCA5A5)
        EscrowStatus.RESOLVING -> Color(0xFF581C87) to Color(0xFFC084FC)
        EscrowStatus.CANCELLED -> Color(0xFF78350F) to Color(0xFFFDE68A)
        EscrowStatus.REFUNDED -> Color(0xFF1F2937) to Color(0xFFD1D5DB)
    }
    Surface(shape = RoundedCornerShape(50), color = container, modifier = modifier) {
        Text(
            text = stringResource(
                when (status) {
                    EscrowStatus.FUNDING -> R.string.escrow_status_pending
                    EscrowStatus.FUNDED -> R.string.escrow_status_funded
                    EscrowStatus.PAYMENT_PENDING -> R.string.escrow_status_payment_pending
                    EscrowStatus.RECEIPT_SENT -> R.string.escrow_status_receipt_sent
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
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@HiltViewModel
class HistoryViewModel @Inject constructor(
    escrowDao: EscrowDao
) : ViewModel() {
    val escrows: StateFlow<List<Escrow>> = escrowDao.getAllEscrows()
        .map { list -> list.map { it.toDomain() }.sortedByDescending { it.createdAt } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
