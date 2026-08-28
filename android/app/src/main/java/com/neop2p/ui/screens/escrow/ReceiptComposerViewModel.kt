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
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val escrowService: EscrowService,
    private val chatRouter: ChatRouter,
    private val offerDao: OfferDao
) : ViewModel() {

    companion object {
        // No I/L/O/0/1 — unambiguous when read aloud or over chat.
        private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        private const val PREFS = "receipt_drafts"
        private const val DRAFT_REFERENCE = "draft_reference_"
        private const val DRAFT_IMAGE = "draft_image_"
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
        val sent: Boolean = false,
        // True when a previously-saved draft (killed app / back-nav mid-compose)
        // was restored for this escrow. Shows the "draft restored" banner.
        val hasDraft: Boolean = false
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val prefs =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)

    private fun draftKey(escrowId: String) = "draft_$escrowId"

    private fun saveDraft(escrowId: String) {
        val s = _state.value
        prefs.edit()
            .putString(DRAFT_REFERENCE + draftKey(escrowId), s.reference)
            .putString(DRAFT_IMAGE + draftKey(escrowId), s.imageBase64)
            .apply()
    }

    private fun clearDraft(escrowId: String) {
        prefs.edit()
            .remove(DRAFT_REFERENCE + draftKey(escrowId))
            .remove(DRAFT_IMAGE + draftKey(escrowId))
            .apply()
    }

    private fun hasDraft(escrowId: String): Boolean =
        prefs.contains(DRAFT_REFERENCE + draftKey(escrowId)) ||
            prefs.contains(DRAFT_IMAGE + draftKey(escrowId))

    /** Drop the saved draft and start fresh (fresh reference, no image). */
    fun discardDraft(escrowId: String) {
        clearDraft(escrowId)
        _state.value = _state.value.copy(
            reference = generateReference(),
            imageBase64 = null,
            hasDraft = false
        )
    }

    /**
     * Derive the offer from the escrow (route carries escrowId only):
     * escrow → offerId → TradeOffer (amount + payment method prefill),
     * and remember the seller peer for the E2EE receipt message. Any saved
     * draft (reference + screenshot) for this escrow is restored so a killed
     * app or back-nav mid-compose never loses the buyer's proof.
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
            // Restore the draft FIRST so a previously saved reference/image
            // survives navigation + process death (F19 draft-proof invariant).
            val draftRef = prefs.getString(DRAFT_REFERENCE + draftKey(escrowId), null)
            val draftImage = prefs.getString(DRAFT_IMAGE + draftKey(escrowId), null)
            val draft = hasDraft(escrowId)
            _state.value = _state.value.copy(
                loading = false,
                offerId = escrow.offerId,
                sellerPeerId = escrow.sellerPeerId,
                amountSats = offer?.cryptoAmountSats ?: escrow.tradeAmountSats,
                fiatAmount = offer?.fiatAmount ?: 0L,
                method = offer?.fiatMethods?.firstOrNull().orEmpty(),
                reference = draftRef ?: _state.value.reference,
                imageBase64 = draftImage ?: _state.value.imageBase64,
                hasDraft = draft
            )
        }
    }

    fun regenerateReference() {
        _state.value = _state.value.copy(reference = generateReference())
        // Write-through so a kill right after regenerating keeps the new code.
        saveDraft(_state.value.offerId)
    }

    fun setImage(base64: String?) {
        _state.value = _state.value.copy(imageBase64 = base64)
        // Write-through: the screenshot is the fragile part of the draft.
        saveDraft(_state.value.offerId)
    }

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
                // Draft consumed: a sent receipt must never resurrect on re-entry.
                clearDraft(escrowId)
                _state.value = _state.value.copy(sending = false, sent = true, hasDraft = false)
            } else {
                _state.value = _state.value.copy(
                    sending = false,
                    error = escrowResult.exceptionOrNull()?.message
                )
            }
        }
    }
}
