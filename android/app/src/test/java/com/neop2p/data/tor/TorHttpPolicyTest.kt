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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TorHttpPolicyTest {
    private val uri = URI("https://mempool.space/api/blocks/tip/height")

    private fun policy(enabled: Boolean, state: TorState) =
        TorHttpPolicy({ enabled }, { state })

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

    // R4 adaptation: the brief's stub overrides only the okhttp 4.x Chain surface.
    // The resolved dependency is okhttp 5.5.0 (via ktor-client-okhttp 3.6.0), whose
    // `Interceptor.Chain` additionally declares the redirect/timeout/DNS/TLS/pool
    // members below. They are never exercised by these tests, so they throw.
    private class RecordingChain : Interceptor.Chain {
        var proceeded = 0
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
        override fun connectTimeoutMillis(): Int = 0
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun readTimeoutMillis(): Int = 0
        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun writeTimeoutMillis(): Int = 0
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this

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
