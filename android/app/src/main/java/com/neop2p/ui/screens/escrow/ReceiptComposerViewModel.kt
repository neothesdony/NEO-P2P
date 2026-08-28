package com.neop2p.ui.screens.escrow

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neop2p.data.escrow.EscrowService
import com.neop2p.data.local.dao.OfferDao
import com.neop2p.data.local.toDomain
import com.neop2p.data.p2p.routing.ChatRouter
import com.neop2p.data.p2p.routing.PaymentReceiptPayload
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ReceiptComposerViewModel @Inject constructor(
    private val escrowService: EscrowService,
    private val chatRouter: ChatRouter,
    private val offerDao: OfferDao
) : ViewModel() {

    companion object {
        // No I/L/O/0/1 — unambiguous when read aloud or over chat.
        private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        fun generateReference(): String =
            (1..8).map { ALPHABET.random() }.joinToString("")
    }

    data class UiState(
        val reference: String = generateReference(),
        // Escrow-derived ids for the E2EE send (no offerId/peerId route params).
        val offerId: String = "",
        val sellerPeerId: String = "",
        val amountSats: Long = 0,
        val fiatAmount: Long = 0,
        val method: String = "",
        val imageBase64: String? = null,
        val loading: Boolean = false,
        val sending: Boolean = false,
        val error: String? = null,
        val sent: Boolean = false
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * Derive the offer from the escrow (route carries escrowId only):
     * escrow → offerId → TradeOffer (amount + payment method prefill),
     * and remember the seller peer for the E2EE receipt message.
     */
    fun load(escrowId: String) {
        if (_state.value.loading) return
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            val escrow = escrowService.getEscrow(escrowId)
            if (escrow == null) {
                _state.value = _state.value.copy(loading = false, error = "Escrow not found")
                return@launch
            }
            val offer = offerDao.getOfferSync(escrow.offerId)?.toDomain()
            _state.value = _state.value.copy(
                loading = false,
                offerId = escrow.offerId,
                sellerPeerId = escrow.sellerPeerId,
                amountSats = offer?.cryptoAmountSats ?: escrow.tradeAmountSats,
                fiatAmount = offer?.fiatAmount ?: 0L,
                method = offer?.fiatMethods?.firstOrNull().orEmpty()
            )
        }
    }

    fun regenerateReference() {
        _state.value = _state.value.copy(reference = generateReference())
    }

    fun setImage(base64: String?) { _state.value = _state.value.copy(imageBase64 = base64) }

    fun send(escrowId: String, offerId: String, peerId: String) {
        if (_state.value.sending) return
        val s = _state.value
        // The screenshot is the buyer's proof of payment — the seller's
        // release gate depends on it, so a receipt without one is refused.
        if (s.imageBase64 == null) {
            _state.value = _state.value.copy(error = "Attach a payment screenshot before sending the receipt")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(sending = true, error = null)
            val payload = PaymentReceiptPayload(s.reference, s.amountSats, s.method, System.currentTimeMillis(), s.imageBase64)
            // The escrow transition is the source of truth: RECEIPT_SENT
            // unlocks the seller's confirm gate. The E2EE chat copy is
            // best-effort — a dead session must NOT block the receipt
            // (previously both had to succeed or the buyer was stuck).
            val escrowResult = escrowService.sendReceipt(escrowId, s.reference, s.imageBase64)
            if (escrowResult.isSuccess) {
                // Best-effort chat delivery; failures are logged, not fatal.
                runCatching { chatRouter.sendReceiptMessage(offerId, peerId, payload) }
                    .onFailure { android.util.Log.w("ReceiptComposer", "Receipt chat copy failed (escrow already RECEIPT_SENT): ${it.message}") }
                _state.value = _state.value.copy(sending = false, sent = true)
            } else {
                _state.value = _state.value.copy(
                    sending = false,
                    error = escrowResult.exceptionOrNull()?.message
                )
            }
        }
    }
}
