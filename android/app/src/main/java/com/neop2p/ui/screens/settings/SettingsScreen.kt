package com.neop2p.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(16.dp)
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
                                onValueChange = { viewModel.updateTurnUrl(it) },
                                label = { Text(stringResource(R.string.settings_turn_placeholder)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
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
                                Switch(
                                    checked = state.torEnabled,
                                    onCheckedChange = { viewModel.toggleTor(it) }
                                )
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
                                onClick = { viewModel.resetIdentity() },
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
            }
        )
    }
}

// ─── ViewModel ───────────────────────────────────────────────
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val identityManager: IdentityManager,
    private val p2pTransport: HybridP2PTransport,
    private val nostrClient: NostrClient
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
        val autoConnect: Boolean = true
    )

    init {
        // Live relay status (connected/disconnected) from the Nostr client.
        viewModelScope.launch {
            nostrClient.relays.collect { relays ->
                _uiState.update { it.copy(relays = relays) }
            }
        }
    }

    fun addRelay() {
        val url = _uiState.value.newRelayUrl.trim()
        if (url.isNotBlank()) {
            _uiState.update { state ->
                state.copy(
                    relays = state.relays + listOf(NostrClient.NostrRelay(url)),
                    newRelayUrl = "",
                    canAddRelay = false
                )
            }
            // Trigger reconnect
            viewModelScope.launch(Dispatchers.IO) {
                nostrClient.addRelay(url)
            }
        }
    }

    fun updateTurnUrl(url: String) {
        _uiState.update { it.copy(turnUrl = url, turnConfigured = url.isNotBlank()) }
    }

    fun toggleTor(enabled: Boolean) {
        _uiState.update { it.copy(torEnabled = enabled) }
        viewModelScope.launch(Dispatchers.IO) {
            if (enabled) {
                // Start Tor proxy
                // p2pTransport.enableTor()
            } else {
                // Disable Tor
                // p2pTransport.disableTor()
            }
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
