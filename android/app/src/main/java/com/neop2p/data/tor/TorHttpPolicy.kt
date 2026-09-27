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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
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
    private val now: () -> Long = System::currentTimeMillis,
) : ProxySelector(), Interceptor {

    /** Unix-ms instant at which the current override expires; 0 = no override. */
    private val overrideUntil = AtomicLong(0L)

    fun overrideNext() { overrideUntil.set(now() + OVERRIDE_TTL_MS) }
    fun clearOverride() { overrideUntil.set(0L) }

    /**
     * True while an override is active AND not past its TTL. Consent is for one
     * action, so a caller that crashes or hangs mid-action cannot leave the
     * override latched open forever.
     */
    private fun overrideActive(): Boolean {
        val until = overrideUntil.get()
        return until != 0L && now() < until
    }

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
        TorGate.verdict(enabledProvider(), stateProvider(), overrideActive())

    override fun select(uri: URI): List<Proxy> {
        val state = stateProvider()
        return if (TorGate.verdict(enabledProvider(), state, overrideActive()) == TorVerdict.ALLOW_TOR) {
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
        // Per-request timeouts keyed on the verdict: a Tor circuit build needs a
        // wider deadline than a direct request. OkHttp's per-call timeout
        // (withConnectTimeout/withReadTimeout/withWriteTimeout) overrides the
        // client-level Ktor HttpTimeout defaults for this dispatch only, so the
        // default direct path keeps connect 10s / read 20s / write 20s.
        // The override is intentionally NOT consumed here: a single action may
        // issue many requests (HD wallet scan, provider rotation). The caller
        // clears it via runDirect()/clearOverride() when the action completes.
        val scoped = if (verdict == TorVerdict.ALLOW_TOR) {
            chain
                .withConnectTimeout(TOR_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .withReadTimeout(TOR_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .withWriteTimeout(TOR_WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } else {
            chain
                .withConnectTimeout(DIRECT_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .withReadTimeout(DIRECT_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .withWriteTimeout(DIRECT_WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
        return scoped.proceed(scoped.request())
    }

    companion object {
        // Tor: a circuit build can outlast the direct default. Kept under the
        // Ktor request timeout (90s) so the socket timeout, not the overall
        // request deadline, is what fails.
        const val TOR_CONNECT_TIMEOUT_MS = 60_000
        const val TOR_READ_TIMEOUT_MS = 90_000
        const val TOR_WRITE_TIMEOUT_MS = 90_000
        // Direct: the pre-Tor defaults (connect 10s / request+socket 20s).
        const val DIRECT_CONNECT_TIMEOUT_MS = 10_000
        const val DIRECT_READ_TIMEOUT_MS = 20_000
        const val DIRECT_WRITE_TIMEOUT_MS = 20_000
        // A per-action override is consent for ONE action; it expires even if
        // the caller never clears it (crash / hung action). Bounded window.
        const val OVERRIDE_TTL_MS = 180_000L
    }
}
