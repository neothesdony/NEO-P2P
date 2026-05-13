package com.neop2p.ui.screens.onboarding

import android.content.Context
import android.os.Bundle
import android.text.InputType
import androidx.activity.compose.*
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neop2p.NeoP2PConfig
import com.neop2p.R
import com.neop2p.data.p2p.IdentityManager
import com.neop2p.domain.model.*
import com.neop2p.ui.theme.NeoP2PTheme
import com.neop2p.ui.theme.Theme
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.lifecycle.HiltViewModelFactory
import kotlinx.coroutines.flow.collectLatest
import javax.inject.Inject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    onOnboardingComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val viewModel: OnboardingViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    NeoP2PTheme {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(
                    color = if (isSystemInDarkTheme()) Color(0xFF0D1117) else Color.White
                )
        ) {
            when (state.currentStep) {
                OnboardingStep.WELCOME -> WelcomeScreen(
                    onNext = { viewModel.nextStep() }
                )
                OnboardingStep.CREATE_IDENTITY -> CreateIdentityScreen(
                    viewModel = viewModel,
                    onNext = { viewModel.nextStep() }
                )
                OnboardingStep.BACKUP_SEED -> BackupSeedScreen(
                    viewModel = viewModel,
                    onBackupComplete = { viewModel.completeOnboarding() }
                )
                OnboardingStep.FINISH -> FinishScreen(
                    onGetStarted = onOnboardingComplete
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WelcomeScreen(
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
            .align(Alignment.Center),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_crypto_lock),
            contentDescription = "NEO-P2P Logo",
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
        )

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "Welcome to NEO-P2P",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Anonymous P2P crypto trading for Indonesia\n" +
                   "Zero servers • No KYC • 100% on-chain escrow",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
        )

        Spacer(modifier = Modifier.height(48.dp))

        Button(
            onClick = onNext,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            enabled = true
        ) {
            Text("Get Started")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateIdentityScreen(
    viewModel: OnboardingViewModel,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    val identityState by viewModel.identityState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
            .align(Alignment.Center),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Create Your Identity",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Your identity is a cryptographic keypair\n" +
                   "stored securely on your device. No phone, email, or name required.",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
        )

        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = identityState.nickname,
            onValueChange = { viewModel.updateNickname(it) },
            label = { Text("Nickname (optional)") },
            placeholder = { Text("e.g., trader_42") },
            modifier = Modifier.fillMaxWidth(),
            colors = TextFieldDefaults.textFieldColors(
                backgroundColor = Color.Transparent
            )
        )

        Spacer(modifier = Modifier.height(24.dp))

        AnimatedVisibility(
            visible = identityState.isGenerating,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(48.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = { viewModel.generateIdentity() },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            enabled = !identityState.isGenerating && identityState.nickname.length <= 32
        ) {
            if (identityState.isGenerating) {
                Text("Creating...")
            } else {
                Text("Generate Identity")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackupSeedScreen(
    viewModel: OnboardingViewModel,
    onBackupComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val seedState by viewModel.seedState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
            .align(Alignment.Center),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Backup Your Seed Phrase",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "This 12-word phrase is the ONLY way to recover\n" +
                   "your identity and funds. Store it safely offline.",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
        )

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = seedState.seedPhrase.joinToString(" "),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium
                )
                .clickable { /* TODO: Copy to clipboard */ }
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "⚠️ WARNING: Anyone with this phrase can access your funds.\n" +
                   "NEO-P2P will NEVER ask for your seed phrase.",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = 2
        )

        Spacer(modifier = Modifier.height(32.dp))

        Row(
            horizontalArrangement = Arrangement.End,
            modifier = Modifier.fillMaxWidth()
        ) {
            Button(
                onClick = { /* TODO: Show seed phrase again */ },
                modifier = Modifier.width(96.dp)
            ) {
                Text("Show Again")
            }

            Spacer(modifier = Modifier.width(12.dp))

            Button(
                onClick = {
                    viewModel.confirmBackup()
                    onBackupComplete()
                },
                modifier = Modifier
                    .width(96.dp)
                    .height(40.dp)
            ) {
                Text("I've Saved It")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        AnimatedVisibility(
            visible = seedState.isConfirming,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(32.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FinishScreen(
    onGetStarted: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
            .align(Alignment.Center),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_check_circle),
            contentDescription = "Success",
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Identity Created!",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Your NEO-P2P identity is ready.\n" +
                   "You can now browse trades, create offers, and chat securely.",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
        )

        Spacer(modifier = Modifier.height(40.dp))

        Button(
            onClick = onGetStarted,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text("Start Trading")
        }
    }
}

// ─── ViewModel ───────────────────────────────────────────────
class OnboardingViewModel @Inject constructor(
    private val identityManager: IdentityManager
) : HiltViewModel() {

    enum class OnboardingStep { WELCOME, CREATE_IDENTITY, BACKUP_SEED, FINISH }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    data class UiState(
        val currentStep: OnboardingStep = OnboardingStep.WELCOME,
        val nickname: String = "",
        val seedPhrase: List<String> = emptyList(),
        val isGenerating: Boolean = false,
        val isConfirming: Boolean = false
    )

    private val _identityState = MutableStateFlow(IdentityState())
    val identityState: StateFlow<IdentityState> = _identityState.asStateFlow()

    data class IdentityState(
        val nickname: String = "",
        val isGenerating: Boolean = false
    )

    private val _seedState = MutableStateFlow(SeedState())
    val seedState: StateFlow<SeedState> = _seedState.asStateFlow()

    data class SeedState(
        val seedPhrase: List<String> = emptyList(),
        val isConfirming: Boolean = false
    )

    fun updateNickname(nickname: String) {
        _identityState.update { it.copy(nickname = nickname) }
        _uiState.update { it.copy(nickname = nickname) }
    }

    fun generateIdentity() {
        _identityState.update { it.copy(isGenerating = true) }
        viewModelScope.launch {
            try {
                // Generate identity - this will store in KeyStore and return peer info
                val identity = identityManager.getOrCreateIdentity()
                // For v1, we'll generate a simple seed phrase
                val seed = identityManager.generateSeedPhrase()
                _seedState.update { it.copy(seedPhrase = seed) }
                _uiState.update { it.copy(seedPhrase = seed) }
                _uiState.update { it.copy(currentStep = OnboardingStep.BACKUP_SEED) }
            } catch (e: Exception) {
                // Handle error
            } finally {
                _identityState.update { it.copy(isGenerating = false) }
            }
        }
    }

    fun nextStep() = when (_uiState.value.currentStep) {
        OnboardingStep.WELCOME -> _uiState.update { it.copy(currentStep = OnboardingStep.CREATE_IDENTITY) }
        OnboardingStep.CREATE_IDENTITY -> if (_identityState.value.nickname.isNotBlank()) {
            _uiState.update { it.copy(currentStep = OnboardingStep.BACKUP_SEED) }
        }
        OnboardingStep.BACKUP_SEED -> _uiState.update { it.copy(currentStep = OnboardingStep.FINISH) }
        OnboardingStep.FINISH -> {}
    }

    fun completeOnboarding() {
        _uiState.update { it.copy(currentStep = OnboardingStep.FINISH) }
    }
}
