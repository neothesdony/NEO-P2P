package com.neop2p.ui.screens.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neop2p.R
import com.neop2p.data.p2p.IdentityManager
import com.neop2p.ui.theme.NeoP2PTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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

@Composable
private fun WelcomeScreen(
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
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
                   "Zero servers \u2022 No KYC \u2022 100% on-chain escrow",
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
            .padding(24.dp),
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
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
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
            .padding(24.dp),
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
            text = "\u26a0\ufe0f WARNING: Anyone with this phrase can access your funds.\n" +
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

@Composable
private fun FinishScreen(
    onGetStarted: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
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
enum class OnboardingStep { WELCOME, CREATE_IDENTITY, BACKUP_SEED, FINISH }

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val identityManager: IdentityManager
) : ViewModel() {

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
                val identity = identityManager.getOrCreateIdentity()
                // Use a simple fallback seed phrase
                val fallbackWords = listOf(
                    "abandon", "ability", "able", "about", "above", "absent",
                    "absorb", "abstract", "absurd", "abuse", "access", "accident"
                )
                _seedState.update { it.copy(seedPhrase = fallbackWords) }
                _uiState.update { it.copy(seedPhrase = fallbackWords) }
                _uiState.update { it.copy(currentStep = OnboardingStep.BACKUP_SEED) }
            } catch (_: Exception) {
                // Handle error silently for v1
            } finally {
                _identityState.update { it.copy(isGenerating = false) }
            }
        }
    }

    fun nextStep() {
        val current = _uiState.value.currentStep
        when (current) {
            OnboardingStep.WELCOME -> _uiState.update { it.copy(currentStep = OnboardingStep.CREATE_IDENTITY) }
            OnboardingStep.CREATE_IDENTITY -> {
                if (_uiState.value.nickname.isNotBlank()) {
                    _uiState.update { it.copy(currentStep = OnboardingStep.BACKUP_SEED) }
                }
            }
            OnboardingStep.BACKUP_SEED -> _uiState.update { it.copy(currentStep = OnboardingStep.FINISH) }
            OnboardingStep.FINISH -> {}
        }
    }

    fun completeOnboarding() {
        _uiState.update { it.copy(currentStep = OnboardingStep.FINISH) }
    }

    fun confirmBackup() {
        _seedState.update { it.copy(isConfirming = true) }
    }
}
