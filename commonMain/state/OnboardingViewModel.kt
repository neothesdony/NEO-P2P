package com.neop2p.state

import com.neop2p.domain.model.Identity
import com.neop2p.domain.repository.IdentityRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class OnboardingViewModel(
    private val identityRepository: IdentityRepository
) {
    private val _uiState = MutableStateFlow<OnboardingUiState>(OnboardingUiState.Loading)
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    init {
        loadIdentity()
    }

    private fun loadIdentity() {
        viewModelScope.launch {
            try {
                // Emit loading state immediately
                _uiState.value = OnboardingUiState.Loading

                // Try to get existing identity
                identityRepository.getIdentity().collect { identity ->
                    if (identity != null) {
                        // Identity exists, go to home
                        _uiState.value = OnboardingUiState.IdentityExists(identity)
                    } else {
                        // No identity, show onboarding steps
                        _uiState.value = OnboardingUiState.NicknameInput("")
                    }
                }
            } catch (e: Exception) {
                _uiState.value = OnboardingUiState.Error(e.localizedMessage ?: "Unknown error")
            }
        }
    }

    fun onNicknameChanged(nickname: String) {
        _uiState.value = OnboardingUiState.NicknameInput(nickname)
    }

    fun onNicknameConfirmed(nickname: String) {
        _uiState.value = OnboardingUiState.Loading
        viewModelScope.launch {
            try {
                // Save the identity with the given nickname
                // In a real app, we would generate keys, etc.
                val identity = Identity(
                    peerId = generatePeerId(), // placeholder
                    nickname = nickname,
                    nostrPubkeyHex = "0x1234...", // placeholder
                    lnNodeId = "0x5678...", // placeholder
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
                identityRepository.saveIdentity(identity)
                _uiState.value = OnboardingUiState.IdentitySaved(identity)
            } catch (e: Exception) {
                _uiState.value = OnboardingUiState.Error(e.localizedMessage ?: "Failed to save identity")
            }
        }
    }

    private fun generatePeerId(): String {
        // Placeholder: generate a random peer ID
        return "peer_" + System.currentTimeMillis().toString(36)
    }
}

// UI State for the Onboarding screen
sealed class OnboardingUiState {
    object Loading : OnboardingUiState()
    data class NicknameInput(val nickname: String) : OnboardingUiState()
    data class IdentitySaved(val identity: Identity) : OnboardingUiState()
    data class IdentityExists(val identity: Identity) : OnboardingUiState()
    data class Error(val message: String) : OnboardingUiState()
}