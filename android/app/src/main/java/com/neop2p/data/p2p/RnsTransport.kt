package com.neop2p.data.p2p

import android.content.Context
import android.util.Log
import com.neop2p.data.p2p.P2PTransport.TransportMessage
import com.neop2p.data.p2p.P2PTransport.TransportState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import network.reticulum.Reticulum
import network.reticulum.identity.Identity

/**
 * RNS + LXMF transport (Phase 1 skeleton: start/stop only, dual-run).
 *
 * Wraps [Reticulum.start] in client-only mode (`enableTransport=false` on
 * phones) with a deterministic 64-byte identity derived from the BIP-39
 * mnemonic (SLIP-10 m/44'/999'/0'/0/1 curve25519 + m/44'/999'/0'/0/2
 * ed25519), so peer IDs stay stable across the libp2p→RNS migration.
 *
 * Phase 2+ adds the LXMRouter (chat/escrow/arbitration over LXMF) and
 * announce-based offer feed (Phase 3). Until then send/publish are stubs and
 * the legacy stack (HybridP2PTransport) remains the active transport.
 */
@Singleton
class RnsTransport @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val identityManager: IdentityManager
) : P2PTransport {

    private var rns: Reticulum? = null

    override val state = MutableStateFlow(TransportState())
    override val incomingMessages = MutableSharedFlow<TransportMessage>()

    override suspend fun start(): Result<Unit> = runCatching {
        if (rns != null) return@runCatching
        val identity = Identity.fromPrivateKey(KeyDerivation.rnsIdentity(identityManager.getMasterSeed()))
        rns = Reticulum.start(
            configDir = context.filesDir.resolve("reticulum").absolutePath,
            enableTransport = false,
            transportIdentity = identity
        )
        Log.i(TAG, "started (identity ${identity.hexHash.take(12)}…)")
        state.value = TransportState(isRunning = true, transportType = "rns")
    }.onFailure { Log.e(TAG, "start failed: ${it.message}") }

    override suspend fun stop(): Result<Unit> = runCatching {
        Reticulum.stop()
        rns = null
        state.value = TransportState()
    }.onFailure { Log.e(TAG, "stop failed: ${it.message}") }

    override suspend fun send(toPeerId: String, data: ByteArray, type: String): Result<Unit> =
        Result.failure(NotImplementedError("Phase 2 — LXMF"))

    override suspend fun publish(topic: String, data: ByteArray): Result<Unit> =
        Result.failure(NotImplementedError("Phase 3 — announce appData"))

    override suspend fun subscribe(topic: String): Result<Unit> = Result.success(Unit)

    override fun isDirect(): Boolean = false

    companion object {
        private const val TAG = "RnsTransport"
    }
}
