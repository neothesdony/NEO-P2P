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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neop2p.NeoP2PConfig
import com.neop2p.R
import com.neop2p.data.p2p.*
import com.neop2p.data.reputation.ReputationSystem
import com.neop2p.domain.model.Offer
import com.neop2p.domain.model.OfferStatus
import com.neop2p.domain.model.OfferType
import com.neop2p.navigation.Routes
import com.neop2p.ui.theme.NeoP2PTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
                    when (state) {
                        is HomeViewModel.Loading -> LoadingScreen()
                        is HomeViewModel.Error -> ErrorScreen(
                            message = (it as HomeViewModel.Error).message,
                            onRetry = { viewModel.refresh() }
                        )
                        is HomeViewModel.Success -> {
                            val data = (it as HomeViewModel.Success).data
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
        )
        .align(Alignment.Center)
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
) = Column(
    modifier = modifier
        .fillMaxSize()
        .padding(24.dp)
        .align(Alignment.Center)
) {
    Icon(
        painter = painterResource(id = R.drawable.ic_warning),
        contentDescription = "Error",
        modifier = Modifier
            .size(64.dp)
            .wrapContentSize(align = Alignment.Center)
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
    Column {
        // Pull-to-refresh
        androidx.compose.foundation.layout.Box(
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
                    .alpha(0.6f)
            )
        }

        // Create Offer FAB
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            ExtendedFloatingActionButton(
                text = { Text("Create Offer") },
                icon = { Icon(painterResource(id = R.drawable.ic_add), contentDescription = "Add") },
                onClick = onCreateOffer,
                modifier = Modifier
                    .width(200.dp)
                    .height(56.dp)
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
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmptyState(
    modifier: Modifier = Modifier
) = Column(
    modifier = modifier
        .fillMaxSize()
        .padding(24.dp)
        .align(Alignment.Center)
) {
    Image(
        painter = painterResource(id = R.drawable.ic_trending_up),
        contentDescription = "No offers yet",
        modifier = Modifier
            .size(80.dp)
            .wrapContentSize(align = Alignment.Center)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TradeOfferList(
    offers: List<TradeOffer>,
    peers: List<Peer>,
    onOfferClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // Create a map for quick peer lookup
    val peerMap = peers.associateBy { it.peerId } to Map

    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        itemsIndexed(items = offers) { index, offer ->
            TradeOfferCard(
                offer = offer,
                peer = peerMap[offer.creatorPeerId],
                onClick = { onOfferClick(offer.offerId) },
                key = offer.offerId
            )

            // Divider between items
            if (index < offers.size - 1) {
                Divider(
                    color = MaterialTheme.colorScheme.divider,
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
    val isBuy = offer.type == TradeOffer.OfferType.BUY
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
                Row(
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        painter = painterResource(
                            if (isBuy) R.drawable.ic_trending_down else R.drawable.ic_trending_up
                        ),
                        contentDescription = if (isBuy) "Buy" else "Sell",
                        modifier = Modifier
                            .size(20.dp)
                            .colorFilter(accentColor)
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

                Row(
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.Start
                ) {
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
                        modifier = Modifier
                            .size(16.dp)
                            .colorFilter(MaterialTheme.colorScheme.onSurfaceVariant)
                    )
                }

                Row(
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.Start
                ) {
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
class HomeViewModel @Inject constructor(
    private val identityManager: IdentityManager,
    private val libP2PManager: LibP2PManager,
    private val nostrClient: NostrClient,
    private val reputationSystem: ReputationSystem
) : HiltViewModel() {

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

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private lateinit var nostrJob: Job
    private lateinit var libp2pJob: Job
    private val refreshHandler = Handler(Looper.getMainLooper())

    init {
        loadInitialData()
        startBackgroundSync()
    }

    private fun loadInitialData() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Load from local database first (offline-first)
                // For v1: simulate with mock data
                val mockOffers = listOf(
                    TradeOffer(
                        offerId = "offer_1",
                        creatorPeerId = "peer_1",
                        type = TradeOffer.OfferType.SELL,
                        cryptoAmountSats = 500_000, // 0.005 BTC
                        fiatAmount = 7_500_000, // Rp 7.500.000
                        pricePerUnit = 1_500_000_000.0, // Rp 1.500.000 per BTC
                        feeSats = 5_000, // 1%
                        fiatMethods = listOf("bca", "gopay", "dana"),
                        status = TradeOffer.OfferStatus.OPEN
                    ),
                    TradeOffer(
                        offerId = "offer_2",
                        creatorPeerId = "peer_2",
                        type = TradeOffer.OfferType.BUY,
                        cryptoAmountSats = 1_000_000, // 0.01 BTC
                        fiatAmount = 15_000_000, // Rp 15.000.000
                        pricePerUnit = 1_500_000_000.0, // Rp 1.500.000 per BTC
                        feeSats = 10_000, // 1%
                        fiatMethods = listOf("mandiri", "ovo", "linkaja"),
                        status = TradeOffer.OfferStatus.OPEN
                    )
                )

                val mockPeers = listOf(
                    Peer(
                        peerId = "peer_1",
                        nickname = "Trader_Budi",
                        nostrPubkey = "npub1...peer1",
                        lnNodeId = "02abc123...peer1",
                        reputationScore = 0.85f,
                        totalTrades = 42
                    ),
                    Peer(
                        peerId = "peer_2",
                        nickname = "",
                        nostrPubkey = "npub1...peer2",
                        lnNodeId = "03def456...peer2",
                        reputationScore = 0.92f,
                        totalTrades = 127
                    )
                )

                _uiState.value = UiState.Success(HomeData(mockOffers, mockPeers))
            } catch (e: Exception) {
                _uiState.value = UiState.Error("Failed to load data: ${e.message}")
            }
        }
    }

    private fun startBackgroundSync() {
        // Start listening for Nostr events
        val myPubkey = identityManager.getOrCreateIdentity().nostrPubkeyHex
        nostrJob = scope.launch {
            nostrClient.connect(myPubkey)
        }

        // Start libp2p background maintenance
        libp2pJob = scope.launch {
            libP2PManager.start()
        }

        // Periodic refresh from local DB
        scope.launch {
            while (isActive) {
                delay(30_000) // 30 seconds
                refresh()
            }
        }
    }

    fun refresh() {
        _uiState.value = UiState.Loading
        loadInitialData()
    }

    override fun onCleared() {
        super.onCleared()
        nostrJob.cancel()
        libp2pJob.cancel()
        scope.cancel()
    }
}
