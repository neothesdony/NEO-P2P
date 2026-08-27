package com.neop2p.ui.screens.home

import androidx.activity.compose.*
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import com.neop2p.ui.util.formatBtc
import com.neop2p.ui.theme.buyColor
import com.neop2p.ui.theme.sellColor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    onChatClick: (String, String) -> Unit,
    onEscrowClick: (String) -> Unit,
    onWalletClick: () -> Unit,
    onHistoryClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val viewModel: HomeViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val activeChat by viewModel.activeChat.collectAsStateWithLifecycle()
    val activeEscrow by viewModel.activeEscrow.collectAsStateWithLifecycle()

    // P0-4: when the identity seed is locked behind device auth (unlock window
    // expired), surface a BiometricPrompt so the user can re-authorize the
    // Keystore key (biometric or PIN). After success we retry the pending op.
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val identityLocked = viewModel.identityLocked.collectAsStateWithLifecycle().value
    LaunchedEffect(identityLocked) {
        if (identityLocked && activity is FragmentActivity) {
            viewModel.consumeIdentityLocked()
            val executor = ContextCompat.getMainExecutor(activity)
            val prompt = BiometricPrompt(
                activity, executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        super.onAuthenticationSucceeded(result)
                        // Re-arm the auth-gated key; retry the pending sync.
                        viewModel.startBackgroundSync()
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        super.onAuthenticationError(errorCode, errString)
                        if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                            errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                        ) {
                            Log.w("HomeScreen", "Unlock prompt failed: $errString")
                        }
                    }
                }
            )
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle(activity.getString(R.string.home_unlock_title))
                .setSubtitle(activity.getString(R.string.home_unlock_subtitle))
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
                .build()
            prompt.authenticate(promptInfo)
        }
    }

    // P0-4-2: if the app started while the phone was locked, the P0-4 guard
    // skipped startBackgroundSync() and the transports never came up (relay
    // never learned our peerId → peers get "delivery failed: peer not found").
    // On every resume, retry once if the transport is still inactive.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (!viewModel.isTransportActive()) {
                    Log.w("HomeScreen", "Transport inactive on resume — restarting background sync")
                    viewModel.startBackgroundSync()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Request POST_NOTIFICATIONS on Android 13+ so the P2P foreground service
    // ("Connected to network") can show a notification. Then start that service
    // (declared in the manifest but never started — it was dead code).
    val notifPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { /* granted or denied — service still runs; user can enable in settings */ }
    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val hasPermission = context.checkSelfPermission(
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!hasPermission) {
                notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val svcIntent = android.content.Intent(context, com.neop2p.service.P2PBackgroundService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(svcIntent)
        } else {
            context.startService(svcIntent)
        }
    }

    NeoP2PTheme {
        val snackbarHostState = remember { SnackbarHostState() }
        LaunchedEffect(Unit) {
            viewModel.escrowEvents.collect { t ->
                val text = when (t.status) {
                    "funded" -> context.getString(R.string.home_escrow_funded)
                    "released" -> context.getString(R.string.home_escrow_released)
                    "disputed" -> context.getString(R.string.home_escrow_disputed)
                    "refunded" -> context.getString(R.string.home_escrow_refunded)
                    "cancelled" -> context.getString(R.string.home_escrow_cancelled)
                    else -> return@collect
                }
                val result = snackbarHostState.showSnackbar(
                    message = text,
                    actionLabel = context.getString(R.string.general_view),
                    duration = SnackbarDuration.Short
                )
                if (result == SnackbarResult.ActionPerformed) {
                    onEscrowClick(t.escrowId)
                }
            }
        }
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(stringResource(R.string.app_name)) },
                    navigationIcon = {
                        // Quick access to the active trade: chat with the matched
                        // peer, or the most recent escrow. Disabled when none.
                        Row {
                            IconButton(
                                onClick = {
                                    activeChat?.let { (oid, pid) -> onChatClick(oid, pid) }
                                },
                                enabled = activeChat != null
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_send),
                                    contentDescription = stringResource(R.string.home_cd_chat)
                                )
                            }
                            IconButton(
                                onClick = { activeEscrow?.let(onEscrowClick) },
                                enabled = activeEscrow != null
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_crypto_lock),
                                    contentDescription = stringResource(R.string.home_cd_escrow)
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = onWalletClick) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_account_balance),
                                contentDescription = stringResource(R.string.home_cd_wallet)
                            )
                        }
                        IconButton(onClick = onHistoryClick) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_history),
                                contentDescription = stringResource(R.string.home_cd_history)
                            )
                        }
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
                                myPeerId = data.myPeerId,
                                isArbitrator = data.isArbitrator,
                                isRefreshing = viewModel.isRefreshing.collectAsStateWithLifecycle().value,
                                onCreateOffer = onCreateOffer,
                                onOfferClick = onOfferClick,
                                onRefresh = { viewModel.refresh() },
                                onWalletClick = onWalletClick
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
) {
    // Skeleton feed — shimmer placeholder instead of a lone spinner.
    val transition = rememberInfiniteTransition(label = "skeleton")
    val shimmer by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shimmerAlpha"
    )

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(color = MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        items(5) {
            SkeletonCard(shimmer = shimmer)
        }
    }
}

@Composable
private fun SkeletonCard(
    shimmer: Float,
    modifier: Modifier = Modifier
) {
    val skeletonColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = shimmer)
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = shimmer * 0.6f)
        ),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(skeletonColor)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Box(
                    modifier = Modifier
                        .width(110.dp)
                        .height(14.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(skeletonColor)
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            Box(
                modifier = Modifier
                    .width(160.dp)
                    .height(30.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(skeletonColor)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .width(220.dp)
                    .height(12.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(skeletonColor)
            )
        }
    }
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
    myPeerId: String,
    isArbitrator: Boolean,
    isRefreshing: Boolean,
    onCreateOffer: () -> Unit,
    onOfferClick: (String) -> Unit,
    onRefresh: () -> Unit,
    onWalletClick: () -> Unit,
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
                    myPeerId = myPeerId,
                    isArbitrator = isArbitrator,
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
    myPeerId: String,
    isArbitrator: Boolean,
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
                // Locked offers are private to the trade: only the creator
                // (seller), the matched peer (buyer), or the arbitrator
                // (admin) may open the details. Everyone else sees the card
                // but tapping does nothing.
                canOpen = !isLocked(offer) ||
                    offer.creatorPeerId == myPeerId ||
                    offer.matchedPeerId == myPeerId ||
                    isArbitrator,
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

private fun isLocked(offer: TradeOffer): Boolean = offer.status != OfferStatus.OPEN

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TradeOfferCard(
    offer: TradeOffer,
    peer: Peer?,
    canOpen: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isBuy = offer.type == OfferType.BUY
    val isLocked = offer.status != OfferStatus.OPEN
    val accentColor = if (isBuy) MaterialTheme.colorScheme.buyColor else MaterialTheme.colorScheme.sellColor

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .then(if (canOpen) Modifier.clickable { onClick() } else Modifier),
        colors = CardDefaults.cardColors(
            containerColor = if (isLocked) MaterialTheme.colorScheme.surface
            else MaterialTheme.colorScheme.surfaceVariant
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

            // Offer details — weight(1f): the details must wrap inside the
            // REMAINING row width, never push the action column (Locked badge)
            // to zero width (the badge used to collapse into vertical text).
            Column(
                modifier = Modifier.weight(1f),
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
                        text = stringResource(R.string.common_btc_amount, formatBtc(offer.cryptoAmountSats)),
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Money hero — tabular figures + display scale + animated color.
                val fiatColor by animateColorAsState(
                    targetValue = if (isBuy) MaterialTheme.colorScheme.buyColor else MaterialTheme.colorScheme.sellColor,
                    label = "fiatAmountColor"
                )
                Text(
                    text = stringResource(R.string.home_fiat_amount, offer.fiatAmount),
                    style = MaterialTheme.typography.titleLarge
                        .copy(fontFeatureSettings = "tnum"),
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = fiatColor
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.home_at_price),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.home_price_per_btc, String.format("%,.0f", offer.pricePerUnit)),
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
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
                if (isLocked) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.tertiaryContainer
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_lock),
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.home_offer_locked),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                maxLines = 1
                            )
                        }
                    }
                    // Authorized parties (seller / buyer / admin) can still open
                    // a locked offer — show the affordance under the badge.
                    if (canOpen) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.home_view_details),
                            style = MaterialTheme.typography.labelSmall,
                            color = accentColor
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.home_view_details),
                        style = MaterialTheme.typography.labelMedium,
                        color = accentColor
                    )
                }
            }
        }
    }
}

// ─── ViewModel ───────────────────────────────────────────────
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val identityManager: IdentityManager,
    private val p2pTransport: HybridP2PTransport,
    private val orchestrator: P2POrchestrator,
    private val nostrClient: NostrClient,
    private val reputationSystem: ReputationSystem,
    private val offerDao: OfferDao,
    private val peerDao: PeerDao,
    private val escrowDao: EscrowDao,
    private val escrowService: com.neop2p.data.escrow.EscrowService
) : androidx.lifecycle.ViewModel() {

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    // Foreground escrow transitions (funded / released / disputed / refunded /
    // cancelled) surfaced as in-app snackbars — the notification dispatcher
    // suppresses these while the app is foregrounded, so the home screen is the
    // user's live view of trade-critical changes.
    private val _escrowEvents = MutableSharedFlow<com.neop2p.data.escrow.EscrowService.EscrowTransition>(replay = 0)
    val escrowEvents: SharedFlow<com.neop2p.data.escrow.EscrowService.EscrowTransition> = _escrowEvents.asSharedFlow()

    init {
        viewModelScope.launch {
            escrowService.transitions.collect { t ->
                if (t.status in setOf("funded", "released", "disputed", "refunded", "cancelled")) {
                    _escrowEvents.emit(t)
                }
            }
        }
    }

    // Quick-access targets for the top bar: the most recent active trade
    // (chat with the matched peer) and the most recent escrow.
    private val _activeChat = MutableStateFlow<Pair<String, String>?>(null)
    val activeChat: StateFlow<Pair<String, String>?> = _activeChat.asStateFlow()

    private val _activeEscrow = MutableStateFlow<String?>(null)
    val activeEscrow: StateFlow<String?> = _activeEscrow.asStateFlow()

    // P0-4: set when the identity seed is gated behind device auth (unlock window
    // expired) and a BiometricPrompt is needed to re-arm the Keystore key.
    private val _identityLocked = MutableStateFlow(false)
    val identityLocked: StateFlow<Boolean> = _identityLocked.asStateFlow()

    fun consumeIdentityLocked() {
        _identityLocked.value = false
    }

    /** True if any transport (libp2p or relay) is currently running. */
    fun isTransportActive(): Boolean = p2pTransport.isActive()

    sealed class UiState {
        object Loading : UiState()
        data class Error(val message: String) : UiState()
        data class Success(val data: HomeData) : UiState()
    }

    data class HomeData(
        val offers: List<TradeOffer>,
        val peers: List<Peer>,
        // Identity context for the locked-offer gate: only the offer creator
        // (seller), the matched peer (buyer), or the arbitrator (admin) may
        // open a LOCKED offer's details.
        val myPeerId: String = "",
        val isArbitrator: Boolean = false
    )

    init {
        observeDbOffers()
        startBackgroundSync()
        observeActiveTargets()
    }

    /**
     * Derive the top-bar quick-access targets from the DB:
     *  - chat: most recent offer that is locked (MATCHED/ESCROWED) and has a
     *    known counterparty (matchedPeerId for the creator, creatorPeerId for
     *    the acceptor).
     *  - escrow: most recent escrow id.
     */
    private fun observeActiveTargets() {
        viewModelScope.launch(Dispatchers.IO) {
            combine(
                offerDao.getAllOffers(),
                escrowDao.getAllEscrows()
            ) { offers, escrows ->
                val myId = try {
                    identityManager.getOrCreateIdentity().peerId
                } catch (_: Exception) { "" }

                val chatTarget = offers
                    .filter { it.status == "MATCHED" || it.status == "ESCROWED" }
                    .sortedByDescending { it.created_at }
                    .firstOrNull { offer ->
                        val peer = if (offer.creator_peer_id == myId) {
                            offer.matched_peer_id
                        } else {
                            offer.creator_peer_id
                        }
                        !peer.isNullOrBlank() && peer != myId
                    }
                    ?.let { it.offer_id to (if (it.creator_peer_id == myId) it.matched_peer_id!! else it.creator_peer_id) }

                val escrowTarget = escrows
                    .sortedByDescending { it.created_at }
                    .firstOrNull { it.status != "CANCELLED" }
                    ?.escrow_id

                _activeChat.value = chatTarget
                _activeEscrow.value = escrowTarget
            }.collect {}
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
                // Terminal trades (COMPLETED/CANCELLED) leave the marketplace
                // feed — a finished escrow's offer must not keep listing.
                // EscrowService marks the offer terminal on release/refund and
                // syncs it via kind:33336, so both devices converge.
                val live = offers.filter {
                    it.status != OfferStatus.COMPLETED &&
                        it.status != OfferStatus.CANCELLED
                }
                // Reputation ranking (post-trade only): higher-rep sellers first.
                // TradeOffer has no sellerPeerId — in this sell-only app the
                // offer creator IS the seller.
                val ranked = live.sortedByDescending {
                    reputationSystem.getReputation(it.creatorPeerId).score
                }
                val myId = runCatching {
                    identityManager.getOrCreateIdentity().peerId
                }.getOrDefault("")
                val isArb = runCatching {
                    identityManager.getArbitratorPubKeyHex()
                        .equals(NeoP2PConfig.ARBITRATOR_PUBKEY, ignoreCase = true)
                }.getOrDefault(false)
                HomeData(ranked, peers, myId, isArb)
            }.catch { e ->
                emit(HomeData(emptyList(), emptyList()))
                _uiState.value = UiState.Error("DB error: ${e.message}")
            }.collect { data ->
                _uiState.value = UiState.Success(data)
            }
        }
    }

    fun startBackgroundSync() {
        // P0-4-2: if the app started while the identity was locked (phone
        // locked at boot), the transport never came up and the relay never
        // learned our peerId — peers' messages then fail with "delivery
        // failed: peer not found". Guard with isActive() so the resume path
        // below can bring the transports up once the identity is unlocked.
        if (p2pTransport.isActive()) {
            Log.d("HomeViewModel", "Transport already active — skipping duplicate start")
            return
        }
        val myPubkey = try {
            identityManager.getOrCreateIdentity().nostrPubkeyHex
        } catch (e: IdentityLockedException) {
            // P0-4: identity gated behind device auth (unlock window expired).
            // Surface the unlock prompt so the user can re-arm the key, then
            // this method is retried from onAuthenticationSucceeded.
            _identityLocked.value = true
            Log.w("HomeViewModel", "Identity locked; prompting unlock: ${e.message}")
            return
        } catch (e: Exception) {
            // Unexpected identity failure — skip connecting until a refresh.
            Log.w("HomeViewModel", "Identity not available for sync: ${e.message}")
            return
        }
        viewModelScope.launch {
            // The orchestrator dispatches ALL inbound P2P messages (pre-key
            // handshake, chat, WebRTC signaling, offer relay) and starts the
            // transports — it must be running or peers' handshakes and
            // messages are silently dropped. It is idempotent, so the
            // identity-unlock retry path can call this again safely.
            orchestrator.start().onFailure {
                android.util.Log.w("HomeViewModel", "P2P orchestrator start failed: ${it.message}")
            }
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
            } catch (e: IdentityLockedException) {
                // P0-4: unlock window expired — surface the unlock prompt and retry.
                _identityLocked.value = true
                Log.w("HomeViewModel", "Identity locked on refresh; prompting unlock: ${e.message}")
            } catch (e: Exception) {
                Log.w("HomeViewModel", "Refresh failed: ${e.message}")
            } finally {
                _isRefreshing.value = false
            }
        }
    }
}
