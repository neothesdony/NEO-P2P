package com.neop2p.ui.screens.offerdetail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neop2p.R
import com.neop2p.data.p2p.IdentityManager
import com.neop2p.data.reputation.ReputationSystem
import com.neop2p.domain.model.*
import com.neop2p.ui.theme.NeoP2PTheme
import com.neop2p.ui.theme.buyColor
import com.neop2p.ui.theme.sellColor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfferDetailScreen(
    offerId: String,
    onBack: () -> Unit,
    onChatClick: (String, String) -> Unit
) {
    val viewModel: OfferDetailViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Load the offer once on first composition (prevents infinite loading spinner).
    LaunchedEffect(offerId) {
        viewModel.loadOffer(offerId)
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.offer_details_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.general_back))
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            when (val s = state) {
                is OfferDetailViewModel.UiState.Loading -> {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
                is OfferDetailViewModel.UiState.Error -> {
                    Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(s.message, style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { viewModel.loadOffer(offerId) }) {
                            Text(stringResource(R.string.general_retry))
                        }
                    }
                }
                is OfferDetailViewModel.UiState.Success -> OfferDetailContent(
                    offer = s.data.offer,
                    peer = s.data.peer,
                    reputation = s.data.reputation,
                    onChatClick = { onChatClick(offerId, s.data.offer.creatorPeerId) }
                )
            }
        }
    }
}

@Composable
private fun OfferDetailContent(
    offer: TradeOffer,
    peer: Peer?,
    reputation: ReputationScore?,
    onChatClick: () -> Unit
) {
    val isBuy = offer.type == OfferType.BUY

    LazyColumn(Modifier.padding(16.dp)) {
        item {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (isBuy) stringResource(R.string.offer_buying) else stringResource(R.string.offer_selling),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (isBuy) MaterialTheme.colorScheme.buyColor else MaterialTheme.colorScheme.sellColor
                        )
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            Text(
                                text = offer.asset.ticker,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    DetailRow(stringResource(R.string.offer_amount), "${offer.cryptoAmountSats / 100_000_000.0} BTC")
                    DetailRow(stringResource(R.string.offer_price), stringResource(R.string.offer_fiat_format, String.format("%,.0f", offer.pricePerUnit)) + "/BTC")
                    DetailRow(stringResource(R.string.offer_total_fiat), stringResource(R.string.offer_fiat_format, String.format("%,.0f", offer.fiatAmount.toDouble())))
                    DetailRow(stringResource(R.string.offer_fee_1), "${offer.feeSats} sats")
                    DetailRow(stringResource(R.string.offer_total_deposit_label), "${offer.totalDepositSats} sats")
                }
            }
        }

        item {
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.offer_payment_methods), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            offer.fiatMethods.forEach { method ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.AccountBalance, contentDescription = null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(method.uppercase(), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        item {
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.offer_trader), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))

            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Row(Modifier.padding(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = peer?.nickname?.ifBlank { stringResource(R.string.general_anonymous) } ?: stringResource(R.string.general_anonymous),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium
                        )
                        reputation?.let { rep ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${(rep.score * 100).toInt()}%",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = when {
                                        rep.score >= 0.9f -> MaterialTheme.colorScheme.primary
                                        rep.score >= 0.7f -> MaterialTheme.colorScheme.secondary
                                        else -> MaterialTheme.colorScheme.error
                                    }
                                )
                                Text(
                                    stringResource(R.string.trades_suffix_format, rep.totalTrades),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onChatClick,
                Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text(stringResource(R.string.offer_start_trade))
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

data class ReputationScore(
    val score: Float,
    val totalTrades: Int
)

data class DetailData(
    val offer: TradeOffer,
    val peer: Peer?,
    val reputation: ReputationScore?
)

@HiltViewModel
class OfferDetailViewModel @Inject constructor(
    private val identityManager: IdentityManager,
    private val reputationSystem: ReputationSystem
) : androidx.lifecycle.ViewModel() {

    sealed class UiState {
        object Loading : UiState()
        data class Error(val message: String) : UiState()
        data class Success(val data: DetailData) : UiState()
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun loadOffer(offerId: String) {
        _uiState.value = UiState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val mockOffer = TradeOffer(
                    offerId = offerId.ifBlank { "offer_1" },
                    creatorPeerId = "peer_1",
                    type = OfferType.SELL,
                    cryptoAmountSats = 500_000,
                    fiatAmount = 7_500_000,
                    pricePerUnit = 1_500_000_000.0,
                    fiatMethods = listOf("bca", "gopay", "dana"),
                    status = OfferStatus.OPEN
                )
                val mockPeer = Peer(
                    peerId = "peer_1", nickname = "Trader_Budi",
                    nostrPubkey = "npub1...", lnNodeId = "02abc...",
                    reputationScore = 0.85f, totalTrades = 42
                )
                _uiState.value = UiState.Success(
                    DetailData(mockOffer, mockPeer, ReputationScore(0.85f, 42))
                )
            } catch (e: Exception) {
                _uiState.value = UiState.Error("Failed to load: ${e.message}")
            }
        }
    }
}
