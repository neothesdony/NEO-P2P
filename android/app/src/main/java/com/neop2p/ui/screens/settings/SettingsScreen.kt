package com.neop2p.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neop2p.NeoP2PConfig
import com.neop2p.R
import com.neop2p.data.p2p.*
import com.neop2p.ui.theme.NeoP2PTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onIdentityReset: () -> Unit,
    onArbitratorFeed: () -> Unit = {},
    onOemNotificationsClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val viewModel: SettingsViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    NeoP2PTheme {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(stringResource(R.string.home_cd_settings)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_arrow_back),
                                contentDescription = stringResource(R.string.general_back)
                            )
                        }
                    }
                )
            },
            content = { innerPadding ->
                val scrollState = rememberScrollState()
                var showResetDialog by remember { mutableStateOf(false) }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(16.dp)
                        .verticalScroll(scrollState)
                ) {
                    // Relays section
                    Text(stringResource(R.string.settings_nostr_relays), style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            state.relays.forEach { relay ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = relay.url,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = if (relay.isConnected) stringResource(R.string.settings_connected) else stringResource(R.string.settings_not_connected),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (relay.isConnected)
                                            MaterialTheme.colorScheme.primary
                                        else
                                            MaterialTheme.colorScheme.error
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            OutlinedTextField(
                                value = state.newRelayUrl,
                                onValueChange = { viewModel.updateNewRelayUrl(it) },
                                label = { Text(stringResource(R.string.settings_relay_url_label)) },
                                placeholder = { Text(stringResource(R.string.settings_relay_url_placeholder)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Button(
                                onClick = { viewModel.addRelay() },
                                enabled = state.canAddRelay
                            ) {
                                Text(stringResource(R.string.settings_add_relay))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Connectivity section
                    Text(stringResource(R.string.settings_connectivity), style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            // TURN server
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(stringResource(R.string.settings_turn_server))
                                Text(
                                    text = if (state.turnConfigured) stringResource(R.string.settings_configured) else stringResource(R.string.settings_not_configured),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (state.turnConfigured)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.error
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            OutlinedTextField(
                                value = state.turnUrl,
                                onValueChange = { /* TURN is configured at build time; field is read-only. */ },
                                label = { Text(stringResource(R.string.settings_turn_placeholder)) },
                                enabled = false,
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = stringResource(R.string.settings_turn_config_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            // STUN servers (read-only)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(stringResource(R.string.settings_default_stun))
                                Text(
                                    text = "stun:stun.l.google.com:19302",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Tor section
                    Text(stringResource(R.string.settings_privacy), style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(stringResource(R.string.settings_tor))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = stringResource(R.string.settings_tor_coming_soon),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(end = 8.dp)
                                    )
                                    Switch(
                                        checked = state.torEnabled,
                                        onCheckedChange = null,
                                        enabled = false
                                    )
                                }
                            }
                            Text(
                                text = stringResource(R.string.settings_tor_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(stringResource(R.string.settings_auto_connect))
                                Switch(
                                    checked = state.autoConnect,
                                    onCheckedChange = { viewModel.toggleAutoConnect(it) }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // About section
                    Text(stringResource(R.string.settings_about), style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(stringResource(R.string.settings_version))
                                Text(stringResource(R.string.settings_version_value), style = MaterialTheme.typography.labelSmall)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(stringResource(R.string.settings_network))
                                Text(stringResource(R.string.settings_network_value), style = MaterialTheme.typography.labelSmall)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(stringResource(R.string.settings_escrow))
                                Text(stringResource(R.string.settings_escrow_value), style = MaterialTheme.typography.labelSmall)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(stringResource(R.string.settings_fee))
                                Text(stringResource(R.string.settings_fee_value), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Arbitrator mode (only visible when the active identity IS the arbitrator)
                    if (state.isArbitrator) {
                        Text(stringResource(R.string.arbitrator_mode_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.tertiary)
                        Spacer(modifier = Modifier.height(8.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer
                            )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = stringResource(R.string.arbitrator_mode_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = onArbitratorFeed,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(stringResource(R.string.arbitrator_open_feed))
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    // Notifications help (OEM background-kill checklist)
                    Text(stringResource(R.string.settings_oem_title), style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onOemNotificationsClick),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(16.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_help),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = stringResource(R.string.settings_oem_entry),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                painter = painterResource(id = R.drawable.ic_arrow_back),
                                contentDescription = null,
                                modifier = Modifier
                                    .size(20.dp)
                                    .rotate(180f),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))

                    // Language (per-app override; applies on next launch)
                    Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            listOf(
                                "system" to R.string.settings_lang_system,
                                "id" to R.string.settings_lang_id,
                                "en" to R.string.settings_lang_en
                            ).forEach { (code, labelRes) ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { viewModel.setLocale(code) }
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = state.locale == code,
                                        onClick = { viewModel.setLocale(code) }
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = stringResource(labelRes),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                            Text(
                                text = stringResource(R.string.settings_lang_restart_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))

                    // Saved payment methods (reused across offers)
                    Text(stringResource(R.string.saved_methods_title), style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            if (state.savedMethods.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.saved_methods_empty),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                state.savedMethods.forEach { (methodId, details) ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                text = methodId.uppercase(),
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            Text(
                                                text = stringResource(R.string.saved_methods_account, details.accountNumber, details.accountHolder),
                                                style = MaterialTheme.typography.bodySmall,
                                                maxLines = 1
                                            )
                                        }
                                        TextButton(onClick = { viewModel.removeSavedMethod(methodId) }) {
                                            Text(stringResource(R.string.saved_methods_remove))
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.saved_methods_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))

                    // Blocked traders (local-only blocklist)
                    Text(stringResource(R.string.blocked_peers_title), style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            if (state.blockedPeers.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.blocked_peers_empty),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                state.blockedPeers.forEach { peerId ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = peerId.take(16) + if (peerId.length > 16) "…" else "",
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.weight(1f)
                                        )
                                        TextButton(onClick = { viewModel.unblockPeer(peerId) }) {
                                            Text(stringResource(R.string.peer_unblock))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))

                    // Danger zone
                    Text(stringResource(R.string.settings_danger_zone), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = stringResource(R.string.settings_reset_warning),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = { showResetDialog = true },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Text(stringResource(R.string.settings_reset_identity))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(32.dp))

                    // Fee wallet info
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(stringResource(R.string.settings_fee_wallet), style = MaterialTheme.typography.labelMedium)
                            Text(
                                text = stringResource(R.string.settings_commission_format, NeoP2PConfig.FEE_WALLET_ADDRESS),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3
                            )
                        }
                    }
                }

                if (showResetDialog) {
                    AlertDialog(
                        onDismissRequest = { showResetDialog = false },
                        title = { Text(stringResource(R.string.settings_reset_dialog_title)) },
                        text = { Text(stringResource(R.string.settings_reset_dialog_body)) },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    showResetDialog = false
                                    viewModel.resetIdentity()
                                    onIdentityReset()
                                }
                            ) {
                                Text(
                                    stringResource(R.string.settings_reset_confirm),
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showResetDialog = false }) {
                                Text(stringResource(R.string.general_cancel))
                            }
                        }
                    )
                }
            }
        )
    }
}

// ─── ViewModel ───────────────────────────────────────────────
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val identityManager: IdentityManager,
    private val p2pTransport: HybridP2PTransport,
    private val nostrClient: NostrClient,
    private val blockedPeerStore: com.neop2p.data.local.BlockedPeerStore,
    private val savedPaymentMethods: com.neop2p.data.local.SavedPaymentMethodsStore,
    private val localeStore: com.neop2p.data.local.LocaleStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsState())
    val uiState: StateFlow<SettingsState> = _uiState.asStateFlow()

    data class SettingsState(
        val relays: List<NostrClient.NostrRelay> = emptyList(),
        val canAddRelay: Boolean = false,
        val newRelayUrl: String = "",
        val turnUrl: String = "",
        val turnConfigured: Boolean = false,
        val torEnabled: Boolean = false,
        val autoConnect: Boolean = true,
        // True when the active identity's derived arbitrator key matches the
        // configured arbitrator pubkey (admin identity) — unlocks the
        // Arbitrator Mode dispute feed.
        val isArbitrator: Boolean = false,
        // Locally blocked peers (their offers are hidden from the market feed).
        val blockedPeers: List<String> = emptyList(),
        // Saved payment methods (bank/QRIS/e-wallet) reused across offers.
        val savedMethods: Map<String, com.neop2p.domain.model.PaymentDetails> = emptyMap(),
        // Per-app language override: "system" / "id" / "en".
        val locale: String = "system"
    ) {
        companion object {
            private val WEBSOCKET_URL_REGEX =
                Regex("^(wss?://|https?://)?[\\w.-]+(:\\d+)?(/.*)?$", RegexOption.IGNORE_CASE)
        }

        val isValidNewRelayUrl: Boolean
            get() {
                val url = newRelayUrl.trim()
                return url.isNotBlank() && (url.startsWith("ws://", ignoreCase = true) ||
                    url.startsWith("wss://", ignoreCase = true) ||
                    url.startsWith("http://", ignoreCase = true) ||
                    url.startsWith("https://", ignoreCase = true) ||
                    WEBSOCKET_URL_REGEX.matches(url))
            }
    }

    init {
        // Live relay status (connected/disconnected) from the Nostr client.
        viewModelScope.launch {
            nostrClient.relays.collect { relays ->
                _uiState.update { it.copy(relays = relays) }
            }
        }
        // Arbitrator gate: true only when THIS identity is the arbitrator.
        val isArb = runCatching {
            identityManager.getArbitratorPubKeyHex()
                .equals(NeoP2PConfig.ARBITRATOR_PUBKEY, ignoreCase = true)
        }.getOrDefault(false)
        _uiState.update { it.copy(isArbitrator = isArb, blockedPeers = blockedPeerStore.blockedPeerIds()) }
        _uiState.update { it.copy(savedMethods = savedPaymentMethods.all()) }
        _uiState.update { it.copy(locale = localeStore.locale()) }
    }

    fun setLocale(code: String) {
        localeStore.setLocale(code)
        _uiState.update { it.copy(locale = code) }
    }

    fun removeSavedMethod(methodId: String) {
        savedPaymentMethods.remove(methodId)
        _uiState.update { it.copy(savedMethods = savedPaymentMethods.all()) }
    }

    fun unblockPeer(peerId: String) {
        blockedPeerStore.unblock(peerId)
        _uiState.update { it.copy(blockedPeers = blockedPeerStore.blockedPeerIds()) }
    }

    fun addRelay() {
        val state = _uiState.value
        val url = state.newRelayUrl.trim()
        if (url.isBlank() || state.relays.any { it.url.equals(url, ignoreCase = true) }) return
        _uiState.update { current ->
            current.copy(
                relays = current.relays + listOf(NostrClient.NostrRelay(url)),
                newRelayUrl = "",
                canAddRelay = false
            )
        }
        // Trigger reconnect
        viewModelScope.launch(Dispatchers.IO) {
            nostrClient.addRelay(url)
        }
    }

    fun updateNewRelayUrl(url: String) {
        _uiState.update {
            val trimmed = url.trim()
            val valid = trimmed.isNotBlank() &&
                !it.relays.any { relay -> relay.url.equals(trimmed, ignoreCase = true) }
            it.copy(newRelayUrl = url, canAddRelay = valid)
        }
    }

    fun toggleAutoConnect(enabled: Boolean) {
        _uiState.update { it.copy(autoConnect = enabled) }
        if (!enabled) {
            viewModelScope.launch(Dispatchers.IO) {
                nostrClient.disconnect()
            }
        }
    }

    fun resetIdentity() {
        viewModelScope.launch(Dispatchers.IO) {
            identityManager.resetIdentity()
            // App will restart to Onboarding
        }
    }
}
