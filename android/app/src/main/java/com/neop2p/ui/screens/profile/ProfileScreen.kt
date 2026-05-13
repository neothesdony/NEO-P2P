package com.neop2p.ui.screens.profile

import android.os.Handler
import android.os.Looper
import androidx.activity.compose.*
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neop2p.NeoP2PConfig
import com.neop2p.R
import com.neop2p.data.p2p.IdentityManager
import com.neop2p.domain.model.*
import com.neop2p.ui.theme.NeoP2PTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.lifecycle.HiltViewModelFactory
import kotlinx.coroutines.Cancelled
import kotlinx.coroutines.channels.awaitClose
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
    val viewModel: ProfileViewModel = hiltViewModel()
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
                    when (state) {
                        is ProfileViewModel.Loading -> LoadingScreen()
                        is ProfileViewModel.Error -> ErrorScreen(
                            message = (it as ProfileViewModel.Error).message,
                            onRetry = { viewModel.refresh() }
                        )
                        is ProfileViewModel.Success -> {
                            val data = (it as ProfileViewModel.Success).data
                            ProfileContent(
                                identity = data.identity,
                                reputation = data.reputation,
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
        )
        .align(Alignment.Center)
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
) = Column(
    modifier = modifier
        .fillMaxSize()
        .padding(24.dp)
        .align(Alignment.Center)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileContent(
    identity: IdentityManager.LocalIdentity,
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
                Circle(
                    modifier = Modifier
                        .size(80.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primary,
                            shape = CircleShape
                        ),
                    color = MaterialTheme.colorScheme.onPrimary
                ) {
                    Text(
                        text = (identity.nickname.ifBlank { "?" }).take(1).uppercase(),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onPrimary
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
class ProfileViewModel @Inject constructor(
    private val identityManager: IdentityManager,
    private val reputationSystem: com.neop2p.data.reputation.ReputationSystem
) : HiltViewModel() {

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    sealed class UiState {
        object Loading : UiState()
        data class Error(val message: String) : UiState()
        data class Success(val data: ProfileData) : UiState()
    }

    data class ProfileData(
        val identity: IdentityManager.LocalIdentity,
        val reputation: ReputationProfile
    )

    init {
        loadProfile()
    }

    private fun loadProfile() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val identity = identityManager.getOrCreateIdentity()
                val reputation = reputationSystem.getMyReputation()
                    .getOrDefault(ReputationProfile(
                        peerId = identity.peerId,
                        score = 1.0f,
                        totalTrades = 0,
                        completedTrades = 0,
                        disputedTrades = 0
                    ))

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
