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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.text.input.KeyboardType
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
import com.neop2p.ui.components.NeoEmptyState
import com.neop2p.ui.theme.NeoP2PTheme
import com.neop2p.ui.util.formatBtc
import com.neop2p.ui.util.formatDurationShort
import com.neop2p.ui.util.formatIdr
import com.neop2p.ui.util.formatIdrNoCurrency
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
    onChatClick: (String, String) -> Unit,
    onEscrowClick: (String) -> Unit,
    onNavigate: (com.neop2p.ui.components.AppTab) -> Unit = {},
    onOpenOemNotifications: () -> Unit = {},
    onInvite: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val viewModel: HomeViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val activeChat by viewModel.activeChat.collectAsStateWithLifecycle()
    val activeEscrow by viewModel.activeEscrow.collectAsStateWithLifecycle()
    val activeChatUnread by viewModel.activeChatUnread.collectAsStateWithLifecycle()
    val relayConnected by viewModel.relayConnected.collectAsStateWithLifecycle()

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
    var notifBannerDismissed by remember { mutableStateOf(false) }
    val notifPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { /* granted or denied — service still runs; user can enable in settings */ }
    fun hasNotifPermission(): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (!hasNotifPermission()) {
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
                            // Unread badge on the active-trade chat (per-offer
                            // count, zero when no active chat).
                            BadgedBox(
                                badge = {
                                    if (activeChatUnread > 0) {
                                        Badge {
                                            Text(if (activeChatUnread > 99) "99+" else activeChatUnread.toString())
                                        }
                                    }
                                }
                            ) {
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
                        // Navigation moved to the bottom bar (Market / Wallet /
                        // Trades / Profile); the top bar keeps only contextual
                        // quick access to the active trade (chat + escrow).
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
                            val portfolio by viewModel.portfolio.collectAsStateWithLifecycle()
                            HomeContent(
                                offers = data.offers,
                                peers = data.peers,
                                myPeerId = data.myPeerId,
                                isArbitrator = data.isArbitrator,
                                isRefreshing = viewModel.isRefreshing.collectAsStateWithLifecycle().value,
                                relayConnected = relayConnected,
                                portfolio = portfolio,
                                showNotifBanner = !notifBannerDismissed && !hasNotifPermission(),
                                onNotifBannerDismiss = { notifBannerDismissed = true },
                                onOpenOemNotifications = onOpenOemNotifications,
                                onInvite = onInvite,
                                onCreateOffer = onCreateOffer,
                                onOfferClick = onOfferClick,
                                onRefresh = { viewModel.refresh() },
                                onBlockPeer = { peerId -> viewModel.blockPeer(peerId) },
                                onNavigate = onNavigate
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
    relayConnected: Boolean,
    portfolio: HomeViewModel.PortfolioHeader = HomeViewModel.PortfolioHeader(),
    showNotifBanner: Boolean = false,
    onNotifBannerDismiss: () -> Unit = {},
    onOpenOemNotifications: () -> Unit = {},
    onInvite: () -> Unit = {},
    onCreateOffer: () -> Unit,
    onOfferClick: (String) -> Unit,
    onRefresh: () -> Unit,
    onBlockPeer: (String) -> Unit = {},
    onNavigate: (com.neop2p.ui.components.AppTab) -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Market filter: method chips + min/max IDR. Local-only (filters the
    // already-loaded feed) — no server round-trip.
    var methodFilter by remember { mutableStateOf<String?>(null) }
    var minIdr by remember { mutableStateOf("") }
    var maxIdr by remember { mutableStateOf("") }
    // Sort: newest first / soonest expiry. Local-only, in-hand data.
    var sortMode by remember { mutableStateOf(SortMode.NEWEST) }

    val filtered = remember(offers, methodFilter, minIdr, maxIdr, sortMode) {
        val base = offers.filter { offer ->
            (methodFilter == null || methodFilter in offer.fiatMethods) &&
                (minIdr.isBlank() || offer.fiatAmount >= (minIdr.toLongOrNull() ?: 0L)) &&
                (maxIdr.isBlank() || offer.fiatAmount <= (maxIdr.toLongOrNull() ?: Long.MAX_VALUE))
        }
        when (sortMode) {
            SortMode.NEWEST -> base.sortedByDescending { it.createdAt }
            SortMode.TTL_SHORTEST -> base.sortedBy { it.expiresAt ?: Long.MAX_VALUE }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Portfolio header: open trades + locked + unread — marketplace overview
        // without opening Wallet. Keeps user aware of funds at stake.
        if (portfolio.openTrades > 0 || portfolio.lockedSats > 0L || portfolio.unreadTotal > 0) {
            PortfolioHeaderCard(
                portfolio = portfolio,
                relayConnected = relayConnected,
                onOpenTrades = { onNavigate(com.neop2p.ui.components.AppTab.TRADES) }
            )
        }
        // Notification-denied banner: relay-fed market needs notifications for
        // takes/paid/release events — silent denial = missed trades. Dismissable
        // per session; "Perbaiki" jumps to the per-brand OEM kill guide.
        if (showNotifBanner) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_warning),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.home_notif_denied),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onOpenOemNotifications) {
                        Text(stringResource(R.string.home_notif_fix))
                    }
                    IconButton(onClick = onNotifBannerDismiss) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.general_close)
                        )
                    }
                }
            }
        }

        // Sync-status banner: relay-fed market, so offline = stale feed.
        if (!relayConnected) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_warning),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.home_offline),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Filter chip row: Semua / per-method + IDR range.
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            FilterChip(
                selected = methodFilter == null,
                onClick = { methodFilter = null },
                label = { Text(stringResource(R.string.home_filter_all)) }
            )
            NeoP2PConfig.FIAT_METHODS.forEach { method ->
                val selected = methodFilter == method.id
                FilterChip(
                    selected = selected,
                    onClick = { methodFilter = if (selected) null else method.id },
                    label = { Text(method.displayNameId) }
                )
            }
            // Sort toggle: newest / soonest expiry.
            var sortMenuOpen by remember { mutableStateOf(false) }
            Box {
                FilterChip(
                    selected = false,
                    onClick = { sortMenuOpen = true },
                    label = {
                        Text(
                            stringResource(
                                when (sortMode) {
                                    SortMode.NEWEST -> R.string.home_sort_newest
                                    SortMode.TTL_SHORTEST -> R.string.home_sort_ttl
                                }
                            )
                        )
                    }
                )
                DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.home_sort_newest)) },
                        onClick = { sortMode = SortMode.NEWEST; sortMenuOpen = false }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.home_sort_ttl)) },
                        onClick = { sortMode = SortMode.TTL_SHORTEST; sortMenuOpen = false }
                    )
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            OutlinedTextField(
                value = minIdr,
                onValueChange = { minIdr = it.filter { c -> c.isDigit() }.take(12) },
                label = { Text(stringResource(R.string.home_filter_min)) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            OutlinedTextField(
                value = maxIdr,
                onValueChange = { maxIdr = it.filter { c -> c.isDigit() }.take(12) },
                label = { Text(stringResource(R.string.home_filter_max)) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize()
            ) {
                // Offer feed
                if (filtered.isEmpty()) {
                    NeoEmptyState(
                        painter = painterResource(id = R.drawable.ic_trending_up),
                        title = stringResource(R.string.home_no_offers),
                        hint = stringResource(R.string.home_no_offers_hint),
                        actions = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Button(onClick = onCreateOffer) {
                                    Text(stringResource(R.string.home_empty_create_offer))
                                }
                                Spacer(Modifier.height(8.dp))
                                OutlinedButton(onClick = onInvite) {
                                    Text(stringResource(R.string.home_empty_invite))
                                }
                            }
                        }
                    )
                } else {
                    TradeOfferList(
                        offers = filtered,
                        peers = peers,
                        myPeerId = myPeerId,
                        isArbitrator = isArbitrator,
                        onOfferClick = onOfferClick,
                        onBlockPeer = onBlockPeer
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
    } // Column
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TradeOfferList(
    offers: List<TradeOffer>,
    peers: List<Peer>,
    myPeerId: String,
    isArbitrator: Boolean,
    onOfferClick: (String) -> Unit,
    onBlockPeer: (String) -> Unit = {},
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
                onClick = { onOfferClick(offer.offerId) },
                onBlockPeer = onBlockPeer
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

@Composable
private fun PortfolioHeaderCard(
    portfolio: HomeViewModel.PortfolioHeader,
    relayConnected: Boolean,
    onOpenTrades: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clickable(onClick = onOpenTrades),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.home_portfolio_open, portfolio.openTrades),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                if (portfolio.lockedSats > 0L) {
                    Text(
                        text = stringResource(R.string.home_portfolio_locked, formatBtc(portfolio.lockedSats)),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                if (portfolio.unreadTotal > 0) {
                    Text(
                        text = stringResource(R.string.home_portfolio_unread, portfolio.unreadTotal),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = if (relayConnected) stringResource(R.string.home_connected) else stringResource(R.string.home_syncing),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (relayConnected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.error
                )
                Text(stringResource(R.string.home_portfolio_tap), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

/** Offer feed sort modes (HomeContent). */
private enum class SortMode { NEWEST, TTL_SHORTEST }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TradeOfferCard(
    offer: TradeOffer,
    peer: Peer?,
    canOpen: Boolean,
    onClick: () -> Unit,
    onBlockPeer: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val isBuy = offer.type == OfferType.BUY
    val isLocked = offer.status != OfferStatus.OPEN
    val accentColor = if (isBuy) MaterialTheme.colorScheme.buyColor else MaterialTheme.colorScheme.sellColor
    val showBlockDialog = remember { mutableStateOf(false) }

    if (showBlockDialog.value) {
        AlertDialog(
            onDismissRequest = { showBlockDialog.value = false },
            title = { Text(stringResource(R.string.peer_block_confirm_title)) },
            text = { Text(stringResource(R.string.peer_block_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showBlockDialog.value = false
                    onBlockPeer(offer.creatorPeerId)
                }) {
                    Text(stringResource(R.string.peer_blocked))
                }
            },
            dismissButton = {
                TextButton(onClick = { showBlockDialog.value = false }) {
                    Text(stringResource(R.string.general_cancel))
                }
            }
        )
    }

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
                    text = stringResource(R.string.home_fiat_amount, formatIdr(offer.fiatAmount)),
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
                        text = stringResource(R.string.home_price_per_btc, formatIdrNoCurrency(offer.pricePerUnit)),
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
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
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
                    // Expiry badge: stale offers stay visible-but-blocked
                    // (BasicSwap "offer valid" pattern) — the countdown shows
                    // under 1h, "Kedaluwarsa" when past the TTL.
                    val expiresAt = offer.expiresAt
                    if (expiresAt != null) {
                        val remaining = expiresAt - System.currentTimeMillis()
                        if (remaining <= 0) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.offer_expired),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 1
                            )
                        } else if (remaining < 60L * 60 * 1000) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = stringResource(
                                    R.string.offer_expires_in,
                                    formatDurationShort(remaining)
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 1
                            )
                        }
                    }
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
    private val orchestrator: P2POrchestrator,
    private val reputationSystem: ReputationSystem,
    private val offerDao: OfferDao,
    private val peerDao: PeerDao,
    private val escrowDao: EscrowDao,
    private val escrowService: com.neop2p.data.escrow.EscrowService,
    private val blockedPeerStore: com.neop2p.data.local.BlockedPeerStore,
    private val chatMessageDao: ChatMessageDao
) : androidx.lifecycle.ViewModel() {

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    // Unread chat count for the ACTIVE trade's chat (top-bar chat icon badge).
    // Zero when no active chat. Computed per-offer so the badge never leaks
    // counts from other trades (F17: no identity/quantity in notification).
    private val _activeChatUnread = MutableStateFlow(0)
    val activeChatUnread: StateFlow<Int> = _activeChatUnread.asStateFlow()

    // Portfolio header: open trades count + locked sats (seller deposits).
    // Derived from escrowDao + chat unread so the marketplace gives a
    // wallet-like overview without opening Wallet.
    data class PortfolioHeader(
        val openTrades: Int = 0,
        val lockedSats: Long = 0L,
        val unreadTotal: Int = 0
    )
    private val _portfolio = MutableStateFlow(PortfolioHeader())
    val portfolio: StateFlow<PortfolioHeader> = _portfolio.asStateFlow()

    // RNS transport connectivity for the sync banner: true when the RNS
    // transport is running (the market feed is RNS-fed).
    val relayConnected: StateFlow<Boolean> = MutableStateFlow(true)

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

    /** True if the RNS transport is currently running. */
    fun isTransportActive(): Boolean = orchestrator.isRunning()

    /** Block a peer locally — their offers leave the feed immediately. */
    fun blockPeer(peerId: String) {
        if (peerId.isBlank()) return
        blockedPeerStore.block(peerId)
        refresh()
    }

    /** Unblock a peer (their offers re-enter on the next relay ingest). */
    fun unblockPeer(peerId: String) {
        blockedPeerStore.unblock(peerId)
        refresh()
    }

    fun blockedPeers(): List<String> = blockedPeerStore.blockedPeerIds()

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
        observePortfolio()
    }

    private fun observePortfolio() {
        viewModelScope.launch(Dispatchers.IO) {
            escrowDao.getAllEscrows().collect { escrows ->
                val myId = runCatching { identityManager.getOrCreateIdentity().peerId }.getOrDefault("")
                val activeEscrows = escrows.filter {
                    it.status !in setOf("RELEASED", "REFUNDED", "CANCELLED")
                }
                val locked = escrows.filter {
                    it.seller_peer_id == myId && it.status !in setOf("RELEASED", "REFUNDED", "CANCELLED")
                }.sumOf { it.deposit_amount_sats }
                // Unread total across all escrow-linked offers
                val unread = runCatching {
                    var total = 0
                    for (e in escrows) {
                        total += chatMessageDao.countUnreadByOffer(e.offer_id)
                    }
                    total
                }.getOrDefault(_activeChatUnread.value)
                _portfolio.value = PortfolioHeader(
                    openTrades = activeEscrows.size,
                    lockedSats = locked,
                    unreadTotal = unread
                )
            }
        }
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

                // Unread badge for the active chat (per-offer, active trade only).
                if (chatTarget == null) {
                    _activeChatUnread.value = 0
                } else {
                    _activeChatUnread.value = chatMessageDao.countUnreadByOffer(chatTarget.first)
                }
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
                // PAUSED offers (seller soft-lock) also leave the public feed,
                // BUT the creator keeps seeing their own so they can
                // re-activate (T9 — pause/re-activate flow).
                val myIdFeed = runCatching {
                    identityManager.getOrCreateIdentity().peerId
                }.getOrDefault("")
                val live = offers.filter {
                    it.status != OfferStatus.COMPLETED &&
                        it.status != OfferStatus.CANCELLED &&
                        (it.status != OfferStatus.PAUSED || it.creatorPeerId == myIdFeed) &&
                        !blockedPeerStore.isBlocked(it.creatorPeerId)
                }
                // Reputation ranking (post-trade only): higher-rep sellers first.
                // TradeOffer has no sellerPeerId — in this sell-only app the
                // offer creator IS the seller.
                val ranked = live.sortedByDescending {
                    reputationSystem.getReputation(it.creatorPeerId).score
                }
                val myId = myIdFeed
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
        // locked at boot), the transport never came up and peers' messages
        // then fail with "delivery failed: peer not found". Guard with
        // isActive() so the resume path below can bring the transport up
        // once the identity is unlocked.
        if (orchestrator.isRunning()) {
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
            // handshake, chat, offer relay) and starts the transport — it must
            // be running or peers' handshakes and messages are silently
            // dropped. It is idempotent, so the identity-unlock retry path can
            // call this again safely.
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
                // Force a reconnect cycle so offers stream in fresh from RNS.
                val myPubkey = identityManager.getOrCreateIdentity().nostrPubkeyHex
                orchestrator.start()
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
