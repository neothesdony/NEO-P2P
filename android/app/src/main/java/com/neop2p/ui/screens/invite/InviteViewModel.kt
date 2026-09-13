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
        data class Success(
            val peerId: String,
            val alreadyKnown: Boolean,
            val identityHashHex: String? = null
        ) : UiState()
        data class Error(val messageRes: Int) : UiState()
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** The `neop2p://peer/<myPeerId>#<identityHash>` link others scan to reach us. */
    fun myInviteLink(): String {
        val peerId = identityManager.myPeerId()
        val identityHash = identityManager.myRnsIdentityHash()
        return if (identityHash.isNullOrBlank()) {
            "neop2p://peer/$peerId"
        } else {
            "neop2p://peer/$peerId#$identityHash"
        }
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
            val (peerId, identityHash) = parsed
            if (peerId == identityManager.myPeerId()) {
                _uiState.value = UiState.Error(com.neop2p.R.string.invite_self)
                return@launch
            }
            val alreadyKnown = peerRegistry.isPeerKnown(peerId)
            peerRegistry.recordPeerSeen(peerId)
            _uiState.value = UiState.Success(peerId, alreadyKnown, identityHash)
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
         * from the profile screen). Optionally carries an identity binding as a
         * `#<32-hex>` fragment (`neop2p://peer/<id>#<identityHash>`), matching
         * the 16-byte RNS identity hash (`Identity.hexHash`) that the
         * `neop2p.identity` binding announce carries (PeerBinding/RnsSession).
         *
         * Returns peerId to identityHashHex (null when the link carries no
         * binding — the caller must then treat the peer as unverified).
         */
        fun parseInvite(raw: String): Pair<String, String?>? {
            val text = raw.trim()
            val inner = if (text.startsWith("neop2p://peer/")) {
                text.removePrefix("neop2p://peer/")
            } else {
                text
            }
            // Drop query params, then split off the identity-hash fragment.
            val withoutQuery = inner.substringBefore('?')
            val id = withoutQuery.substringBefore('#')
            val hash = withoutQuery.substringAfter('#', "").ifBlank { null }

            if (id.length !in 8..128) return null
            if (!id.all { it.isLetterOrDigit() }) return null
            if (hash != null) {
                if (hash.length != 32) return null
                if (!hash.all { it in "0123456789abcdefABCDEF" }) return null
            }
            return id to hash
        }
    }
}
