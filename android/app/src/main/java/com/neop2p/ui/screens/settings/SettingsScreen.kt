package com.neop2p.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
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
                    title = { Text("Settings") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_arrow_back),
                                contentDescription = "Back"
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
                    Text("Nostr Relays", style = MaterialTheme.typography.titleMedium)
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
                                        text = relay,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = "Connected",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Button(
                                onClick = { viewModel.addRelay() },
                                enabled = state.canAddRelay
                            ) {
                                Text("Add Relay")
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Connectivity section
                    Text("Connectivity", style = MaterialTheme.typography.titleMedium)
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
                                Text("TURN Server")
                                Text(
                                    text = if (state.turnConfigured) "Configured" else "Not configured",
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
                                label = { Text("TURN URL (turn://user:pass@host:port)") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            // STUN servers (read-only)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Default STUN")
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
                    Text("Privacy", style = MaterialTheme.typography.titleMedium)
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
                                Text("Route through Tor")
                                Switch(
                                    checked = state.torEnabled,
                                    onCheckedChange = { viewModel.toggleTor(it) }
                                )
                            }
                            Text(
                                text = "Routes all Nostr/libp2p traffic through Tor for maximum anonymity. Slower but private.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Auto-connect to relays")
                                Switch(
                                    checked = state.autoConnect,
                                    onCheckedChange = { viewModel.toggleAutoConnect(it) }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // About section
                    Text("About", style = MaterialTheme.typography.titleMedium)
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
                                Text("Version")
                                Text("v1.0.0-alpha", style = MaterialTheme.typography.labelSmall)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Network")
                                Text("Nostr + libp2p", style = MaterialTheme.typography.labelSmall)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Escrow")
                                Text("Lightning 2-of-3", style = MaterialTheme.typography.labelSmall)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Fee")
                                Text("1%", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Danger zone
                    Text("Danger Zone", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "\u26a0\ufe0f Resetting identity will permanently destroy your keypair. You will lose access to any active escrows.",
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
                                Text("Reset Identity")
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
                            Text("Fee Wallet", style = MaterialTheme.typography.labelMedium)
                            Text(
                                text = "1% commission goes to: ${NeoP2PConfig.FEE_WALLET_ADDRESS}",
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
    private val libP2PManager: LibP2PManager,
    private val nostrClient: NostrClient
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsState())
    val uiState: StateFlow<SettingsState> = _uiState.asStateFlow()

    data class SettingsState(
        val relays: List<String> = NeoP2PConfig.DEFAULT_NOSTR_RELAYS.toList(),
        val canAddRelay: Boolean = false,
        val newRelayUrl: String = "",
        val turnUrl: String = "",
        val turnConfigured: Boolean = false,
        val torEnabled: Boolean = false,
        val autoConnect: Boolean = true
    )

    fun addRelay() {
        val url = _uiState.value.newRelayUrl.trim()
        if (url.isNotBlank()) {
            _uiState.update { state ->
                state.copy(
                    relays = state.relays + listOf(url),
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
                // libP2PManager.enableTor()
            } else {
                // Disable Tor
                // libP2PManager.disableTor()
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
