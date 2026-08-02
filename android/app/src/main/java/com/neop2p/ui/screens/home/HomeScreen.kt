package com.neop2p.ui.screens.home

import androidx.activity.compose.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.neop2p.NeoP2PConfig
import com.neop2p.R
import com.neop2p.data.local.*
import com.neop2p.data.local.dao.*
import com.neop2p.data.p2p.*
import com.neop2p.data.reputation.ReputationSystem
import com.neop2p.domain.model.OfferStatus
import com.neop2p.domain.model.OfferType
import com.neop2p.domain.model.Peer
import com.neop2p.domain.model.TradeOffer
import com.neop2p.ui.theme.NeoP2PTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import javax.inject.Inject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onCreateOffer: () -> Unit,
    onOfferClick: (String) -> Unit,
    onProfileClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val viewModel: HomeViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    NeoP2PTheme {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("NEO-P2P") },
                    actions = {
                        IconButton(onClick = onProfileClick) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_person),
                                contentDescription = "Profile"
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(onClick = onSettingsClick) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_settings),
                                contentDescription = "Settings"
                            )
                        }
                    }
                )
            },
            content = { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    when (val s = state) {
                        is HomeViewModel.UiState.Loading -> LoadingScreen()
                        is HomeViewModel.UiState.Error -> ErrorScreen(
                            message = s.message,
                            onRetry = { viewModel.refresh() }
                        )
                        is HomeViewModel.UiState.Success -> {
                            val data = s.data
                            HomeContent(
                                offers = data.offers,
                                peers = data.peers,
                                onCreateOffer = onCreateOffer,
                                onOfferClick = onOfferClick,
                                onRefresh = { viewModel.refresh() }
                            )
                        }
                    }
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoadingScreen(
    modifier: Modifier = Modifier
) = Box(
    modifier = modifier
        .fillMaxSize()
        .background(
            color = if (isSystemInDarkTheme()) Color(0xFF0D1117) else Color.White
        ),
    contentAlignment = Alignment.Center
) {
    CircularProgressIndicator(
        modifier = Modifier.size(48.dp),
        color = MaterialTheme.colorScheme.primary
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ErrorScreen(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) = Box(
    modifier = modifier
        .fillMaxSize()
        .padding(24.dp),
    contentAlignment = Alignment.Center
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_warning),
            contentDescription = "Error",
            modifier = Modifier.size(64.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = message,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onRetry,
            modifier = Modifier
                .width(120.dp)
                .height(40.dp)
        ) {
            Text("Retry")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeContent(
    offers: List<TradeOffer>,
    peers: List<Peer>,
    onCreateOffer: () -> Unit,
    onOfferClick: (String) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Pull-to-refresh indicator
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = { /* Handle pull down */ },
                            onDoubleTap = { /* Refresh on double tap */ },
                            onLongPress = { /* Refresh on long press */ }
                        )
                    }
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_refresh),
                    contentDescription = "Pull to refresh",
                    modifier = Modifier
                        .align(Alignment.Center)
                        .graphicsLayer(alpha = 0.6f)
                )
            }

            // Offer feed
            if (offers.isEmpty()) {
                EmptyState()
            } else {
                TradeOfferList(
                    offers = offers,
                    peers = peers,
                    onOfferClick = onOfferClick
                )
            }
        } // Column

        // Create Offer FAB (in BoxScope — aligned to bottom-right)
        ExtendedFloatingActionButton(
            text = { Text("Create Offer") },
            icon = { Icon(painterResource(id = R.drawable.ic_add), contentDescription = "Add") },
            onClick = onCreateOffer,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .width(200.dp)
                .height(56.dp)
        )
    } // Box
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmptyState(
    modifier: Modifier = Modifier
) = Box(
    modifier = modifier
        .fillMaxSize()
        .padding(24.dp),
    contentAlignment = Alignment.Center
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Image(
            painter = painterResource(id = R.drawable.ic_trending_up),
            contentDescription = "No offers yet",
            modifier = Modifier.size(80.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "No active offers yet",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Create the first offer to start trading!",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TradeOfferList(
    offers: List<TradeOffer>,
    peers: List<Peer>,
    onOfferClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val peerMap = peers.associateBy { it.peerId }

    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        itemsIndexed(items = offers) { index, offer ->
            TradeOfferCard(
                offer = offer,
                peer = peerMap[offer.creatorPeerId],
                onClick = { onOfferClick(offer.offerId) }
            )

            if (index < offers.size - 1) {
                Divider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    thickness = 1.dp
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TradeOfferCard(
    offer: TradeOffer,
    peer: Peer?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isBuy = offer.type == OfferType.BUY
    val accentColor = if (isBuy) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
        ) {
            // Peer avatar/nickname
            Column(
                verticalArrangement = Arrangement.Center
            ) {
                peer?.let { p ->
                    Text(
                        text = if (p.nickname.isNotBlank()) p.nickname else "Anonymous",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "(${p.totalTrades} trades)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                } ?: run {
                    Text(
                        text = "Anonymous",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "(New)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Offer details
            Column(
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.Start
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(
                            if (isBuy) R.drawable.ic_trending_down else R.drawable.ic_trending_up
                        ),
                        contentDescription = if (isBuy) "Buy" else "Sell",
                        modifier = Modifier.size(20.dp),
                        tint = accentColor
                    )
                    Text(
                        text = "${offer.cryptoAmountSats / 100_000_000.00000000} BTC",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = "Rp ${offer.fiatAmount / 1000},00",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "@ Rp ",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${offer.pricePerUnit / 1000},00 / BTC",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        painter = painterResource(id = R.drawable.ic_info_outline),
                        contentDescription = "Price info",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    SuggestionChip(
                        onClick = {},
                        label = { Text(offer.fiatMethods.firstOrNull() ?: "Bank") },
                        icon = {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_account_balance),
                                contentDescription = "Fiat method",
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "• ${offer.fiatMethods.size} methods",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Action button
            Column(
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.End
            ) {
                Text(
                    text = "View Details",
                    style = MaterialTheme.typography.labelMedium,
                    color = accentColor
                )
            }
        }
    }
}

// ─── ViewModel ───────────────────────────────────────────────
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val identityManager: IdentityManager,
    private val p2pTransport: HybridP2PTransport,
    private val nostrClient: NostrClient,
    private val reputationSystem: ReputationSystem,
    private val offerDao: OfferDao,
    private val peerDao: PeerDao
) : androidx.lifecycle.ViewModel() {

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    sealed class UiState {
        object Loading : UiState()
        data class Error(val message: String) : UiState()
        data class Success(val data: HomeData) : UiState()
    }

    data class HomeData(
        val offers: List<TradeOffer>,
        val peers: List<Peer>
    )

    init {
        observeDbOffers()
        persistNostrOffers()
        startBackgroundSync()
    }

    private fun observeDbOffers() {
        viewModelScope.launch(Dispatchers.Main) {
            combine(
                offerDao.getAllOffers()
                    .map { entities -> entities.map { it.toDomain() } },
                peerDao.getAllPeers()
                    .map { entities -> entities.map { it.toDomain() } }
            ) { offers, peers ->
                HomeData(offers, peers)
            }.catch { e ->
                emit(HomeData(emptyList(), emptyList()))
                _uiState.value = UiState.Error("DB error: ${e.message}")
            }.collect { data ->
                _uiState.value = UiState.Success(data)
            }
        }
    }

    private fun persistNostrOffers() {
        viewModelScope.launch(Dispatchers.IO) {
            nostrClient.offers.collect { eventJson ->
                try {
                    val content = eventJson["content"]?.jsonPrimitive?.content ?: return@collect
                    val offerJson = Json.parseToJsonElement(content).jsonObject

                    val offer = TradeOffer(
                        offerId = offerJson["offer_id"]?.jsonPrimitive?.content
                            ?: eventJson["id"]?.jsonPrimitive?.content ?: return@collect,
                        creatorPeerId = offerJson["creator_peer_id"]?.jsonPrimitive?.content ?: "",
                        type = OfferType.valueOf(
                            offerJson["type"]?.jsonPrimitive?.content ?: "SELL"
                        ),
                        fiatAmount = offerJson["fiat_amount"]?.jsonPrimitive?.long ?: 0L,
                        cryptoAmountSats = offerJson["crypto_amount_sats"]?.jsonPrimitive?.long ?: 0L,
                        pricePerUnit = offerJson["price_per_unit"]?.jsonPrimitive?.double ?: 0.0,
                        feePercent = offerJson["fee_percent"]?.jsonPrimitive?.double ?: NeoP2PConfig.FEE_PERCENT,
                        fiatMethods = if (offerJson["fiat_methods"] != null) {
                            Json.decodeFromJsonElement<List<String>>(offerJson["fiat_methods"]!!)
                        } else emptyList(),
                        status = try {
                            OfferStatus.valueOf(
                                offerJson["status"]?.jsonPrimitive?.content ?: "OPEN"
                            )
                        } catch (_: Exception) { OfferStatus.OPEN },
                        createdAt = offerJson["created_at"]?.jsonPrimitive?.long
                            ?: System.currentTimeMillis(),
                        nostrEventId = eventJson["id"]?.jsonPrimitive?.content
                    )

                    offerDao.upsert(offer.toEntity())

                } catch (e: Exception) {
                    Log.w("HomeViewModel", "Failed to persist Nostr offer: ${e.message}")
                }
            }
        }
    }

    private fun startBackgroundSync() {
        val myPubkey = identityManager.getOrCreateIdentity().nostrPubkeyHex
        viewModelScope.launch {
            nostrClient.connect(myPubkey)
        }
        viewModelScope.launch {
            p2pTransport.start()
        }
    }

    fun refresh() {
        Log.d("HomeViewModel", "Refresh triggered (DB Flow is reactive)")
    }
}
