package com.neop2p.data.p2p

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import network.reticulum.Reticulum
import network.reticulum.interfaces.tcp.TCPClientInterface
import network.reticulum.interfaces.toRef
import network.reticulum.transport.Transport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * B6/S07 (2026-09-01): high-latency + jitter link test.
 *
 * The RnsFaultProxy delays every relayed chunk by 400ms + 0-300ms jitter
 * (both directions) — a slow, jittery link like a congested mobile network
 * or a long-distance TCP path. The full RNS flow must still complete:
 * announce → path → link establish → chat round-trip → LXMF signaling.
 *
 * Link establishment timeout is 6s/hop + 360s keepalive (LinkConstants),
 * so 400-700ms per-chunk delay is well inside the budget.
 *
 * Reuses RnsTwoProcessServerMain (standard RECEIVED/SIGNAL protocol) with a
 * unique seed offset (31) so the full suite stays collision-free.
 */
class RnsLatencyTest {

    private lateinit var configDir: File
    private lateinit var session: RnsSession
    private var child: Process? = null
    private var proxy: RnsFaultProxy? = null
    private var tcpClient: TCPClientInterface? = null

    @Before
    fun setUp() {
        Reticulum.stop()
        configDir = Files.createTempDirectory("rns-lat-parent-").toFile()
    }

    @After
    fun tearDown() {
        runCatching { tcpClient?.detach() }
        runCatching { session.stop() }
        runCatching { Reticulum.stop() }
        proxy?.stop()
        child?.destroy()
        child?.waitFor(5, TimeUnit.SECONDS)
        configDir.deleteRecursively()
    }

    @Test
    fun `chat and signaling complete over a high-latency jittery link`() = runBlocking {
        // Disjoint port range: 20000-39999 (integration) / 40000-59999 (flap).
        // 50000-64999 keeps us under the 65535 TCP limit.
        val serverPort = 50000 + (System.currentTimeMillis() % 15000).toInt()
        val proxyPort = serverPort + 1

        // 1. Child JVM (real Reticulum + TCP server), unique seed offset 31.
        val javaBin = File(System.getProperty("java.home"), "bin/java").absolutePath
        val classpath = System.getProperty("java.class.path")
        val pb = ProcessBuilder(
            javaBin, "-cp", classpath,
            "com.neop2p.data.p2p.RnsTwoProcessServerMainKt", serverPort.toString(), "31"
        )
        pb.redirectErrorStream(true)
        child = pb.start()
        val childOut = child!!.inputStream.bufferedReader()

        val readyLine = withTimeout(30_000) {
            var line: String?
            while (true) {
                line = childOut.readLine() ?: throw IllegalStateException("child exited early")
                if (line.startsWith("READY ")) break
                if (line.startsWith("CHILD_ERROR")) throw IllegalStateException("child failed: $line")
            }
            line
        }
        readyLine.removePrefix("READY ")

        // 2. Proxy with 400ms base delay + 0-300ms jitter per chunk.
        proxy = RnsFaultProxy(proxyPort, "127.0.0.1", serverPort, delayMs = 400, jitterMs = 300)
        proxy!!.start()

        // 3. Parent: Reticulum + RnsSession (peerB), TCP client → proxy.
        Reticulum.start(configDir = configDir.absolutePath, enableTransport = true)
        tcpClient = TCPClientInterface(
            name = "ParentClient",
            targetHost = "127.0.0.1",
            targetPort = proxyPort
        )
        Transport.registerInterface(tcpClient!!.toRef())
        tcpClient!!.start()

        session = RnsSession(
            configDir = configDir.absolutePath,
            // Unique seed: 13 (integration parent), 29 (flap parent) taken.
            seed = ByteArray(64) { (it + 37).toByte() },
            myPeerId = "peerB"
        )
        session.start().getOrThrow()

        val linkDeadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < linkDeadline && tcpClient?.online?.value != true) {
            Thread.sleep(100)
        }
        assertTrue("parent TCP link must come online", tcpClient?.online?.value == true)
        session.reannounce()

        // 4. Baseline: child announces, sends "hello from child", we reply.
        val childPeerId = withTimeout(60_000) { session.peerSeen.first() }
        assertEquals("peerA", childPeerId)

        val baseline = async { withTimeout(60_000) { session.incoming.first() } }
        yield()
        val inbound = baseline.await()
        assertEquals("chat", inbound.type)
        assertEquals("hello from child", inbound.data.toString(Charsets.UTF_8))

        val sent = session.send("peerA", "hello from parent".encodeToByteArray(), "chat")
        assertTrue("baseline send must succeed: ${sent.exceptionOrNull()}", sent.isSuccess)

        // 5. Signaling over the same delayed link (offer_status).
        val status = session.sendOfferStatus("peerA", "offer_1", "MATCHED", "peerB", "tb1qabc", "peerB")
        assertTrue("offer_status send must succeed: ${status.exceptionOrNull()}", status.isSuccess)

        // 6. Child prints RECEIVED + SIGNAL and exits 0.
        val exitCode = withTimeout(90_000) { child!!.waitFor() }
        val output = childOut.readText()
        assertEquals("child must exit 0, output: $output", 0, exitCode)
        assertTrue("child must print RECEIVED, got: $output", output.contains("RECEIVED peerB hello from parent"))
        assertTrue("child must print SIGNAL, got: $output", output.contains("SIGNAL offer_status"))
    }
}
