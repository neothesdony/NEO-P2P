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
 * Two-process RNS/LXMF integration test (JVM A <-> JVM B over TCP).
 *
 * Reticulum is a JVM-wide singleton, so real peer-to-peer delivery cannot
 * run in one JVM. This test spawns a child JVM ([RnsTwoProcessServerMain])
 * that runs its own Reticulum instance (transport mode) with a TCP server
 * interface, and connects the parent (this test JVM) as a TCP client.
 *
 * Flow:
 *  1. child starts, prints READY, re-announces every 2s
 *  2. parent starts its own Reticulum + RnsSession (peerB), connects TCP
 *  3. child learns the parent's announce, sends "hello from child"
 *  4. parent receives it, replies "hello from parent"
 *  5. child prints RECEIVED peerB hello from parent and exits 0
 *
 * The child JVM runs on the same JDK 21 as the test launcher
 * (System.getProperty("java.home")), so no toolchain lookup is needed.
 */
class RnsTwoProcessIntegrationTest {

    private lateinit var configDir: File
    private lateinit var session: RnsSession
    private var child: Process? = null
    private var tcpClient: TCPClientInterface? = null

    @Before
    fun setUp() {
        // Deterministic start: tear down any Reticulum left by earlier tests.
        Reticulum.stop()
        configDir = Files.createTempDirectory("rns-int-parent-").toFile()
    }

    @After
    fun tearDown() {
        runCatching { tcpClient?.detach() }
        runCatching { session.stop() }
        runCatching { Reticulum.stop() }
        child?.destroy()
        child?.waitFor(5, TimeUnit.SECONDS)
        configDir.deleteRecursively()
    }

    @Test
    fun `peer to peer chat over TCP`() = runBlocking {
        val port = 20000 + (System.currentTimeMillis() % 20000).toInt()

        // 1. Start the child JVM (real Reticulum + TCP server).
        val javaBin = File(System.getProperty("java.home"), "bin/java").absolutePath
        val classpath = System.getProperty("java.class.path")
        val pb = ProcessBuilder(
            javaBin, "-cp", classpath,
            "com.neop2p.data.p2p.RnsTwoProcessServerMainKt", port.toString()
        )
        pb.redirectErrorStream(true)
        child = pb.start()
        val childOut = child!!.inputStream.bufferedReader()

        // 2. Wait for the child's READY line.
        val readyLine = withTimeout(30_000) {
            var line: String?
            while (true) {
                line = childOut.readLine() ?: throw IllegalStateException("child exited early")
                if (line.startsWith("READY ")) break
                if (line.startsWith("CHILD_ERROR")) throw IllegalStateException("child failed: $line")
            }
            line
        }
        val childDestHex = readyLine.removePrefix("READY ")

        // 3. Parent: start Reticulum + RnsSession (peerB), connect TCP client.
        Reticulum.start(configDir = configDir.absolutePath, enableTransport = true)
        tcpClient = TCPClientInterface(
            name = "ParentClient",
            targetHost = "127.0.0.1",
            targetPort = port
        )
        Transport.registerInterface(tcpClient!!.toRef())
        tcpClient!!.start()

        session = RnsSession(
            configDir = configDir.absolutePath,
            // Unique seed (see RnsTwoProcessServerMain — must not collide with
            // the child's identity or the parent's Transport would skip the
            // child's announce as a "local destination").
            seed = ByteArray(64) { (it + 13).toByte() },
            myPeerId = "peerB"
        )
        session.start().getOrThrow()

        // The parent announces ONCE at startup — if the TCP link is not up
        // yet, that announce is broadcast on 0 interfaces and lost, and the
        // child (which only re-announces every 2s, never learns the parent)
        // would time out waiting for our announce. Wait for the link, then
        // re-announce so the child learns our path.
        val linkDeadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < linkDeadline && tcpClient?.online?.value != true) {
            Thread.sleep(100)
        }
        session.reannounce()

        // 4. Wait for the child's announce (peerA) and its message.
        val childPeerId = withTimeout(30_000) { session.peerSeen.first() }
        assertEquals("peerA", childPeerId)

        val deferred = async { withTimeout(30_000) { session.incoming.first() } }
        yield()
        val inbound = deferred.await()
        assertEquals("chat", inbound.type)
        assertEquals("peerA", inbound.fromPeerId)
        assertEquals("hello from child", inbound.data.toString(Charsets.UTF_8))

        // 5. Reply — the child prints RECEIVED and exits 0.
        val sent = session.send("peerA", "hello from parent".encodeToByteArray(), "chat")
        assertTrue("parent send must succeed: ${sent.exceptionOrNull()}", sent.isSuccess)

        // 6. Phase 3: send an offer_status over the same link — the child
        // prints SIGNAL and exits 0.
        val status = session.sendOfferStatus("peerA", "offer_1", "MATCHED", "peerB", "tb1qabc", "peerB")
        assertTrue("offer_status send must succeed: ${status.exceptionOrNull()}", status.isSuccess)

        val exitCode = withTimeout(30_000) { child!!.waitFor() }
        assertEquals("child must exit 0", 0, exitCode)
        val output = childOut.readText()
        assertTrue("child must print RECEIVED, got: $output", output.contains("RECEIVED peerB hello from parent"))
        assertTrue("child must print SIGNAL, got: $output", output.contains("SIGNAL offer_status"))
    }
}
