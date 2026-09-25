package com.neop2p.data.tor

import com.neop2p.data.network.TorGate
import com.neop2p.data.network.TorState
import com.neop2p.data.network.TorVerdict
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.Interceptor
import okhttp3.Response

/** Thrown before dispatch when Tor is enabled but not connected. */
class TorUnavailableException(reason: String) : IOException("Tor unavailable: $reason")

/**
 * Routes the single HTTP client through Tor's HTTP CONNECT port when connected,
 * and fails closed (blocks) when Tor is enabled but not connected. The override
 * permits direct HTTP for exactly one ACTION — it stays active across every
 * request the action makes and is cleared by the caller when the action ends
 * (success, failure, or cancellation), not by the first request.
 */
class TorHttpPolicy(
    private val enabledProvider: () -> Boolean,
    private val stateProvider: () -> TorState,
) : ProxySelector(), Interceptor {

    private val override = AtomicBoolean(false)

    fun overrideNext() { override.set(true) }
    fun clearOverride() { override.set(false) }

    /**
     * Runs [block] with the explicit direct override active, then clears it.
     * Action-scoped: a wallet scan or an escrow verify that makes many HTTP
     * requests completes under one override, and the override never leaks to
     * the next action. Cleared in `finally`, so cancellation also resets it.
     */
    suspend fun <T> runDirect(block: suspend () -> T): T {
        overrideNext()
        return try {
            block()
        } finally {
            clearOverride()
        }
    }

    fun verdictNow(): TorVerdict =
        TorGate.verdict(enabledProvider(), stateProvider(), override.get())

    override fun select(uri: URI): List<Proxy> {
        val state = stateProvider()
        return if (TorGate.verdict(enabledProvider(), state, override.get()) == TorVerdict.ALLOW_TOR) {
            // ALLOW_TOR implies Connected, so this cast is safe on the single snapshot.
            val port = (state as TorState.Connected).httpPort
            listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
        } else {
            listOf(Proxy.NO_PROXY)
        }
    }

    override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) {
        // OkHttp reports per-route failures; the interceptor owns the decision.
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val verdict = verdictNow()
        if (verdict == TorVerdict.BLOCK) {
            throw TorUnavailableException(TorGate.blockReason(stateProvider()) ?: "tor_not_ready")
        }
        // The override is intentionally NOT consumed here: a single action may
        // issue many requests (HD wallet scan, provider rotation). The caller
        // clears it via runDirect()/clearOverride() when the action completes.
        return chain.proceed(chain.request())
    }
}
