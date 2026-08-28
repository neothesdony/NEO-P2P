package com.neop2p.ui.screens.profile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import androidx.activity.compose.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.hilt.navigation.compose.hiltViewModel
import com.neop2p.data.local.dao.AttestationDao
import com.neop2p.data.local.entity.AttestationEntity
import com.neop2p.data.reputation.ReputationProfile
import com.neop2p.ui.theme.NeoP2PTheme
import com.neop2p.R
import com.neop2p.data.p2p.IdentityManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onBack: () -> Unit,
    onSettingsClick: () -> Unit = {},
    onInviteClick: () -> Unit = {},
    onTabChange: (com.neop2p.ui.components.AppTab) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val viewModel: ProfileViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showEditDialog by remember { mutableStateOf(false) }
    var nicknameInput by remember { mutableStateOf("") }
    var showAttestations by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ProfileViewModel.UiEvent.NicknameSaveFailed ->
                    snackbarHostState.showSnackbar(context.getString(R.string.profile_save_failed))
            }
        }
    }

    fun copyToClipboard(label: String, text: String) {
        val clip = ClipData.newPlainText(label, text)
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(clip)
    }

    NeoP2PTheme {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(stringResource(R.string.home_cd_profile)) },
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
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    when (val stateVal = state) {
                        is ProfileViewModel.UiState.Loading -> LoadingScreen()
                        is ProfileViewModel.UiState.Error -> ErrorScreen(
                            message = stateVal.messageRes?.let { context.getString(it) } ?: stateVal.message,
                            onRetry = { viewModel.refresh() }
                        )
                        is ProfileViewModel.UiState.Success -> {
                            ProfileContent(
                                identity = stateVal.data.identity,
                                reputation = stateVal.data.reputation,
                                onViewAttestations = { showAttestations = true },
                                onEditNickname = {
                                    nicknameInput = stateVal.data.identity.nickname
                                    showEditDialog = true
                                },
                                onCopyPeerId = {
                                    copyToClipboard("NEO-P2P peer ID", stateVal.data.identity.peerId)
                                    scope.launch {
                                        snackbarHostState.showSnackbar(context.getString(R.string.profile_peer_id_copied))
                                    }
                                },
                                onCopyPubkey = {
                                    copyToClipboard("NEO-P2P Nostr pubkey", stateVal.data.identity.nostrPubkeyHex)
                                    scope.launch {
                                        snackbarHostState.showSnackbar(context.getString(R.string.profile_pubkey_copied))
                                    }
                                },
                                onSettingsClick = onSettingsClick,
                                onInviteClick = onInviteClick
                            )
                        }
                    }
                }
            }
        )

        if (showEditDialog) {
            AlertDialog(
                onDismissRequest = { showEditDialog = false },
                title = { Text(stringResource(R.string.profile_edit_title)) },
                text = {
                    OutlinedTextField(
                        value = nicknameInput,
                        onValueChange = { nicknameInput = it },
                        label = { Text(stringResource(R.string.profile_nickname_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = nicknameInput.isNotBlank(),
                        onClick = {
                            viewModel.updateNickname(nicknameInput)
                            showEditDialog = false
                        }
                    ) { Text(stringResource(R.string.general_save)) }
                },
                dismissButton = {
                    TextButton(onClick = { showEditDialog = false }) { Text(stringResource(R.string.general_cancel)) }
                }
            )
        }

        val successData = (state as? ProfileViewModel.UiState.Success)?.data
        if (showAttestations && successData != null) {
            AttestationsDialog(
                myPeerId = successData.identity.peerId,
                attestations = successData.attestations,
                onDismiss = { showAttestations = false }
            )
        }
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
private fun ProfileContent(
    identity: IdentityManager.Identity,
    reputation: ReputationProfile,
    onViewAttestations: () -> Unit,
    onEditNickname: () -> Unit,
    onCopyPeerId: () -> Unit,
    onCopyPubkey: () -> Unit,
    onSettingsClick: () -> Unit = {},
    onInviteClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(modifier = Modifier.fillMaxWidth().padding(24.dp).verticalScroll(rememberScrollState())) {
        // Avatar section
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.CenterHorizontally)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Large avatar
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(
                            color = MaterialTheme.colorScheme.primary,
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = (identity.nickname.ifBlank { "?" }).take(1).uppercase(),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = identity.nickname.ifBlank { stringResource(R.string.general_anonymous) },
                    style = MaterialTheme.typography.titleLarge
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Peer ID with copy — full ID, small font so it wraps cleanly.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.profile_public_id, identity.peerId),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    IconButton(
                        onClick = onCopyPeerId,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ContentCopy,
                            contentDescription = stringResource(R.string.profile_copy_peer_id),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Reputation
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.profile_reputation), style = MaterialTheme.typography.titleMedium)

            Row(
                horizontalArrangement = Arrangement.SpaceAround,
                modifier = Modifier.fillMaxWidth()
            ) {
                StatCard(
                    label = stringResource(R.string.profile_score),
                    value = "${(reputation.score * 100).toInt()}%"
                )
                StatCard(
                    label = stringResource(R.string.profile_trades),
                    value = "${reputation.totalTrades}"
                )
                StatCard(
                    label = stringResource(R.string.profile_completed),
                    value = "${reputation.completedTrades}"
                )
                StatCard(
                    label = stringResource(R.string.profile_disputes),
                    value = "${reputation.disputedTrades}"
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Cryptographic identity
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.profile_public_key), style = MaterialTheme.typography.titleMedium)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = stringResource(R.string.profile_nostr_key, identity.nostrPubkeyHex.take(20)),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = onCopyPubkey,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ContentCopy,
                                contentDescription = stringResource(R.string.profile_copy_pubkey),
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (identity.lnNodeId.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.profile_ln_key, identity.lnNodeId.take(20)),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Action buttons
        OutlinedButton(
            onClick = onEditNickname,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Text(stringResource(R.string.profile_edit_title))
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = onViewAttestations,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Text(stringResource(R.string.profile_view_attestations))
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Invite a peer — QR / paste / scan entry point.
        OutlinedButton(
            onClick = onInviteClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Text(stringResource(R.string.invite_entry))
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Settings — folded into Profile per the bottom-nav cleanup; the
        // top bar no longer carries a settings gear.
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onSettingsClick),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.profile_open_settings),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) = Column(
    modifier = modifier,
    horizontalAlignment = Alignment.CenterHorizontally
) {
    Text(
        text = value,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground
    )
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * Real attestation viewer. Shows signed attestations (kind:33335) received from
 * the relay, split into "about me" (others rating me) and "by me" (my ratings
 * of others). Empty state shows a plain message instead of a dead stub.
 */
@Composable
private fun AttestationsDialog(
    myPeerId: String,
    attestations: List<AttestationEntity>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val aboutMe = attestations.filter { it.target_peer_id == myPeerId }
    val byMe = attestations.filter { it.from_peer_id == myPeerId }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profile_attestations_title)) },
        text = {
            if (attestations.isEmpty()) {
                Text(stringResource(R.string.profile_no_attestations))
            } else {
                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (aboutMe.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.profile_attestations_about_me),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        aboutMe.forEach { AttestationRow(it, context) }
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    if (byMe.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.profile_attestations_by_me),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        byMe.forEach { AttestationRow(it, context) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.general_cancel)) }
        }
    )
}

@Composable
private fun AttestationRow(attestation: AttestationEntity, context: Context) {
    val isPositive = attestation.outcome == "POSITIVE"
    val dateText = remember(attestation.timestamp) {
        android.text.format.DateFormat.getDateFormat(context)
            .format(java.util.Date(attestation.timestamp))
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(
                    color = if (isPositive) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = if (isPositive) {
                stringResource(R.string.profile_attestation_positive, attestation.volume_sats, dateText)
            } else {
                stringResource(R.string.profile_attestation_negative, attestation.volume_sats, dateText)
            },
            style = MaterialTheme.typography.bodySmall
        )
    }
}

// ─── ViewModel ───────────────────────────────────────────────
@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val identityManager: IdentityManager,
    private val reputationSystem: com.neop2p.data.reputation.ReputationSystem,
    private val attestationDao: AttestationDao
) : ViewModel() {

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    sealed class UiState {
        object Loading : UiState()
        data class Error(val message: String, val messageRes: Int? = null) : UiState()
        data class Success(val data: ProfileData) : UiState()
    }

    sealed class UiEvent {
        object NicknameSaveFailed : UiEvent()
    }

    data class ProfileData(
        val identity: IdentityManager.Identity,
        val reputation: ReputationProfile,
        val attestations: List<AttestationEntity>
    )

    init {
        loadProfile()
    }

    private fun loadProfile() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val identity = identityManager.getOrCreateIdentity()
                val reputation = reputationSystem.getMyReputation(identity.peerId)
                val attestations = attestationDao.getAllAttestations().first()

                _uiState.value = UiState.Success(ProfileData(identity, reputation, attestations))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load profile", e)
                _uiState.value = UiState.Error(
                    message = e.message ?: "",
                    messageRes = R.string.profile_load_error
                )
            }
        }
    }

    fun refresh() {
        _uiState.value = UiState.Loading
        loadProfile()
    }

    fun updateNickname(nickname: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                identityManager.updateNickname(nickname)
                loadProfile()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save nickname", e)
                _events.tryEmit(UiEvent.NicknameSaveFailed)
            }
        }
    }

    companion object {
        private const val TAG = "ProfileViewModel"
    }
}
