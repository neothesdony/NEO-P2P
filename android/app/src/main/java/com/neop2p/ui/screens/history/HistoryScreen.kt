package com.neop2p.ui.screens.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import com.neop2p.ui.components.MoneyText
import com.neop2p.ui.components.NeoEmptyState
import com.neop2p.ui.theme.escrowStatusColors
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Search
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
    onTradeRoomClick: (String) -> Unit = {},
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val viewModel: HistoryViewModel = hiltViewModel()
    val escrows by viewModel.escrows.collectAsStateWithLifecycle()
    // Local search: TradeID (escrowId), offer id, or payment reference /
    // kode unik. Pure in-memory filter over the already-loaded list.
    var query by rememberSaveable { mutableStateOf("") }
    val q = query.trim()
    val filtered = remember(escrows, q) {
        if (q.isEmpty()) escrows
        else escrows.filter { row ->
            row.escrow.escrowId.contains(q, ignoreCase = true) ||
                row.escrow.offerId.contains(q, ignoreCase = true) ||
                row.escrow.receiptReference?.contains(q, ignoreCase = true) == true
        }
    }

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
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            // Search by TradeID / kode unik / reference (F14 must-have).
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.history_search_hint)) },
                leadingIcon = {
                    Icon(Icons.Filled.Search, contentDescription = null)
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
            if (escrows.isEmpty()) {
                NeoEmptyState(
                    icon = Icons.AutoMirrored.Filled.ReceiptLong,
                    title = stringResource(R.string.history_empty),
                    modifier = Modifier.fillMaxSize()
                )
            } else if (filtered.isEmpty()) {
                // Search active but nothing matches — distinct from "no trades".
                NeoEmptyState(
                    icon = Icons.Filled.Search,
                    title = stringResource(R.string.history_search_empty),
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                val needsAction = filtered.filter { it.needsMyAction }
                val waiting = filtered.filter { !it.needsMyAction && !isTerminal(it.escrow.status) }
                val done = filtered.filter { isTerminal(it.escrow.status) }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (needsAction.isNotEmpty()) {
                        item(key = "header_action") {
                            Text(
                                stringResource(R.string.history_section_action),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        items(needsAction, key = { "a_" + it.escrow.escrowId }) { row ->
                            Box(Modifier.animateItem()) {
                                HistoryRow(escrow = row.escrow, fiatAmount = row.fiatAmount, onClick = { onRowClick(row, onEscrowClick, onTradeRoomClick) })
                            }
                        }
                    }
                    if (waiting.isNotEmpty()) {
                        item(key = "header_waiting") {
                            Text(
                                stringResource(R.string.history_section_waiting),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        items(waiting, key = { "w_" + it.escrow.escrowId }) { row ->
                            Box(Modifier.animateItem()) {
                                HistoryRow(escrow = row.escrow, fiatAmount = row.fiatAmount, onClick = { onRowClick(row, onEscrowClick, onTradeRoomClick) })
                            }
                        }
                    }
                    if (done.isNotEmpty()) {
                        item(key = "header_done") {
                            Text(
                                stringResource(R.string.history_section_done),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        items(done, key = { "d_" + it.escrow.escrowId }) { row ->
                            Box(Modifier.animateItem()) {
                                HistoryRow(escrow = row.escrow, fiatAmount = row.fiatAmount, onClick = { onRowClick(row, onEscrowClick, onTradeRoomClick) })
                            }
                        }
                    }
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
                MoneyText(
                    text = stringResource(R.string.common_btc_amount, formatBtc(escrow.tradeAmountSats)),
                    style = MaterialTheme.typography.titleMedium
                )
                if (fiatAmount != null) {
                    Spacer(Modifier.height(2.dp))
                    MoneyText(
                        text = stringResource(R.string.home_fiat_amount, fiatAmount),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // Kode unik + total: the buyer recognizes the trade by the
                    // exact transfer amount (amount + 3-digit code) before
                    // opening the detail — no "which trade was this?" guessing.
                    val code = com.neop2p.ui.util.uniquePaymentCode(escrow.escrowId, fiatAmount)
                    MoneyText(
                        text = stringResource(R.string.history_kode_unik, code.toString()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
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

// In-flight trades open the trade hub (status header + pay card + chat
// shortcut); terminal ones go straight to the escrow detail.
private fun onRowClick(row: HistoryRowData, onEscrowClick: (String) -> Unit, onTradeRoomClick: (String) -> Unit) {
    if (isTerminal(row.escrow.status)) {
        onEscrowClick(row.escrow.escrowId)
    } else {
        onTradeRoomClick(row.escrow.offerId)
    }
}

private fun isTerminal(status: EscrowStatus): Boolean =
    status == EscrowStatus.RELEASED ||
        status == EscrowStatus.REFUNDED ||
        status == EscrowStatus.CANCELLED ||
        status == EscrowStatus.DISPUTED ||
        status == EscrowStatus.RESOLVING

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
    escrowDao: EscrowDao,
    private val identityManager: com.neop2p.data.p2p.IdentityManager
) : ViewModel() {
    val escrows: StateFlow<List<HistoryRowData>> = escrowDao.getAllEscrowsWithFiat()
        .map { list ->
            val myPeerId = runCatching { identityManager.getOrCreateIdentity().peerId }
                .getOrDefault("")
            list.map { row ->
                val escrow = row.escrow.toDomain()
                HistoryRowData(
                    escrow = escrow,
                    fiatAmount = row.offerFiatAmount,
                    // Role-aware "needs my action": the seller acts on
                    // funding/confirm/release; the buyer acts on pay/receipt.
                    needsMyAction = needsMyAction(escrow, myPeerId)
                )
            }.sortedByDescending { it.escrow.createdAt }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun needsMyAction(escrow: com.neop2p.domain.model.Escrow, myPeerId: String): Boolean {
        if (myPeerId.isBlank()) return false
        val isSeller = escrow.sellerPeerId == myPeerId
        val isBuyer = escrow.buyerPeerId == myPeerId
        return when (escrow.status) {
            com.neop2p.domain.model.EscrowStatus.FUNDING -> isSeller
            com.neop2p.domain.model.EscrowStatus.FUNDED -> isBuyer
            com.neop2p.domain.model.EscrowStatus.PAYMENT_PENDING -> isBuyer
            com.neop2p.domain.model.EscrowStatus.RECEIPT_SENT -> isSeller
            com.neop2p.domain.model.EscrowStatus.CONFIRMING -> isSeller
            com.neop2p.domain.model.EscrowStatus.SIGNED -> isSeller
            else -> false
        }
    }
}

/** Escrow plus the fiat amount of its originating offer (may be null if the offer row is gone). */
data class HistoryRowData(
    val escrow: Escrow,
    val fiatAmount: Long?,
    val needsMyAction: Boolean = false
)
