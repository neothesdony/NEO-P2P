package com.neop2p.ui.screens.profile

import androidx.activity.compose.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
    modifier: Modifier = Modifier
) {
    val viewModel: ProfileViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showEditDialog by remember { mutableStateOf(false) }
    var nicknameInput by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

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
                            message = stateVal.message,
                            onRetry = { viewModel.refresh() }
                        )
                        is ProfileViewModel.UiState.Success -> {
                            ProfileContent(
                                identity = stateVal.data.identity,
                                reputation = stateVal.data.reputation,
                                onViewAttestations = {
                                    scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.profile_no_attestations)) }
                                },
                                onEditNickname = {
                                    nicknameInput = stateVal.data.identity.nickname
                                    showEditDialog = true
                                }
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

                Text(
                    text = stringResource(R.string.profile_public_id, identity.peerId.take(8)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
                    Text(
                        text = stringResource(R.string.profile_nostr_key, identity.nostrPubkeyHex.take(20)),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1
                    )
                    Text(
                        text = stringResource(R.string.profile_ln_key, identity.lnNodeId.take(20)),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1
                    )
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

// ─── ViewModel ───────────────────────────────────────────────
@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val identityManager: IdentityManager,
    private val reputationSystem: com.neop2p.data.reputation.ReputationSystem
) : ViewModel() {

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    sealed class UiState {
        object Loading : UiState()
        data class Error(val message: String) : UiState()
        data class Success(val data: ProfileData) : UiState()
    }

    data class ProfileData(
        val identity: IdentityManager.Identity,
        val reputation: ReputationProfile
    )

    init {
        loadProfile()
    }

    private fun loadProfile() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val identity = identityManager.getOrCreateIdentity()
                val reputation = reputationSystem.getMyReputation(identity.peerId)

                _uiState.value = UiState.Success(ProfileData(identity, reputation))
            } catch (e: Exception) {
                _uiState.value = UiState.Error("Failed to load profile: ${e.message}")
            }
        }
    }

    fun refresh() {
        _uiState.value = UiState.Loading
        loadProfile()
    }

    fun updateNickname(nickname: String) {
        viewModelScope.launch(Dispatchers.IO) {
            identityManager.updateNickname(nickname)
            loadProfile()
        }
    }
}
