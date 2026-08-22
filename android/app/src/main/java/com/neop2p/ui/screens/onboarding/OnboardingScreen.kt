package com.neop2p.ui.screens.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.res.stringResource
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
                    color = MaterialTheme.colorScheme.background
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
                    onBackupComplete = { viewModel.nextStep() }
                )
                OnboardingStep.VERIFY_SEED -> VerifySeedScreen(
                    viewModel = viewModel,
                    onVerified = { viewModel.completeOnboarding() }
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
            contentDescription = stringResource(R.string.onb_cd_logo),
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
        )

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = stringResource(R.string.onb_welcome_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.onb_welcome_subtitle),
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
            Text(stringResource(R.string.onb_get_started))
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
            text = stringResource(R.string.onb_create_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.onb_create_desc),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
        )

        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = identityState.nickname,
            onValueChange = { viewModel.updateNickname(it) },
            label = { Text(stringResource(R.string.onb_nickname_label)) },
            placeholder = { Text(stringResource(R.string.onb_nickname_placeholder)) },
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
                Text(stringResource(R.string.onb_creating))
            } else {
                Text(stringResource(R.string.onb_generate_identity))
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
            text = stringResource(R.string.onb_backup_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.onb_backup_desc),
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
            text = stringResource(R.string.onb_backup_warning_full),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )

        Spacer(modifier = Modifier.height(32.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Button(
                onClick = { /* TODO: Show seed phrase again */ },
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
            ) {
                Text(stringResource(R.string.onb_show_again))
            }

            Button(
                onClick = {
                    viewModel.confirmBackup()
                    onBackupComplete()
                },
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
            ) {
                Text(stringResource(R.string.onb_saved))
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
private fun VerifySeedScreen(
    viewModel: OnboardingViewModel,
    onVerified: () -> Unit,
    modifier: Modifier = Modifier
) {
    val verifyState by viewModel.verifyState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.onb_verify_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.onb_verify_desc),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
        )

        Spacer(modifier = Modifier.height(32.dp))

        verifyState.challengeIndices.forEach { index ->
            OutlinedTextField(
                value = verifyState.entries[index].orEmpty(),
                onValueChange = { viewModel.updateVerifyEntry(index, it) },
                label = { Text(stringResource(R.string.onb_word_n, index + 1)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                )
            )

            Spacer(modifier = Modifier.height(16.dp))
        }

        verifyState.error?.let { errorMsg ->
            Text(
                text = errorMsg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        AnimatedVisibility(
            visible = verifyState.isVerifying,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(32.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        Button(
            onClick = { viewModel.verifySeed() },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            enabled = !verifyState.isVerifying
        ) {
            Text(stringResource(R.string.onb_verify_continue))
        }

        Spacer(modifier = Modifier.height(12.dp))

        TextButton(
            onClick = { viewModel.nextStep() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.general_back))
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
            contentDescription = stringResource(R.string.onb_cd_success),
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.onb_finish_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.onb_finish_desc),
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
            Text(stringResource(R.string.onb_start_trading))
        }
    }
}

// ─── ViewModel ───────────────────────────────────────────────
enum class OnboardingStep { WELCOME, CREATE_IDENTITY, BACKUP_SEED, VERIFY_SEED, FINISH }

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

    // Verification challenge: indices of the 3 words the user must re-enter
    private val _verifyState = MutableStateFlow(VerifyState())
    val verifyState: StateFlow<VerifyState> = _verifyState.asStateFlow()

    data class VerifyState(
        val challengeIndices: List<Int> = emptyList(),
        val entries: MutableMap<Int, String> = mutableMapOf(),
        val error: String? = null,
        val isVerifying: Boolean = false
    )

    fun updateNickname(nickname: String) {
        _identityState.update { it.copy(nickname = nickname) }
        _uiState.update { it.copy(nickname = nickname) }
    }

    fun generateIdentity() {
        _identityState.update { it.copy(isGenerating = true) }
        viewModelScope.launch {
            try {
                // Apply the nickname the user entered during onboarding (if any)
                val rawNickname = _uiState.value.nickname.trim()
                val identity = if (rawNickname.isNotBlank()) {
                    identityManager.getOrCreateIdentity().let {
                        identityManager.updateNickname(rawNickname)
                    }
                } else {
                    identityManager.getOrCreateIdentity()
                }
                // Use the actual BIP-39 seed phrase derived from the identity
                _seedState.update { it.copy(seedPhrase = identity.seedPhrase) }
                _uiState.update { it.copy(seedPhrase = identity.seedPhrase) }
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
            // User tapped "I've Saved It" on the backup screen → go to verification
            OnboardingStep.BACKUP_SEED -> startVerification()
            // User tapped "Back" on the verification screen → show the phrase again
            OnboardingStep.VERIFY_SEED -> _uiState.update {
                it.copy(currentStep = OnboardingStep.BACKUP_SEED, isConfirming = false)
            }
            OnboardingStep.FINISH -> {}
        }
    }

    /**
     * Builds a 3-word verification challenge from random positions in the seed phrase.
     */
    private fun startVerification() {
        val phrase = _uiState.value.seedPhrase
        if (phrase.isEmpty()) {
            _uiState.update { it.copy(currentStep = OnboardingStep.FINISH) }
            return
        }
        val challengeIndices = phrase.indices.shuffled().take(3).sorted()
        _verifyState.value = VerifyState(challengeIndices = challengeIndices)
        _uiState.update { it.copy(currentStep = OnboardingStep.VERIFY_SEED) }
    }

    fun updateVerifyEntry(index: Int, value: String) {
        _verifyState.update { it.copy(entries = it.entries.toMutableMap().apply { put(index, value) }, error = null) }
    }

    /**
     * Validates the entered words against the real seed phrase.
     */
    fun verifySeed() {
        val state = _verifyState.value
        val phrase = _uiState.value.seedPhrase
        _verifyState.update { it.copy(isVerifying = true) }
        viewModelScope.launch {
            val allFilled = state.challengeIndices.all { state.entries[it].orEmpty().isNotBlank() }
            val matches = state.challengeIndices.all { state.entries[it]?.trim()?.equals(phrase[it], ignoreCase = true) == true }
            if (allFilled && matches) {
                completeOnboarding()
            } else {
                val msg = if (!allFilled) {
                    "Please fill in all requested words."
                } else {
                    "One or more words don't match. Please check your saved phrase."
                }
                _verifyState.update {
                    it.copy(isVerifying = false, error = msg)
                }
            }
        }
    }

    fun completeOnboarding() {
        _uiState.update { it.copy(currentStep = OnboardingStep.FINISH) }
    }

    fun confirmBackup() {
        _seedState.update { it.copy(isConfirming = true) }
    }
}
