package com.neop2p.data.p2p.ratchet

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.SecureRandom

/** A legacy (v1) peer cannot be talked to; it must upgrade. */
class PeerMustUpgradeException(message: String) : Exception(message)

/**
 * Double Ratchet engine (E2EE v2, 2026-09-23). Pure JVM; the Android layer only
 * persists the returned [RatchetState].
 *
 * Handshake: both peers derive the X3DH secret `sk` from the exchanged v2
 * bundles (see PreKeyBundleCodec). The peer whose peerId sorts first is the
 * initiator; it performs the first DH ratchet step and sends a header-only
 * `ratchet_init`. The responder processes that header, deriving its receiving
 * chain, then generates its own sending chain — so both sides can send.
 */
object DoubleRatchet {
    const val VERSION = 2
    private val random = SecureRandom()

    fun generateDhKeyPair(): Pair<ByteArray, ByteArray> {
        val priv = ByteArray(32).also { random.nextBytes(it) }
        val pub = X25519PrivateKeyParameters(priv, 0).generatePublicKey().encoded
        return priv to pub
    }

    fun dh(priv: ByteArray, pub: ByteArray): ByteArray {
        val agreement = X25519Agreement()
        agreement.init(X25519PrivateKeyParameters(priv, 0))
        val out = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(X25519PublicKeyParameters(pub, 0), out, 0)
        return out
    }

    /** The initiator: one DH ratchet step from the shared secret + remote SPK. */
    fun initiatorState(
        sk: ByteArray,
        initiatorDhPriv: ByteArray,
        initiatorDhPub: ByteArray,
        remoteSpkPub: ByteArray,
    ): RatchetState {
        val dhOut = dh(initiatorDhPriv, remoteSpkPub)
        val (root, sendChain) = RatchetKdf.rootKeyStep(sk, dhOut)
        dhOut.fill(0)
        return RatchetState(
            rootKey = root,
            dhSelfPriv = initiatorDhPriv,
            dhSelfPub = initiatorDhPub,
            dhRemotePub = remoteSpkPub,
            sendChainKey = sendChain,
            sendCount = 0,
            recvChainKey = ByteArray(0),
            recvCount = 0,
            prevChainLength = 0,
            recvEpoch = 0,
            skipped = emptyMap(),
            remoteInitialPub = remoteSpkPub,
        )
    }

    /** The responder before it has seen the initiator's ratchet header. */
    fun responderState(sk: ByteArray, spkPriv: ByteArray, spkPub: ByteArray): RatchetState =
        RatchetState(
            rootKey = sk.copyOf(),
            dhSelfPriv = spkPriv,
            dhSelfPub = spkPub,
            dhRemotePub = ByteArray(0),
            sendChainKey = ByteArray(0),
            sendCount = 0,
            recvChainKey = ByteArray(0),
            recvCount = 0,
            prevChainLength = 0,
            recvEpoch = 0,
            skipped = emptyMap(),
            remoteInitialPub = spkPub,
        )

    /** Responder side of `ratchet_init`: derive recv chain, then its send chain. */
    fun processRatchetInit(
        state: RatchetState,
        sessionId: String,
        fromPeerId: String,
        headerBytes: ByteArray,
    ): RatchetState {
        val header = RatchetEnvelope.decodeHeader(headerBytes)
        var next = receiveRatchet(state, header)
        if (next.sendChainKey.isNotEmpty()) return next
        next = sendRatchet(next, header.dhPub)
        return next
    }

    fun encrypt(
        state: RatchetState,
        sessionId: String,
        fromPeerId: String,
        offerId: String,
        plaintext: ByteArray,
    ): Pair<RatchetState, ByteArray> {
        check(state.sendChainKey.isNotEmpty()) { "No sending chain yet — waiting for peer ratchet_init" }
        val (messageKey, nextChain) = RatchetKdf.chainKeyStep(state.sendChainKey)
        val header = RatchetHeader(state.dhSelfPub, state.sendCount, state.prevChainLength)
        val aad = RatchetAad.build(sessionId, fromPeerId, offerId, header)
        val ciphertext = ChaChaAead.seal(messageKey, aad, plaintext)
        messageKey.fill(0)
        val envelope = RatchetEnvelope.encode(header, ciphertext)
        val next = state.copy(sendChainKey = nextChain, sendCount = state.sendCount + 1)
        return next to envelope
    }

    fun decrypt(
        state: RatchetState,
        sessionId: String,
        fromPeerId: String,
        offerId: String,
        envelope: ByteArray,
    ): Pair<RatchetState, ByteArray> {
        val (header, ciphertext) = RatchetEnvelope.decode(envelope)
        var next = state
        if (!header.dhPub.contentEquals(next.dhRemotePub)) {
            // A new remote DH key is a full DH ratchet step: derive the new
            // receiving chain, then ALWAYS rotate our own sending key/chain so
            // the peer can ratchet on our next message (Double Ratchet
            // DHRatchet). The old sending chain must never be reused.
            next = receiveRatchet(next, header)
            next = sendRatchet(next, header.dhPub)
        }
        val key = RatchetState.skippedKey(next.recvEpoch, header.msgNum)
        val skippedKey = next.skipped[key]
        when (val decision = RatchetReplayWindow.decide(
            recvCount = next.recvCount,
            msgNum = header.msgNum,
            hasSkippedKey = skippedKey != null,
            skippedSize = next.skipped.size,
        )) {
            is RatchetReplayWindow.Decision.Reject ->
                throw IllegalStateException("Rejected ratchet message: ${decision.reason}")
            is RatchetReplayWindow.Decision.FromSkipped -> {
                val aad = RatchetAad.build(sessionId, fromPeerId, offerId, header)
                val plain = ChaChaAead.open(skippedKey!!, aad, ciphertext)
                val updated = next.copy(skipped = next.skipped - key)
                skippedKey.fill(0)
                return updated to plain
            }
            is RatchetReplayWindow.Decision.InOrder ->
                return decryptFromChain(next, header, sessionId, fromPeerId, offerId, ciphertext, skipCount = 0)
            is RatchetReplayWindow.Decision.SkipAhead ->
                return decryptFromChain(
                    next, header, sessionId, fromPeerId, offerId, ciphertext,
                    skipCount = (decision.msgNum - next.recvCount).toInt()
                )
        }
    }

    private fun decryptFromChain(
        state: RatchetState,
        header: RatchetHeader,
        sessionId: String,
        fromPeerId: String,
        offerId: String,
        ciphertext: ByteArray,
        skipCount: Int,
    ): Pair<RatchetState, ByteArray> {
        var chain = state.recvChainKey
        val skipped = LinkedHashMap(state.skipped)
        var epoch = state.recvEpoch
        repeat(skipCount) { offset ->
            val (mk, nextChain) = RatchetKdf.chainKeyStep(chain)
            skipped[RatchetState.skippedKey(epoch, state.recvCount + offset)] = mk
            chain = nextChain
        }
        val (messageKey, nextChain) = RatchetKdf.chainKeyStep(chain)
        val aad = RatchetAad.build(sessionId, fromPeerId, offerId, header)
        val plain = ChaChaAead.open(messageKey, aad, ciphertext)
        messageKey.fill(0)
        val updated = state.copy(
            recvChainKey = nextChain,
            recvCount = header.msgNum + 1,
            skipped = skipped,
        )
        return updated to plain
    }

    private fun receiveRatchet(state: RatchetState, header: RatchetHeader): RatchetState {
        var chain = state.recvChainKey
        val skipped = LinkedHashMap(state.skipped)
        val epoch = state.recvEpoch
        if (chain.isNotEmpty()) {
            var count = state.recvCount
            while (count < header.prevChainLength) {
                val (mk, nextChain) = RatchetKdf.chainKeyStep(chain)
                skipped[RatchetState.skippedKey(epoch, count)] = mk
                chain = nextChain
                count++
            }
        }
        val dhOut = dh(state.dhSelfPriv, header.dhPub)
        val (root, recvChain) = RatchetKdf.rootKeyStep(state.rootKey, dhOut)
        dhOut.fill(0)
        return state.copy(
            rootKey = root,
            recvChainKey = recvChain,
            recvCount = 0,
            recvEpoch = epoch + 1,
            dhRemotePub = header.dhPub,
            skipped = skipped,
        )
    }

    private fun sendRatchet(state: RatchetState, remotePub: ByteArray): RatchetState {
        val (newPriv, newPub) = generateDhKeyPair()
        state.dhSelfPriv.fill(0)
        val dhOut = dh(newPriv, remotePub)
        val (root, sendChain) = RatchetKdf.rootKeyStep(state.rootKey, dhOut)
        dhOut.fill(0)
        return state.copy(
            rootKey = root,
            dhSelfPriv = newPriv,
            dhSelfPub = newPub,
            sendChainKey = sendChain,
            sendCount = 0,
            prevChainLength = state.sendCount,
        )
    }
}
