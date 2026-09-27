package com.neop2p.ui.screens.settings

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neop2p.BuildConfig
import com.neop2p.NeoP2PConfig
import com.neop2p.R
import com.neop2p.data.local.TransportNodeStore
import com.neop2p.data.p2p.*
import com.neop2p.data.portability.toBundle
import com.neop2p.data.portability.toEntity
import com.neop2p.data.wallet.WalletService
import com.neop2p.ui.theme.NeoP2PTheme
import com.neop2p.ui.util.SecureScreen
import com.neop2p.ui.util.authenticateForSecret
import com.neop2p.ui.util.copySensitive
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onIdentityReset: () -> Unit,
    onOemNotificationsClick: () -> Unit = {},
    onOpenLegal: (String) -> Unit = {},
    onOpenHelp: () -> Unit = {},
    onIdentityRestored: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val viewModel: SettingsViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Live embedded-Tor state: the toggle + status line + Retry read this.
    val torState by viewModel.torState.collectAsStateWithLifecycle()
    // Transport-node add form (Tier 3): host + port for an extra RNS node.
    var newNodeHost by remember { mutableStateOf("") }
    var newNodePort by remember { mutableStateOf(TransportNodeStore.DEFAULT_PORT.toString()) }

    // Hoisted above the Scaffold: the snackbarHost param and the copy action
    // both need these (Scaffold params cannot see content-lambda locals).
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    // Recovery phrase: auth-gated reveal (P0-4 pattern).
    var showSeedDialog by remember { mutableStateOf(false) }
    var seedVisible by remember { mutableStateOf(false) }
    var seedWords by remember { mutableStateOf<List<String>>(emptyList()) }
    // No device auth (no fingerprint AND no PIN/pattern) → the BiometricPrompt
    // fails instantly with ERROR_NO_BIOMETRICS. Offer "set a screen lock" or
    // an explicit show-anyway (a lock-less phone is already open).
    var showNoAuthDialog by remember { mutableStateOf(false) }

    // Phase 3 (C5): passphrase-encrypted identity + trade-data export via SAF.
    var showExportDialog by remember { mutableStateOf(false) }
    var exportPassphrase by remember { mutableStateOf("") }
    var pendingPassphrase by remember { mutableStateOf<CharArray?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val pass = pendingPassphrase
        pendingPassphrase = null
        if (uri != null && pass != null) {
            scope.launch {
                runCatching {
                    val bytes = viewModel.exportBundle(pass)
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    }
                }.onSuccess {
                    snackbarHostState.showSnackbar(context.getString(R.string.export_success))
                }.onFailure { e ->
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.export_failed, e.message ?: "unknown")
                    )
                }
            }
        }
    }

    // Phase 3 (C5): import an encrypted identity/trade-data bundle via SAF.
    var showImportDialog by remember { mutableStateOf(false) }
    var importPassphrase by remember { mutableStateOf("") }
    var pendingImportBytes by remember { mutableStateOf<ByteArray?>(null) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val bytes = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
            if (bytes != null) {
                pendingImportBytes = bytes
                importPassphrase = ""
                showImportDialog = true
            }
        }
    }

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
            snackbarHost = { SnackbarHost(snackbarHostState) },
            content = { innerPadding ->
                val scrollState = rememberScrollState()
                var showResetDialog by remember { mutableStateOf(false) }
                var showDestroyDialog by remember { mutableStateOf(false) }
                var destroyConfirmText by remember { mutableStateOf("") }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(16.dp)
                        .verticalScroll(scrollState)
                ) {
                    // RNS transport section: the built-in default node is
                    // always connected; extra nodes (Tier 3) can be added.
                    // Every node is a packet ferry, not a trust anchor —
                    // traffic stays end-to-end encrypted and announces are
                    // signed, so more nodes = more reach, never less security.
                    Text(stringResource(R.string.settings_rns_transport_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
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
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.settings_rns_transport),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = stringResource(
                                        if (state.transportReady) R.string.settings_connected
                                        else R.string.settings_transport_offline
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (state.transportReady) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    }
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.settings_rns_transport_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            // Extra transport nodes (Tier 3)
                            if (state.transportNodes.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.settings_transport_nodes_empty),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                state.transportNodes.forEach { node ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "${node.host}:${node.port}",
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.weight(1f)
                                        )
                                        TextButton(onClick = { viewModel.removeTransportNode(node.host, node.port) }) {
                                            Text(stringResource(R.string.settings_transport_node_remove))
                                        }
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            // Add-node form
                            OutlinedTextField(
                                value = newNodeHost,
                                onValueChange = { newNodeHost = it },
                                label = { Text(stringResource(R.string.settings_transport_node_host_label)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            OutlinedTextField(
                                value = newNodePort,
                                onValueChange = { newNodePort = it.filter { c -> c.isDigit() }.take(5) },
                                label = { Text(stringResource(R.string.settings_transport_node_port_label)) },
                                singleLine = true,
                                isError = newNodePort.isNotBlank() && !validTransportPort(newNodePort),
                                supportingText = {
                                    if (newNodePort.isNotBlank() && !validTransportPort(newNodePort)) {
                                        Text(stringResource(R.string.settings_transport_node_port_invalid))
                                    }
                                },
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    viewModel.addTransportNode(newNodeHost, newNodePort)
                                    newNodeHost = ""
                                    newNodePort = TransportNodeStore.DEFAULT_PORT.toString()
                                },
                                enabled = newNodeHost.isNotBlank() && validTransportPort(newNodePort),
                                modifier = Modifier.fillMaxWidth().height(48.dp)
                            ) {
                                Text(stringResource(R.string.settings_transport_node_add))
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            // Public community nodes (2026-09-10): verified
                            // public RNS transport nodes, one-tap Add. Opt-in
                            // only — never auto-connected. Pure packet ferries
                            // on an open mesh (announces signed, traffic E2EE).
                            Text(
                                text = stringResource(R.string.settings_transport_public_title),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            state.communityPresets.forEach { node ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${node.host}:${node.port}",
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(onClick = { viewModel.addTransportNode(node.host, node.port.toString()) }) {
                                        Text(stringResource(R.string.settings_transport_node_add))
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Tor section: opt-in routing of the clearnet HTTP
                    // chokepoint (chain data, market price, update check).
                    // Off by default; the RNS/LXMF transport always stays
                    // direct. Non-connected states render a status line
                    // (fail-closed, never a silent fallback) and a failure
                    // offers Retry.
                    Text(stringResource(R.string.settings_tor_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            ListItem(
                                headlineContent = { Text(stringResource(R.string.settings_tor_toggle)) },
                                supportingContent = {
                                    val labelKey = com.neop2p.data.tor.TorStatusLabel.of(torState)
                                    Text(
                                        buildString {
                                            append(stringResource(R.string.settings_tor_desc))
                                            if (labelKey != null) append("\n").append(stringResource(labelKeyToRes(labelKey)))
                                        }
                                    )
                                },
                                trailingContent = {
                                    Switch(
                                        checked = torState !is com.neop2p.data.network.TorState.Disabled,
                                        onCheckedChange = { viewModel.setTorEnabled(it) },
                                    )
                                },
                            )
                            if (torState is com.neop2p.data.network.TorState.Failed) {
                                TextButton(onClick = { viewModel.retryTor() }) {
                                    Text(stringResource(R.string.settings_tor_retry))
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // About section
                    Text(stringResource(R.string.settings_about), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
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
                                Text("v" + BuildConfig.VERSION_NAME, style = MaterialTheme.typography.labelSmall)
                            }
                            HorizontalDivider(Modifier.padding(vertical = 8.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !state.updateChecking) {
                                        viewModel.checkForUpdates()
                                    }
                                    .padding(vertical = 12.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.settings_check_updates),
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f)
                                )
                                when (val result = state.updateResult) {
                                    null -> if (state.updateChecking) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp
                                        )
                                    }
                                    is UpdateCheckResult.Available -> {
                                        Text(
                                            text = stringResource(
                                                R.string.settings_update_available, result.tag
                                            ),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(end = 8.dp)
                                        )
                                        TextButton(onClick = {
                                            runCatching {
                                                context.startActivity(
                                                    Intent(Intent.ACTION_VIEW, android.net.Uri.parse(result.url))
                                                )
                                            }
                                        }) {
                                            Text(stringResource(R.string.settings_update_open))
                                        }
                                    }
                                    UpdateCheckResult.UpToDate -> Text(
                                        text = stringResource(R.string.settings_update_uptodate),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    UpdateCheckResult.Error -> Text(
                                        text = stringResource(R.string.settings_update_error),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                            HorizontalDivider(Modifier.padding(vertical = 8.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenLegal("terms") }
                                    .padding(vertical = 12.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.settings_terms),
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenLegal("privacy") }
                                    .padding(vertical = 12.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.settings_privacy_policy),
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenHelp() }
                                    .padding(vertical = 12.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.settings_help),
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        activity?.let { act ->
                                            authenticateForSecret(act, onSuccess = {
                                                exportPassphrase = ""
                                                showExportDialog = true
                                            })
                                        }
                                    }
                                    .padding(vertical = 12.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.settings_export_identity),
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        activity?.let { act ->
                                            authenticateForSecret(act, onSuccess = {
                                                importLauncher.launch(arrayOf("application/octet-stream"))
                                            })
                                        }
                                    }
                                    .padding(vertical = 12.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.settings_import_identity),
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f)
                                )
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

                    // Notifications help (OEM background-kill checklist)
                    Text(stringResource(R.string.settings_oem_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
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
                    Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
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
                    Text(stringResource(R.string.saved_methods_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
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
                    Text(stringResource(R.string.blocked_peers_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
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

                    // Reported traders (F18, local-only trace)
                    Text(stringResource(R.string.settings_reported_peers), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            if (state.reportedPeers.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.peer_report_empty),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                state.reportedPeers.forEach { report ->
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp)
                                    ) {
                                        Row(
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(
                                                text = report.peerId.take(16) + if (report.peerId.length > 16) "…" else "",
                                                style = MaterialTheme.typography.bodySmall,
                                                modifier = Modifier.weight(1f)
                                            )
                                            TextButton(onClick = { viewModel.removeReport(report.peerId) }) {
                                                Text(stringResource(R.string.peer_report_remove))
                                            }
                                        }
                                        Text(
                                            text = report.reason,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))

                    // Recovery phrase — auth-gated reveal so the seed can be
                    // recovered after onboarding (seed loss = wallet loss).
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = stringResource(R.string.settings_show_seed),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    val act = activity
                                    if (act != null) {
                                        // Pre-check: with no fingerprint AND no
                                        // PIN/pattern the prompt dies instantly
                                        // (ERROR_NO_BIOMETRICS) — surface a
                                        // fallback dialog instead of silence.
                                        val canAuth = BiometricManager.from(act)
                                            .canAuthenticate(
                                                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                                                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
                                            ) == BiometricManager.BIOMETRIC_SUCCESS
                                        if (!canAuth) {
                                            showNoAuthDialog = true
                                            return@Button
                                        }
                                        val executor = ContextCompat.getMainExecutor(act)
                                        val prompt = BiometricPrompt(
                                            act, executor,
                                            object : BiometricPrompt.AuthenticationCallback() {
                                                override fun onAuthenticationSucceeded(
                                                    result: BiometricPrompt.AuthenticationResult
                                                ) {
                                                    super.onAuthenticationSucceeded(result)
                                                    seedWords = viewModel.seedPhrase()
                                                    seedVisible = false
                                                    showSeedDialog = true
                                                }

                                                override fun onAuthenticationError(
                                                    errorCode: Int, errString: CharSequence
                                                ) {
                                                    super.onAuthenticationError(errorCode, errString)
                                                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                                                        errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                                                    ) {
                                                        android.util.Log.w("Settings", "Unlock prompt failed: $errString")
                                                    }
                                                }
                                            }
                                        )
                                        val promptInfo = BiometricPrompt.PromptInfo.Builder()
                                            .setTitle(act.getString(R.string.settings_seed_auth_required))
                                            .setAllowedAuthenticators(
                                                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                                                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
                                            )
                                            .build()
                                        prompt.authenticate(promptInfo)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().height(48.dp)
                            ) {
                                Text(stringResource(R.string.settings_show_seed))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Danger zone
                    Text(stringResource(R.string.settings_danger_zone), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { heading() })
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
                            Spacer(modifier = Modifier.height(8.dp))
                            // Destroy local trade data — KEEPS identity + seed.
                            // Distinct from reset: funds stay safe on-chain,
                            // the app just forgets every trade/chat/offer.
                            OutlinedButton(
                                onClick = { showDestroyDialog = true },
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Text(stringResource(R.string.settings_destroy_data))
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

                if (showDestroyDialog) {
                    AlertDialog(
                        onDismissRequest = { showDestroyDialog = false },
                        title = { Text(stringResource(R.string.settings_destroy_title)) },
                        text = {
                            Column {
                                Text(
                                    text = stringResource(R.string.settings_destroy_body),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Spacer(Modifier.height(12.dp))
                                OutlinedTextField(
                                    value = destroyConfirmText,
                                    onValueChange = { destroyConfirmText = it },
                                    label = { Text(stringResource(R.string.settings_destroy_type_confirm)) },
                                    singleLine = true
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    showDestroyDialog = false
                                    destroyConfirmText = ""
                                    viewModel.destroyLocalData()
                                },
                                enabled = destroyConfirmText == "HAPUS"
                            ) {
                                Text(
                                    stringResource(R.string.settings_destroy_confirm),
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDestroyDialog = false }) {
                                Text(stringResource(R.string.general_cancel))
                            }
                        }
                    )
                }

                if (showNoAuthDialog) {
                    AlertDialog(
                        onDismissRequest = { showNoAuthDialog = false },
                        title = { Text(stringResource(R.string.settings_seed_no_auth_title)) },
                        text = { Text(stringResource(R.string.settings_seed_no_auth_message)) },
                        confirmButton = {
                            Button(
                                onClick = {
                                    showNoAuthDialog = false
                                    context.startActivity(
                                        Intent(Settings.ACTION_SECURITY_SETTINGS)
                                    )
                                }
                            ) {
                                Text(stringResource(R.string.settings_seed_set_lock))
                            }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = {
                                    showNoAuthDialog = false
                                    seedWords = viewModel.seedPhrase()
                                    seedVisible = false
                                    showSeedDialog = true
                                }
                            ) {
                                Text(stringResource(R.string.settings_seed_show_anyway))
                            }
                        }
                    )
                }

                if (showExportDialog) {
                    AlertDialog(
                        onDismissRequest = { showExportDialog = false },
                        title = { Text(stringResource(R.string.export_passphrase_title)) },
                        text = {
                            Column {
                                Text(
                                    text = stringResource(R.string.export_passphrase_body),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Spacer(Modifier.height(12.dp))
                                OutlinedTextField(
                                    value = exportPassphrase,
                                    onValueChange = { exportPassphrase = it },
                                    placeholder = { Text(stringResource(R.string.export_passphrase_hint)) },
                                    singleLine = true
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    pendingPassphrase = exportPassphrase.toCharArray()
                                    showExportDialog = false
                                    exportLauncher.launch("neop2p-identity-backup.np2b")
                                },
                                enabled = exportPassphrase.length >= 8
                            ) {
                                Text(stringResource(R.string.export_confirm))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showExportDialog = false }) {
                                Text(stringResource(R.string.general_cancel))
                            }
                        }
                    )
                }

                if (showImportDialog) {
                    AlertDialog(
                        onDismissRequest = { showImportDialog = false },
                        title = { Text(stringResource(R.string.import_passphrase_title)) },
                        text = {
                            Column {
                                Text(
                                    text = stringResource(R.string.import_passphrase_body),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = stringResource(R.string.import_replace_warning),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Spacer(Modifier.height(12.dp))
                                OutlinedTextField(
                                    value = importPassphrase,
                                    onValueChange = { importPassphrase = it },
                                    placeholder = { Text(stringResource(R.string.export_passphrase_hint)) },
                                    singleLine = true
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    val bytes = pendingImportBytes
                                    showImportDialog = false
                                    pendingImportBytes = null
                                    if (bytes != null) {
                                        scope.launch {
                                            val result =
                                                viewModel.importBundle(bytes, importPassphrase.toCharArray())
                                            result.onSuccess {
                                                snackbarHostState.showSnackbar(
                                                    context.getString(R.string.import_success)
                                                )
                                                onIdentityRestored()
                                            }.onFailure { e ->
                                                snackbarHostState.showSnackbar(
                                                    context.getString(R.string.import_failed, e.message ?: "unknown")
                                                )
                                            }
                                        }
                                    }
                                }
                            ) {
                                Text(stringResource(R.string.import_confirm))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showImportDialog = false }) {
                                Text(stringResource(R.string.general_cancel))
                            }
                        }
                    )
                }

                if (showSeedDialog) {
                    AlertDialog(
                        onDismissRequest = { showSeedDialog = false },
                        title = { Text(stringResource(R.string.settings_seed_dialog_title)) },
                        text = {
                            SecureScreen {
                                Column {
                                    Text(
                                        text = stringResource(R.string.settings_seed_dialog_warning),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    Text(
                                        text = if (seedVisible) seedWords.joinToString(" ")
                                        else List(seedWords.size) { "••••" }.joinToString(" "),
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(
                                            onClick = { seedVisible = !seedVisible },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text(
                                                stringResource(
                                                    if (seedVisible) R.string.settings_seed_hide
                                                    else R.string.settings_seed_show
                                                )
                                            )
                                        }
                                        Button(
                                            onClick = {
                                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                                    as ClipboardManager
                                                // C6: shared sensitive-clipboard helper.
                                                clipboard.copySensitive(
                                                    "NEO-P2P recovery phrase",
                                                    seedWords.joinToString(" ")
                                                )
                                                scope.launch {
                                                    snackbarHostState.showSnackbar(
                                                        context.getString(R.string.onb_seed_copied)
                                                    )
                                                }
                                            },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text(stringResource(R.string.settings_seed_copy))
                                        }
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showSeedDialog = false }) {
                                Text(stringResource(R.string.general_close))
                            }
                        }
                    )
                }
            }
        )
    }
}

// ─── ViewModel ───────────────────────────────────────────────
/**
 * Transport-node port validation: 1..65535, integer only.
 * Kept pure so the field-level UX and the store both share one rule.
 */
internal fun validTransportPort(s: String): Boolean {
    val p = s.trim().toIntOrNull() ?: return false
    return p in 1..65535
}

/** Result of the manual Settings update check. */
sealed interface UpdateCheckResult {
    data object UpToDate : UpdateCheckResult
    data class Available(val tag: String, val url: String) : UpdateCheckResult
    data object Error : UpdateCheckResult
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val identityManager: IdentityManager,
    private val blockedPeerStore: com.neop2p.data.local.BlockedPeerStore,
    private val reportedPeerStore: com.neop2p.data.local.ReportedPeerStore,
    private val savedPaymentMethods: com.neop2p.data.local.SavedPaymentMethodsStore,
    private val localeStore: com.neop2p.data.local.LocaleStore,
    private val offerDao: com.neop2p.data.local.dao.OfferDao,
    private val escrowDao: com.neop2p.data.local.dao.EscrowDao,
    private val chatMessageDao: com.neop2p.data.local.dao.ChatMessageDao,
    private val attestationDao: com.neop2p.data.local.dao.AttestationDao,
    private val peerDao: com.neop2p.data.local.dao.PeerDao,
    private val conversationKeyDao: com.neop2p.data.local.dao.ConversationKeyDao,
    private val deletedOfferStore: com.neop2p.data.local.DeletedOfferStore,
    private val transportNodeStore: com.neop2p.data.local.TransportNodeStore,
    private val peerBindingStore: com.neop2p.data.local.PeerBindingStore,
    private val rnsTransport: RnsTransport,
    private val orchestrator: P2POrchestrator,
    private val reputationSystem: com.neop2p.data.reputation.ReputationSystem,
    private val pendingDisputeStore: com.neop2p.data.local.PendingDisputeStore,
    private val pendingArbitrationStore: com.neop2p.data.local.PendingArbitrationStore,
    private val walletSnapshotStore: com.neop2p.data.wallet.WalletSnapshotStore,
    private val walletAddressStateStore: com.neop2p.data.wallet.WalletAddressStateStore,
    private val walletService: WalletService,
    private val updateChecker: com.neop2p.data.update.UpdateChecker,
    private val sweepThrottleStore: com.neop2p.data.local.SweepThrottleStore,
    private val torManager: com.neop2p.data.tor.TorManager,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsState())
    val uiState: StateFlow<SettingsState> = _uiState.asStateFlow()

    /** Live embedded-Tor daemon state (drives the Settings toggle + status). */
    val torState: StateFlow<com.neop2p.data.network.TorState> = torManager.state

    data class SettingsState(
        // Locally blocked peers (their offers are hidden from the market feed).
        val blockedPeers: List<String> = emptyList(),
        // Local-only reported peers (F18): persistent trace, never sent anywhere.
        val reportedPeers: List<com.neop2p.data.local.ReportedPeerStore.Report> = emptyList(),
        // Saved payment methods (bank/QRIS/e-wallet) reused across offers.
        val savedMethods: Map<String, com.neop2p.domain.model.PaymentDetails> = emptyMap(),
        // Per-app language override: "system" / "id" / "en".
        val locale: String = "system",
        // Extra RNS transport nodes (Tier 3), beyond the built-in default.
        val transportNodes: List<com.neop2p.data.local.TransportNode> = emptyList(),
        // Verified public RNS transport-node presets (opt-in one-tap Add).
        val communityPresets: List<com.neop2p.data.local.TransportNode> = emptyList(),
        // Live RNS transport state — mirrors the Home transport-down banner.
        val transportReady: Boolean = false,
        // Manual update check (Settings → About).
        val updateChecking: Boolean = false,
        val updateResult: UpdateCheckResult? = null,
    )

    init {
        _uiState.update { it.copy(blockedPeers = blockedPeerStore.blockedPeerIds()) }
        _uiState.update { it.copy(savedMethods = savedPaymentMethods.all()) }
        _uiState.update { it.copy(locale = localeStore.locale()) }
        _uiState.update { it.copy(reportedPeers = reportedPeerStore.reports()) }
        _uiState.update { it.copy(transportNodes = transportNodeStore.all()) }
        _uiState.update { it.copy(communityPresets = com.neop2p.data.local.TransportNodeStore.communityPresets()) }
        // Live transport state (same source as Home's transport-down banner).
        // StateFlow emits its current value immediately on collect, so the
        // label reflects reality within one frame of opening Settings.
        viewModelScope.launch {
            orchestrator.transportReady.collect { ready ->
                _uiState.update { it.copy(transportReady = ready) }
            }
        }
    }

    fun setLocale(code: String) {
        localeStore.setLocale(code)
        _uiState.update { it.copy(locale = code) }
    }

    fun setTorEnabled(enabled: Boolean) = torManager.setEnabled(enabled)

    fun retryTor() = torManager.retry()

    /**
     * Destroy ALL local trade data (offers, escrows, chat, attestations,
     * peers, conversation keys, saved methods, block/delete stores) while
     * KEEPING the identity + seed — funds stay safe on-chain, the app just
     * forgets every trade. Distinct from identity reset (which wipes the
     * seed too). The UI requires type-to-confirm before calling this.
     */
    fun destroyLocalData() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { offerDao.clear() }
            runCatching { escrowDao.clear() }
            runCatching { chatMessageDao.clear() }
            runCatching { attestationDao.clear() }
            runCatching { peerDao.clear() }
            runCatching { conversationKeyDao.clear() }
            runCatching { deletedOfferStore.clear() }
            runCatching { blockedPeerStore.clear() }
            runCatching { reportedPeerStore.clear() }
            savedPaymentMethods.clear()
            peerBindingStore.clear()
            reputationSystem.resetLocalReputations()
            // B2 (2026-09-23): the identity-scoped stores/queues a wipe must
            // not leave behind — a surviving retry queue or wallet snapshot
            // can leak or act on the pre-wipe trade data.
            runCatching { pendingDisputeStore.clear() }
            runCatching { pendingArbitrationStore.clear() }
            runCatching { walletSnapshotStore.clear() }
            runCatching { walletAddressStateStore.clear() }
            runCatching { sweepThrottleStore.clear() }
            runCatching { transportNodeStore.clear() }
            runCatching {
                appContext.getSharedPreferences("receipt_drafts", Context.MODE_PRIVATE).edit().clear().apply()
            }
            runCatching {
                appContext.getSharedPreferences("neop2p_notified_events", Context.MODE_PRIVATE).edit().clear().apply()
            }
            runCatching {
                appContext.getSharedPreferences("escrow_rated", Context.MODE_PRIVATE).edit().clear().apply()
            }
            _uiState.update {
                it.copy(
                    savedMethods = emptyMap(),
                    blockedPeers = emptyList(),
                    reportedPeers = emptyList(),
                    transportNodes = emptyList()
                )
            }
        }
    }

    fun removeSavedMethod(methodId: String) {
        savedPaymentMethods.remove(methodId)
        _uiState.update { it.copy(savedMethods = savedPaymentMethods.all()) }
    }

    fun unblockPeer(peerId: String) {
        blockedPeerStore.unblock(peerId)
        _uiState.update { it.copy(blockedPeers = blockedPeerStore.blockedPeerIds()) }
    }

    fun removeReport(peerId: String) {
        reportedPeerStore.remove(peerId)
        _uiState.update { it.copy(reportedPeers = reportedPeerStore.reports()) }
    }

    // ─── Transport nodes (Tier 3) ───────────────────────────────

    /**
     * Add an extra RNS transport node (host:port). Persists first, then
     * live-applies — the new interface comes up without a network restart.
     * Invalid input (blank host / bad port) is rejected before persisting.
     */
    fun addTransportNode(host: String, port: String) {
        val trimmed = host.trim()
        if (trimmed.isBlank()) return
        val p = port.trim().toIntOrNull()
        if (p == null || p !in 1..65535) return
        if (!transportNodeStore.add(trimmed, p)) return
        refreshTransportNodes()
        viewModelScope.launch { rnsTransport.applyTransportNodes() }
    }

    fun removeTransportNode(host: String, port: Int) {
        transportNodeStore.remove(host, port)
        refreshTransportNodes()
        viewModelScope.launch { rnsTransport.applyTransportNodes() }
    }

    private fun refreshTransportNodes() {
        _uiState.update { it.copy(transportNodes = transportNodeStore.all()) }
    }

    // ─── Update check (manual) ──────────────────────────────────

    /**
     * Manual release check: fetches the latest GitHub release and reports the
     * result inline. Report-only — it never posts a notification and never
     * records a last-notified tag (that is the background worker's job).
     */
    fun checkForUpdates() {
        if (_uiState.value.updateChecking) return
        _uiState.update { it.copy(updateChecking = true, updateResult = null) }
        viewModelScope.launch {
            val latest = updateChecker.fetchLatest()
            val result = when {
                latest == null -> UpdateCheckResult.Error
                com.neop2p.data.update.UpdatePolicy.isNewer(latest.tag, BuildConfig.VERSION_NAME) ->
                    UpdateCheckResult.Available(latest.tag, latest.htmlUrl)
                else -> UpdateCheckResult.UpToDate
            }
            _uiState.update { it.copy(updateChecking = false, updateResult = result) }
        }
    }

    fun resetIdentity() {
        viewModelScope.launch(Dispatchers.IO) {
            // Stop the P2P pipeline FIRST: the running RnsSession holds the
            // OLD identity (seed-derived). Wiping the seed under a live
            // session keeps announcing a ghost peerId forever, and the fresh
            // identity never connects (RnsTransport.start() early-returns
            // while session != null). stop() cancels the sweep too, so the
            // self-heal cannot resurrect the old session mid-reset. After
            // onboarding, Home's LaunchedEffect start() boots the new identity.
            orchestrator.stop()
            identityManager.resetIdentity()
            // App will restart to Onboarding (NavGraph onIdentityReset)
        }
    }

    /** The current identity's BIP-39 recovery phrase (auth-gated in the UI). */
    fun seedPhrase(): List<String> = identityManager.getOrCreateIdentity().seedPhrase

    /**
     * Build a passphrase-encrypted, versioned identity + trade-state bundle
     * (Phase 3, C5). Runs on IO: reads the identity, wallet HD pointers, and
     * every local escrow/offer row, then PBKDF2+AES-GCM encrypts the JSON.
     */
    suspend fun exportBundle(passphrase: CharArray): ByteArray = withContext(Dispatchers.IO) {
        val identity = identityManager.getOrCreateIdentity()
        val pointers = walletAddressStateStore.load(identity.peerId)
        val bundle = com.neop2p.data.portability.IdentityBundle(
            peerId = identity.peerId,
            mnemonic = identity.seedPhrase,
            nickname = identity.nickname,
            lnNodeId = identity.lnNodeId,
            walletExternalPointer = pointers.nextExternal,
            walletChangePointer = pointers.nextChange,
            escrows = escrowDao.getAllEscrowsSync().map { it.toBundle() },
            offers = offerDao.getAllOffersSync().map { it.toBundle() }
        )
        val json = com.neop2p.data.portability.BundleCodec.encode(bundle)
        com.neop2p.data.portability.BundleCrypto.encrypt(json.toByteArray(Charsets.UTF_8), passphrase)
    }

    /**
     * Decrypt + apply an imported bundle (Phase 3, C5): restores the identity
     * (force-overwriting), restores the wallet HD pointers, then inserts every
     * carried escrow/offer row (IGNORE — an existing local row wins). Fails
     * closed on a wrong passphrase or a foreign/corrupt bundle.
     */
    suspend fun importBundle(bytes: ByteArray, passphrase: CharArray): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val plaintext = com.neop2p.data.portability.BundleCrypto.decrypt(bytes, passphrase)
                val bundle = com.neop2p.data.portability.BundleCodec.decode(plaintext.toString(Charsets.UTF_8))
                val identity = identityManager.restoreFromSeedPhrase(
                    bundle.mnemonic,
                    force = true,
                    nickname = bundle.nickname
                )
                walletAddressStateStore.save(
                    com.neop2p.data.wallet.HdPointers(
                        nextExternal = bundle.walletExternalPointer,
                        nextChange = bundle.walletChangePointer
                    ),
                    identity.peerId
                )
                bundle.escrows.forEach { escrowDao.insertEscrowIgnore(it.toEntity()) }
                bundle.offers.forEach { offerDao.insertOfferIgnore(it.toEntity()) }
                // Re-scan the wallet from index 0 with the restored pointers.
                runCatching { walletService.loadState() }
                Unit
            }
        }
}

/** Maps the pure [com.neop2p.data.tor.TorStatusLabel] key to its string resource. */
private fun labelKeyToRes(key: String): Int = when (key) {
    "tor_status_starting" -> R.string.tor_status_starting
    "tor_status_bootstrapping" -> R.string.tor_status_bootstrapping
    "tor_status_connected" -> R.string.tor_status_connected
    else -> R.string.tor_status_failed
}
