package com.neop2p.ui.screens.profile

import androidx.activity.compose.*
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
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
    onEditNickname: () -> Unit,
    onViewAttestations: () -> Unit,
    modifier: Modifier = Modifier
) {
    val viewModel: ProfileViewModel = viewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    NeoP2PTheme {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("Profile") },
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
                                onViewAttestations = onViewAttestations,
                                onEditNickname = onEditNickname
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
        ),
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
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentSize(align = Alignment.Center)
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
    Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
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
                    text = identity.nickname.ifBlank { "Anonymous" },
                    style = MaterialTheme.typography.titleLarge
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "#${identity.peerId.take(8)}",
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
            Text("Reputation", style = MaterialTheme.typography.titleMedium)

            Row(
                horizontalArrangement = Arrangement.SpaceAround,
                modifier = Modifier.fillMaxWidth()
            ) {
                StatCard(
                    label = "Score",
                    value = "${(reputation.score * 100).toInt()}%"
                )
                StatCard(
                    label = "Trades",
                    value = "${reputation.totalTrades}"
                )
                StatCard(
                    label = "Completed",
                    value = "${reputation.completedTrades}"
                )
                StatCard(
                    label = "Disputes",
                    value = "${reputation.disputedTrades}"
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Cryptographic identity
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Public Key", style = MaterialTheme.typography.titleMedium)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Nostr: ${identity.nostrPubkeyHex.take(20)}...",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1
                    )
                    Text(
                        text = "LN: ${identity.lnNodeId.take(20)}...",
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
            Text("Edit Nickname")
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = onViewAttestations,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Text("View Attestations")
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
}

// ─── Data Classes ─────────────────────────────────────────────
data class ReputationProfile(
    val peerId: String,
    val score: Float,
    val totalTrades: Int,
    val completedTrades: Int,
    val disputedTrades: Int
)
