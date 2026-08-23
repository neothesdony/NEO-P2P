package com.neop2p.ui.screens.home

import androidx.activity.compose.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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
import com.neop2p.ui.theme.buyColor
import com.neop2p.ui.theme.sellColor
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
                    title = { Text(stringResource(R.string.app_name)) },
                    actions = {
                        IconButton(onClick = onProfileClick) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_person),
                                contentDescription = stringResource(R.string.home_cd_profile)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(onClick = onSettingsClick) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_settings),
                                contentDescription = stringResource(R.string.home_cd_settings)
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
                                isRefreshing = viewModel.isRefreshing.collectAsStateWithLifecycle().value,
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
        .background(color = MaterialTheme.colorScheme.background),
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
            contentDescription = stringResource(R.string.general_error),
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
            Text(stringResource(R.string.general_retry))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeContent(
    offers: List<TradeOffer>,
    peers: List<Peer>,
    isRefreshing: Boolean,
    onCreateOffer: () -> Unit,
    onOfferClick: (String) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = Modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize()
        ) {
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

        // Create Offer FAB (in BoxScope — aligned to bottom-right)
        ExtendedFloatingActionButton(
            text = { Text(stringResource(R.string.home_create_offer)) },
            icon = { Icon(painterResource(id = R.drawable.ic_add), contentDescription = stringResource(R.string.general_add)) },
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
            contentDescription = stringResource(R.string.home_no_offers),
            modifier = Modifier.size(80.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.home_no_offers),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.home_no_offers_hint),
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
                HorizontalDivider(
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
    val accentColor = if (isBuy) MaterialTheme.colorScheme.buyColor else MaterialTheme.colorScheme.sellColor

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
                        text = if (p.nickname.isNotBlank()) p.nickname else stringResource(R.string.general_anonymous),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.trades_suffix_format, p.totalTrades),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                } ?: run {
                    Text(
                        text = stringResource(R.string.general_anonymous),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.home_new),
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
                        contentDescription = if (isBuy) stringResource(R.string.trade_buy) else stringResource(R.string.trade_sell),
                        modifier = Modifier.size(20.dp),
                        tint = accentColor
                    )
                    Text(
                        text = stringResource(R.string.common_btc_amount, (offer.cryptoAmountSats / 100_000_000.0).toString()),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = stringResource(R.string.home_fiat_amount, offer.fiatAmount / 1000),
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.home_at_price),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.home_price_per_btc, String.format("%,.0f", offer.pricePerUnit / 1000)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        painter = painterResource(id = R.drawable.ic_info_outline),
                        contentDescription = stringResource(R.string.home_cd_price_info),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_account_balance),
                                contentDescription = stringResource(R.string.home_cd_fiat_method),
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = offer.fiatMethods.firstOrNull() ?: stringResource(R.string.home_bank),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.home_methods, offer.fiatMethods.size),
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
                    text = stringResource(R.string.home_view_details),
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

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

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
        listenForOfferDeletions()
        listenForOfferStatusUpdates()
        startBackgroundSync()
    }

    /** Remove offers locally when another NEO-P2P peer deletes them (NIP-09). */
    private fun listenForOfferDeletions() {
        viewModelScope.launch(Dispatchers.IO) {
            nostrClient.deletions.collect { deletedEventId ->
                try {
                    val offer = offerDao.getOfferByEventId(deletedEventId)
                    if (offer != null) {
                        offerDao.delete(offer)
                        Log.d("HomeViewModel", "Removed locally-deleted offer event=$deletedEventId")
                    }
                } catch (e: Exception) {
                    Log.w("HomeViewModel", "Failed to apply offer deletion: ${e.message}")
                }
            }
        }
    }

    /** Apply status updates (e.g. accept → MATCHED) from other peers so offers lock. */
    private fun listenForOfferStatusUpdates() {
        viewModelScope.launch(Dispatchers.IO) {
            nostrClient.offerStatusUpdates.collect { (offerId, status) ->
                try {
                    offerDao.updateStatus(offerId, status)
                    Log.d("HomeViewModel", "Applied status update offer=$offerId status=$status")
                } catch (e: Exception) {
                    Log.w("HomeViewModel", "Failed to apply offer status: ${e.message}")
                }
            }
        }
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
        val myPubkey = try {
            identityManager.getOrCreateIdentity().nostrPubkeyHex
        } catch (e: Exception) {
            // P0-4: identity may be locked behind device auth (no recent unlock).
            // Do NOT crash startup — just skip connecting until the user unlocks.
            Log.w("HomeViewModel", "Identity not available for sync (locked?): ${e.message}")
            return
        }
        viewModelScope.launch {
            nostrClient.connect(myPubkey)
        }
        viewModelScope.launch {
            p2pTransport.start()
        }
    }

    fun refresh() {
        if (_isRefreshing.value) return
        _isRefreshing.value = true
        viewModelScope.launch {
            try {
                // Force a reconnect cycle so offers stream in fresh from relays.
                val myPubkey = identityManager.getOrCreateIdentity().nostrPubkeyHex
                nostrClient.connect(myPubkey)
                p2pTransport.start()
            } catch (e: Exception) {
                Log.w("HomeViewModel", "Refresh failed: ${e.message}")
            } finally {
                _isRefreshing.value = false
            }
        }
    }
}
