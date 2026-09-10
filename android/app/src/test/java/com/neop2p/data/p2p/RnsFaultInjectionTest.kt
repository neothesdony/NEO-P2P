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
 * RNS fault-injection integration test (S05/S06): kill the TCP link
 * mid-conversation and prove the transport heals.
 *
 * Topology: child JVM (real Reticulum + TCPServerInterface) ← proxy ←
 * parent JVM (RnsSession + TCPClientInterface). The proxy relays bytes
 * verbatim; [RnsFaultProxy.kill] drops every live connection, which the
 * client's TCPClientInterface auto-reconnect and the server's per-client
 * interface spawn must survive.
 *
 * Sequence:
 *  1. baseline chat round-trip through the proxy (path works)
 *  2. proxy.kill() — both directions die
 *  3. parent's TCP client reconnects; parent re-announces (fresh path)
 *  4. parent sends a second message — the child must receive it
 *     (RECEIVED2) and exit 0
 */
class RnsFaultInjectionTest {

    private lateinit var configDir: File
    private lateinit var session: RnsSession
    private var child: Process? = null
    private var proxy: RnsFaultProxy? = null
    private var tcpClient: TCPClientInterface? = null

    @Before
    fun setUp() {
        Reticulum.stop()
        configDir = Files.createTempDirectory("rns-flap-parent-").toFile()
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
    fun `link kill mid-conversation heals and trade signaling still flows`() = runBlocking {
        // Disjoint port range from RnsTwoProcessIntegrationTest (20000-39999):
        // both tests spawn child JVMs in the same suite and overlapping
        // ranges collide (child fails to bind → spurious timeout).
        val serverPort = 40000 + (System.currentTimeMillis() % 20000).toInt()
        val proxyPort = serverPort + 1

        // 1. Start the child JVM (real Reticulum + TCP server).
        val javaBin = File(System.getProperty("java.home"), "bin/java").absolutePath
        val classpath = System.getProperty("java.class.path")
        val pb = ProcessBuilder(
            javaBin, "-cp", classpath,
            "com.neop2p.data.p2p.RnsTwoProcessFlapServerMainKt", serverPort.toString()
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

        // 2. Start the fault proxy between the parent and the child.
        proxy = RnsFaultProxy(proxyPort, "127.0.0.1", serverPort)
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
            // UNIQUE seed: RnsTwoProcessIntegrationTest's parent uses (it+13)
            // in the same JVM — Transport keeps registered destinations across
            // stop()/start(), so a colliding identity makes the child's
            // announce skip as "local destination" (full-suite flake).
            seed = ByteArray(64) { (it + 29).toByte() },
            myPeerId = "peerB"
        )
        session.start().getOrThrow()

        val linkDeadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < linkDeadline && tcpClient?.online?.value != true) {
            Thread.sleep(100)
        }
        assertTrue("parent TCP link must come online", tcpClient?.online?.value == true)
        session.reannounce()

        // 4. Baseline: child announces, sends "hello from child", we reply.
        val childPeerId = withTimeout(30_000) { session.peerSeen.first() }
        assertEquals("peerA", childPeerId)

        val baseline = async { withTimeout(30_000) { session.incoming.first() } }
        yield()
        val inbound = baseline.await()
        assertEquals("chat", inbound.type)
        assertEquals("hello from child", inbound.data.toString(Charsets.UTF_8))

        val sent = session.send("peerA", "hello from parent".encodeToByteArray(), "chat")
        assertTrue("baseline send must succeed: ${sent.exceptionOrNull()}", sent.isSuccess)

        // 5. Wait for the child's RECEIVED (baseline round-trip done).
        withTimeout(30_000) {
            while (true) {
                val line = childOut.readLine() ?: throw IllegalStateException("child exited early")
                if (line.startsWith("RECEIVED ")) break
                if (line.startsWith("CHILD_ERROR")) throw IllegalStateException("child failed: $line")
            }
        }

        // 6. Kill the link mid-conversation.
        val connectionsBefore = proxy!!.acceptedConnections
        proxy!!.kill()

        // 7. Wait for the client to reconnect (fresh connection through the
        //    proxy), then re-announce so the child learns our fresh path.
        val reconnectDeadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < reconnectDeadline &&
            (tcpClient?.online?.value != true || proxy!!.acceptedConnections <= connectionsBefore)
        ) {
            Thread.sleep(200)
        }
        assertTrue(
            "TCP client must reconnect after kill (accepted=${proxy!!.acceptedConnections}, before=$connectionsBefore)",
            tcpClient?.online?.value == true && proxy!!.acceptedConnections > connectionsBefore
        )
        session.reannounce()

        // 8. Post-flap signaling must still flow (S05/S06 invariant): the
        //    offer_status send returns success synchronously, the DIRECT link
        //    dies, the receipt times out, and the failed-delivery callback
        //    re-queues it — the next peer announce re-sends it over a fresh
        //    link. Chat is excluded (it rides the durable OfflineQueue).
        val sent2 = session.sendOfferStatus("peerA", "offer_1", "MATCHED", "peerB", "tb1qabc", "peerB")
        assertTrue("post-flap send must succeed: ${sent2.exceptionOrNull()}", sent2.isSuccess)

        val exitCode = withTimeout(60_000) { child!!.waitFor() }
        val output = childOut.readText()
        assertEquals("child must exit 0, output: $output", 0, exitCode)
        assertTrue("child must print RECEIVED2, got: $output", output.contains("RECEIVED2 peerB offer_status"))
    }
}
