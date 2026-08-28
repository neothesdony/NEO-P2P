package com.neop2p.ui.screens.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import com.neop2p.ui.components.OnboardingStepIndicator
import com.neop2p.ui.util.ErrorCodes
import com.neop2p.ui.theme.NeoP2PTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// Signature ease — snappy overshoot-free motion instead of default expand/shrink.
// cubic-bezier(0.4, 0.0, 0.2, 1): M3 standard emphasis, tuned for short reveal.
private val NeoMotionEase = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    onOnboardingComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val viewModel: OnboardingViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // C4: after a successful seed restore, warn that open trades from the
    // OLD install are NOT on this device (no cloud inbox — the local store
    // stays on the original phone). Funds are safe (on-chain, seed-derived),
    // but the trade list and chat history do not travel.
    var showRestoreWarning by remember { mutableStateOf(false) }

    // Collect one-time events (copy feedback, generation errors) from ViewModel.
    val snackbarHostState = remember { SnackbarHostState() }
    val event by viewModel.events.collectAsStateWithLifecycle()
    LaunchedEffect(event) {
        event?.let { ev ->
            when (ev) {
                is OnboardingViewModel.OnboardingEvent.ShowMessage -> snackbarHostState.showSnackbar(ev.message)
                is OnboardingViewModel.OnboardingEvent.CopySeed -> {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("NEO-P2P seed phrase", ev.seed)
                    clipboard.setPrimaryClip(clip)
                    // Auto-clear after 60s so the phrase does not linger on the
                    // system clipboard (other apps can read it). Only clear if
                    // it is still OUR phrase — never clobber something the
                    // user copied later.
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        val current = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                        if (current == ev.seed) {
                            clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                        }
                    }, 60_000L)
                    snackbarHostState.showSnackbar(context.getString(R.string.onb_seed_copied))
                }
            }
            viewModel.consumeEvent()
        }
    }

    NeoP2PTheme {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            content = { innerPadding ->
                Box(
                    modifier = modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    // Wizard progress dots — modern onboarding keeps users
                    // oriented (Bitcoin Design guide: never lose the user).
                    OnboardingStepIndicator(
                        total = OnboardingStep.entries.size,
                        current = state.currentStep.ordinal,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp)
                    )
                    when (state.currentStep) {
                        OnboardingStep.WELCOME -> WelcomeScreen(
                            onNext = { viewModel.nextStep() }
                        )
                        OnboardingStep.CREATE_IDENTITY -> CreateIdentityScreen(
                            viewModel = viewModel,
                            onNext = { viewModel.nextStep() },
                            onRestore = { viewModel.goToRestore() }
                        )
                        OnboardingStep.RESTORE -> RestoreIdentityScreen(
                            viewModel = viewModel,
                            // Restore succeeded → warn that open trades live on
                            // the OLD device, then finish onboarding.
                            onRestored = { showRestoreWarning = true },
                            onBack = { viewModel.nextStep() }
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
        )

        // C4: seed-restore warning — open trades live on the OLD device.
        if (showRestoreWarning) {
            AlertDialog(
                onDismissRequest = { /* deliberate: must acknowledge */ },
                title = { Text(stringResource(R.string.onb_restore_warning_title)) },
                text = { Text(stringResource(R.string.onb_restore_warning_body)) },
                confirmButton = {
                    TextButton(onClick = {
                        showRestoreWarning = false
                        viewModel.completeOnboarding()
                    }) {
                        Text(stringResource(R.string.onb_restore_warning_continue))
                    }
                }
            )
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
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
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
    onRestore: () -> Unit,
    modifier: Modifier = Modifier
) {
    val identityState by viewModel.identityState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
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
            enter = expandVertically(animationSpec = tween(320, easing = NeoMotionEase)),
            exit = shrinkVertically(animationSpec = tween(220, easing = NeoMotionEase))
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

        Spacer(modifier = Modifier.height(16.dp))

        TextButton(
            onClick = onRestore,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.onb_restore_link))
        }
    }
}

@Composable
private fun RestoreIdentityScreen(
    viewModel: OnboardingViewModel,
    onRestored: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val restoreState by viewModel.restoreState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.onb_restore_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.onb_restore_desc),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
        )

        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = restoreState.seedInput,
            onValueChange = { viewModel.updateRestoreInput(it) },
            label = { Text(stringResource(R.string.onb_restore_title)) },
            placeholder = { Text(stringResource(R.string.onb_restore_placeholder)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
            )
        )

        restoreState.error?.let { errorMsg ->
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = errorMsg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = stringResource(R.string.error_code_line, ErrorCodes.ERR_INVALID_SEED),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        AnimatedVisibility(
            visible = restoreState.isRestoring,
            enter = expandVertically(animationSpec = tween(320, easing = NeoMotionEase)),
            exit = shrinkVertically(animationSpec = tween(220, easing = NeoMotionEase))
        ) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(48.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = { viewModel.restoreIdentity(onRestored) },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            enabled = !restoreState.isRestoring && restoreState.seedInput.isNotBlank()
        ) {
            Text(stringResource(R.string.onb_restore_button))
        }

        Spacer(modifier = Modifier.height(12.dp))

        TextButton(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.general_back))
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
    var seedVisible by remember { mutableStateOf(true) }
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
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

        val displayedWords = if (seedVisible) seedState.seedPhrase else List(seedState.seedPhrase.size) { "••••" }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium
                )
                .clickable {
                    val seed = seedState.seedPhrase.joinToString(" ")
                    viewModel.copySeedToClipboard(seed)
                }
        ) {
            Text(
                text = displayedWords.joinToString(" "),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(16.dp)
            )
        }

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
                onClick = { seedVisible = !seedVisible },
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
            ) {
                Text(
                    stringResource(
                        if (seedVisible) R.string.onb_hide_seed else R.string.onb_show_again
                    )
                )
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
            enter = expandVertically(animationSpec = tween(320, easing = NeoMotionEase)),
            exit = shrinkVertically(animationSpec = tween(220, easing = NeoMotionEase))
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
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
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
            enter = expandVertically(animationSpec = tween(320, easing = NeoMotionEase)),
            exit = shrinkVertically(animationSpec = tween(220, easing = NeoMotionEase))
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
enum class OnboardingStep { WELCOME, CREATE_IDENTITY, RESTORE, BACKUP_SEED, VERIFY_SEED, FINISH }

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
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

    // One-time events for actions that don't belong in persistent UI state.
    private val _events = MutableStateFlow<OnboardingEvent?>(null)
    val events: StateFlow<OnboardingEvent?> = _events.asStateFlow()

    sealed class OnboardingEvent {
        data class ShowMessage(val message: String) : OnboardingEvent()
        data class CopySeed(val seed: String) : OnboardingEvent()
    }

    fun consumeEvent() {
        _events.value = null
    }

    fun copySeedToClipboard(seed: String) {
        _events.value = OnboardingEvent.CopySeed(seed)
    }

    // Verification challenge: indices of the 3 words the user must re-enter
    private val _verifyState = MutableStateFlow(VerifyState())
    val verifyState: StateFlow<VerifyState> = _verifyState.asStateFlow()

    data class VerifyState(
        val challengeIndices: List<Int> = emptyList(),
        val entries: MutableMap<Int, String> = mutableMapOf(),
        val error: String? = null,
        val isVerifying: Boolean = false
    )

    // Restore-from-seed state
    private val _restoreState = MutableStateFlow(RestoreState())
    val restoreState: StateFlow<RestoreState> = _restoreState.asStateFlow()

    data class RestoreState(
        val seedInput: String = "",
        val isRestoring: Boolean = false,
        val error: String? = null
    )

    fun goToRestore() {
        _uiState.update { it.copy(currentStep = OnboardingStep.RESTORE) }
    }

    fun updateRestoreInput(value: String) {
        _restoreState.update { it.copy(seedInput = value, error = null) }
    }

    fun restoreIdentity(onRestored: () -> Unit) {
        val words = _restoreState.value.seedInput
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
        if (words.size != 12) {
            _restoreState.update {
                it.copy(error = context.getString(R.string.onb_seed_12_words))
            }
            return
        }
        _restoreState.update { it.copy(isRestoring = true, error = null) }
        viewModelScope.launch {
            try {
                identityManager.restoreFromSeedPhrase(words)
                _restoreState.update { it.copy(isRestoring = false) }
                onRestored()
            } catch (e: Exception) {
                _restoreState.update {
                    it.copy(
                        isRestoring = false,
                        error = e.message ?: context.getString(R.string.onb_seed_invalid)
                    )
                }
            }
        }
    }

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
            } catch (e: Exception) {
                _events.value = OnboardingEvent.ShowMessage(
                    e.message ?: "Failed to create identity. Please try again."
                )
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
            // User tapped "Back" on the restore screen → return to create identity
            OnboardingStep.RESTORE -> _uiState.update { it.copy(currentStep = OnboardingStep.CREATE_IDENTITY) }
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
                    context.getString(R.string.onb_verify_incomplete)
                } else {
                    context.getString(R.string.onb_verify_mismatch)
                }
                _verifyState.update {
                    it.copy(isVerifying = false, error = msg)
                }
            }
        }
    }

    fun completeOnboarding() {
        // Durable: a kill after this point may go straight to HOME, so the
        // backup+verify steps must have been completed before this is called.
        com.neop2p.data.local.OnboardingStore(context).markComplete()
        _uiState.update { it.copy(currentStep = OnboardingStep.FINISH) }
    }

    fun confirmBackup() {
        _seedState.update { it.copy(isConfirming = true) }
    }
}
