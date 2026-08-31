package com.neop2p.ui.screens.invite

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neop2p.NeoP2PConfig
import com.neop2p.data.p2p.IdentityManager
import com.neop2p.data.p2p.store.PeerRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class InviteViewModel @Inject constructor(
    private val identityManager: IdentityManager,
    private val peerRegistry: PeerRegistry
) : ViewModel() {

    sealed class UiState {
        object Idle : UiState()
        object Validating : UiState()
        data class Success(val peerId: String, val alreadyKnown: Boolean) : UiState()
        data class Error(val messageRes: Int) : UiState()
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** The `neop2p://peer/<myPeerId>` link others scan to reach us. */
    fun myInviteLink(): String {
        val peerId = identityManager.myPeerId()
        return "neop2p://peer/$peerId"
    }

    fun parseAndConnect(raw: String) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return
        _uiState.value = UiState.Validating
        viewModelScope.launch {
            val parsed = parseInvite(trimmed)
            if (parsed == null) {
                _uiState.value = UiState.Error(com.neop2p.R.string.invite_invalid_qr)
                return@launch
            }
            val (peerId, _) = parsed
            if (peerId == identityManager.myPeerId()) {
                _uiState.value = UiState.Error(com.neop2p.R.string.invite_self)
                return@launch
            }
            val alreadyKnown = peerRegistry.isPeerKnown(peerId)
            peerRegistry.recordPeerSeen(peerId)
            _uiState.value = UiState.Success(peerId, alreadyKnown)
        }
    }

    fun onScanCancelled() {
        _uiState.value = UiState.Idle
    }

    fun reset() {
        _uiState.value = UiState.Idle
    }

    companion object {
        /**
         * Accepts `neop2p://peer/<id>` and tolerates a bare peer id (pasted
         * from the profile screen). The peer id is a base58-style libp2p id —
         * anything 8..128 chars of [A-Za-z0-9] is accepted.
         */
        fun parseInvite(raw: String): Pair<String, String?>? {
            val text = raw.trim()
            val id = if (text.startsWith("neop2p://peer/")) {
                text.removePrefix("neop2p://peer/").substringBefore('?')
            } else {
                text
            }
            if (id.length !in 8..128) return null
            if (!id.all { it.isLetterOrDigit() }) return null
            return id to null
        }
    }
}
