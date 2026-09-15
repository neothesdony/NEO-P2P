package com.neop2p.ui.util

/**
 * Stable test tags for the money-path call-to-action composables.
 *
 * `Modifier.testTag` plus these constants give instrumented / Maestro flows a
 * stable handle on the CTAs that move real money or authorize its release.
 * The string values are a test contract — renaming one is a breaking change
 * for the E2E flows, not a cosmetic refactor.
 */
object TestTags {
    const val CREATE_OFFER_SUBMIT = "create_offer_submit"
    const val ACCEPT_OFFER = "accept_offer"
    const val FUND_ESCROW = "fund_escrow"
    const val VERIFY_FUNDING = "verify_funding"
    const val MARK_PAID = "mark_paid"
    const val CONFIRM_RECEIPT = "confirm_receipt"
    const val REJECT_RECEIPT = "reject_receipt"
    const val OPEN_DISPUTE = "open_dispute"
    const val SHARE_PAYMENT_DETAILS = "share_payment_details"
    const val INVITE_IDENTITY_HASH = "invite_identity_hash"
    const val WALLET_SEND = "wallet_send"

    /** Every tag, in declaration order (pinned by [TestTagsTest]). */
    val all: List<String> = listOf(
        CREATE_OFFER_SUBMIT,
        ACCEPT_OFFER,
        FUND_ESCROW,
        VERIFY_FUNDING,
        MARK_PAID,
        CONFIRM_RECEIPT,
        REJECT_RECEIPT,
        OPEN_DISPUTE,
        SHARE_PAYMENT_DETAILS,
        INVITE_IDENTITY_HASH,
        WALLET_SEND,
    )
}
