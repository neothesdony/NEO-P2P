package com.neop2p.data.tor

import com.neop2p.data.network.TorState
import com.neop2p.data.network.TorVerdict
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.concurrent.TimeUnit
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TorHttpPolicyTest {
    private val uri = URI("https://mempool.space/api/blocks/tip/height")

    private fun policy(enabled: Boolean, state: TorState) =
        TorHttpPolicy({ enabled }, { state })

    private fun assertBlocks(p: TorHttpPolicy) {
        try {
            p.intercept(RecordingChain())
            fail("expected TorUnavailableException")
        } catch (_: TorUnavailableException) {
        }
    }

    @Test fun disabledSelectsNoProxy() {
        val p = policy(false, TorState.Disabled)
        assertEquals(listOf(Proxy.NO_PROXY), p.select(uri))
        assertEquals(TorVerdict.ALLOW_DIRECT, p.verdictNow())
    }

    @Test fun connectedSelectsHttpProxyOnTorPort() {
        val p = policy(true, TorState.Connected(8118))
        assertEquals(
            listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 8118))),
            p.select(uri),
        )
    }

    @Test fun enabledButNotConnectedThrowsAndDoesNotProceed() {
        val p = policy(true, TorState.Starting)
        val chain = RecordingChain()
        try {
            p.intercept(chain)
            fail("expected TorUnavailableException")
        } catch (e: TorUnavailableException) {
            assertTrue(e.message!!.contains("tor_starting"))
        }
        assertEquals(0, chain.proceeded)
    }

    @Test fun overrideStaysActiveAcrossRequestsUntilCleared() {
        val p = policy(true, TorState.Starting)
        p.overrideNext()
        // One action may issue many requests (HD scan, provider rotation):
        // every request under the override proceeds.
        p.intercept(RecordingChain())
        p.intercept(RecordingChain())
        // Cleared: the next action blocks again.
        p.clearOverride()
        try {
            p.intercept(RecordingChain())
            fail("expected TorUnavailableException after override cleared")
        } catch (_: TorUnavailableException) {
        }
    }

    @Test fun runDirectClearsOverrideAfterBlock() = kotlinx.coroutines.runBlocking {
        val p = policy(true, TorState.Starting)
        p.runDirect { p.intercept(RecordingChain()) }
        try {
            p.intercept(RecordingChain())
            fail("expected TorUnavailableException after runDirect")
        } catch (_: TorUnavailableException) {
        }
    }

    @Test fun disabledInterceptorProceeds() {
        val p = policy(false, TorState.Disabled)
        val chain = RecordingChain()
        p.intercept(chain)
        assertEquals(1, chain.proceeded)
    }

    // I-1: the per-request timeout is keyed on the verdict. A Tor dispatch gets
    // connect 60s / read 90s / write 90s; the default direct dispatch keeps the
    // pre-Tor connect 10s / read 20s / write 20s.
    @Test fun torPathUsesWideTimeouts() {
        val p = policy(true, TorState.Connected(8118))
        val chain = RecordingChain()
        p.intercept(chain)
        assertEquals(TorHttpPolicy.TOR_CONNECT_TIMEOUT_MS, chain.connectTimeoutMs)
        assertEquals(TorHttpPolicy.TOR_READ_TIMEOUT_MS, chain.readTimeoutMs)
        assertEquals(TorHttpPolicy.TOR_WRITE_TIMEOUT_MS, chain.writeTimeoutMs)
        assertEquals(90_000, chain.readTimeoutMs)
    }

    @Test fun directPathUsesDefaultTimeouts() {
        val p = policy(false, TorState.Disabled)
        val chain = RecordingChain()
        p.intercept(chain)
        assertEquals(TorHttpPolicy.DIRECT_CONNECT_TIMEOUT_MS, chain.connectTimeoutMs)
        assertEquals(TorHttpPolicy.DIRECT_READ_TIMEOUT_MS, chain.readTimeoutMs)
        assertEquals(TorHttpPolicy.DIRECT_WRITE_TIMEOUT_MS, chain.writeTimeoutMs)
        assertEquals(20_000, chain.readTimeoutMs)
    }

    @Test fun overrideDirectPathAlsoUsesDefaultTimeouts() {
        val p = policy(true, TorState.Starting)
        p.overrideNext()
        val chain = RecordingChain()
        p.intercept(chain)
        assertEquals(TorHttpPolicy.DIRECT_CONNECT_TIMEOUT_MS, chain.connectTimeoutMs)
        assertEquals(TorHttpPolicy.DIRECT_READ_TIMEOUT_MS, chain.readTimeoutMs)
        assertEquals(TorHttpPolicy.DIRECT_WRITE_TIMEOUT_MS, chain.writeTimeoutMs)
    }

    // I-4: the per-action override is bounded — past its TTL it is treated as
    // cleared even if the caller never called clearOverride().
    @Test fun overrideExpiresAfterTtl() {
        var clock = 1_000L
        val p = TorHttpPolicy({ true }, { TorState.Starting }, now = { clock })
        p.overrideNext()
        assertEquals(TorVerdict.ALLOW_DIRECT, p.verdictNow())
        clock += TorHttpPolicy.OVERRIDE_TTL_MS - 1
        assertEquals(TorVerdict.ALLOW_DIRECT, p.verdictNow())
        clock += 1
        assertEquals(TorVerdict.BLOCK, p.verdictNow())
        assertBlocks(p)
    }

    // M-5: the override is cleared in `finally`, so a throwing block cannot
    // leak direct consent into the next action.
    @Test fun runDirectClearsOverrideWhenBlockThrows() = runBlocking {
        val p = policy(true, TorState.Starting)
        try {
            p.runDirect { throw IllegalStateException("boom") }
            fail("expected IllegalStateException")
        } catch (_: IllegalStateException) {
        }
        assertBlocks(p)
    }

    @Test fun runDirectClearsOverrideWhenBlockCancelled() = runBlocking {
        val p = policy(true, TorState.Starting)
        try {
            p.runDirect { throw CancellationException("cancelled") }
            fail("expected CancellationException")
        } catch (_: CancellationException) {
        }
        assertBlocks(p)
    }

    // R4 adaptation: the brief's stub overrides only the okhttp 4.x Chain surface.
    // The resolved dependency is okhttp 5.5.0 (via ktor-client-okhttp 3.6.0), whose
    // `Interceptor.Chain` additionally declares the redirect/timeout/DNS/TLS/pool
    // members below. They are never exercised by these tests, so they throw.
    private class RecordingChain : Interceptor.Chain {
        var proceeded = 0
        // Captured so a test can assert the per-request timeout the policy applied.
        var connectTimeoutMs = 0
        var readTimeoutMs = 0
        var writeTimeoutMs = 0
        override fun request(): Request = Request.Builder().url("https://mempool.space/").build()
        override fun proceed(request: Request): Response {
            proceeded++
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("ok".toResponseBody())
                .build()
        }
        override fun connection(): Connection? = null
        override fun call(): okhttp3.Call = throw UnsupportedOperationException()
        override fun connectTimeoutMillis(): Int = connectTimeoutMs
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain {
            connectTimeoutMs = unit.toMillis(timeout.toLong()).toInt()
            return this
        }
        override fun readTimeoutMillis(): Int = readTimeoutMs
        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain {
            readTimeoutMs = unit.toMillis(timeout.toLong()).toInt()
            return this
        }
        override fun writeTimeoutMillis(): Int = writeTimeoutMs
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain {
            writeTimeoutMs = unit.toMillis(timeout.toLong()).toInt()
            return this
        }

        override val followSslRedirects: Boolean get() = throw UnsupportedOperationException()
        override val followRedirects: Boolean get() = throw UnsupportedOperationException()
        override val dns: okhttp3.Dns get() = throw UnsupportedOperationException()
        override fun withDns(dns: okhttp3.Dns): Interceptor.Chain =
            throw UnsupportedOperationException()
        override val socketFactory: javax.net.SocketFactory get() =
            throw UnsupportedOperationException()
        override fun withSocketFactory(socketFactory: javax.net.SocketFactory): Interceptor.Chain =
            throw UnsupportedOperationException()
        override val retryOnConnectionFailure: Boolean get() = throw UnsupportedOperationException()
        override fun withRetryOnConnectionFailure(retryOnConnectionFailure: Boolean): Interceptor.Chain =
            throw UnsupportedOperationException()
        override val authenticator: okhttp3.Authenticator get() =
            throw UnsupportedOperationException()
        override fun withAuthenticator(authenticator: okhttp3.Authenticator): Interceptor.Chain =
            throw UnsupportedOperationException()
        override val cookieJar: okhttp3.CookieJar get() = throw UnsupportedOperationException()
        override fun withCookieJar(cookieJar: okhttp3.CookieJar): Interceptor.Chain =
            throw UnsupportedOperationException()
        override val cache: okhttp3.Cache? get() = throw UnsupportedOperationException()
        override fun withCache(cache: okhttp3.Cache?): Interceptor.Chain =
            throw UnsupportedOperationException()
        override val proxy: Proxy? get() = throw UnsupportedOperationException()
        override fun withProxy(proxy: Proxy?): Interceptor.Chain =
            throw UnsupportedOperationException()
        override val proxySelector: java.net.ProxySelector get() =
            throw UnsupportedOperationException()
        override fun withProxySelector(proxySelector: java.net.ProxySelector): Interceptor.Chain =
            throw UnsupportedOperationException()
        override val proxyAuthenticator: okhttp3.Authenticator get() =
            throw UnsupportedOperationException()
        override fun withProxyAuthenticator(proxyAuthenticator: okhttp3.Authenticator): Interceptor.Chain =
            throw UnsupportedOperationException()
        override val sslSocketFactoryOrNull: javax.net.ssl.SSLSocketFactory? get() =
            throw UnsupportedOperationException()
        override fun withSslSocketFactory(
            sslSocketFactory: javax.net.ssl.SSLSocketFactory?,
            x509TrustManager: javax.net.ssl.X509TrustManager?,
        ): Interceptor.Chain = throw UnsupportedOperationException()
        override val x509TrustManagerOrNull: javax.net.ssl.X509TrustManager? get() =
            throw UnsupportedOperationException()
        override val hostnameVerifier: javax.net.ssl.HostnameVerifier get() =
            throw UnsupportedOperationException()
        override fun withHostnameVerifier(hostnameVerifier: javax.net.ssl.HostnameVerifier): Interceptor.Chain =
            throw UnsupportedOperationException()
        override val certificatePinner: okhttp3.CertificatePinner get() =
            throw UnsupportedOperationException()
        override fun withCertificatePinner(certificatePinner: okhttp3.CertificatePinner): Interceptor.Chain =
            throw UnsupportedOperationException()
        override val connectionPool: okhttp3.ConnectionPool get() =
            throw UnsupportedOperationException()
        override fun withConnectionPool(connectionPool: okhttp3.ConnectionPool): Interceptor.Chain =
            throw UnsupportedOperationException()
        override val eventListener: okhttp3.EventListener get() =
            throw UnsupportedOperationException()
    }
}
