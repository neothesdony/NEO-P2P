package com.neop2p.ui.screens.offerdetail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neop2p.R
import com.neop2p.data.p2p.IdentityManager
import com.neop2p.data.reputation.ReputationSystem
import com.neop2p.domain.model.*
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

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Offer Details") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
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
                            Text("Retry")
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
                            if (isBuy) "BUYING" else "SELLING",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (isBuy) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary
                        )
                        Spacer(Modifier.width(8.dp))
                        SuggestionChip(
                            onClick = {},
                            label = { Text(offer.asset.ticker) }
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    DetailRow("Amount", "${offer.cryptoAmountSats / 100_000_000.0} BTC")
                    DetailRow("Price", "Rp ${String.format("%,.0f", offer.pricePerUnit)}/BTC")
                    DetailRow("Total Fiat", "Rp ${String.format("%,.0f", offer.fiatAmount.toDouble())}")
                    DetailRow("Fee (1%)", "${offer.feeSats} sats")
                    DetailRow("Total Deposit", "${offer.totalDepositSats} sats")
                }
            }
        }

        item {
            Spacer(Modifier.height(16.dp))
            Text("Payment Methods", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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
            Text("Trader", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))

            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Row(Modifier.padding(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = peer?.nickname?.ifBlank { "Anonymous" } ?: "Anonymous",
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
                                    " (${rep.totalTrades} trades)",
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
                Text("Start Trade")
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
