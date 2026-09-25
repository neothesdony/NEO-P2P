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
 * and fails closed (blocks) when Tor is enabled but not connected. The one-shot
 * override permits exactly one direct action.
 */
class TorHttpPolicy(
    private val enabledProvider: () -> Boolean,
    private val stateProvider: () -> TorState,
) : ProxySelector(), Interceptor {

    private val override = AtomicBoolean(false)

    fun overrideNext() { override.set(true) }
    fun clearOverride() { override.set(false) }

    fun verdictNow(): TorVerdict =
        TorGate.verdict(enabledProvider(), stateProvider(), override.get())

    override fun select(uri: URI): List<Proxy> {
        val state = stateProvider()
        return when (verdictNow()) {
            TorVerdict.ALLOW_TOR ->
                listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", (state as TorState.Connected).httpPort)))
            else -> listOf(Proxy.NO_PROXY)
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
        return try {
            chain.proceed(chain.request())
        } finally {
            // A one-shot override is consumed by the first action it allows.
            override.set(false)
        }
    }
}
