package com.neop2p.data.p2p

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
 * B2 (2026-09-01): three-peer topology — one parent, two child JVMs.
 *
 * The parent runs TWO TCP client interfaces (one per child server) and one
 * RnsSession. Both children announce; the parent must discover BOTH peers,
 * exchange chat with both, and deliver LXMF signaling to both — proving the
 * transport handles multiple simultaneous peers over separate links.
 *
 * Reuses RnsTwoProcessServerMain (standard RECEIVED/SIGNAL protocol) with
 * unique seed offsets (7 and 31) and disjoint port ranges.
 */
class RnsThreePeerTest {

    private lateinit var configDir: File
    private lateinit var session: RnsSession
    private var childA: Process? = null
    private var childB: Process? = null
    private var tcpA: TCPClientInterface? = null
    private var tcpB: TCPClientInterface? = null

    @Before
    fun setUp() {
        Reticulum.stop()
        configDir = Files.createTempDirectory("rns-3peer-parent-").toFile()
    }

    @After
    fun tearDown() {
        runCatching { tcpA?.detach() }
        runCatching { tcpB?.detach() }
        runCatching { session.stop() }
        runCatching { Reticulum.stop() }
        childA?.destroy()
        childB?.destroy()
        childA?.waitFor(5, TimeUnit.SECONDS)
        childB?.waitFor(5, TimeUnit.SECONDS)
        configDir.deleteRecursively()
    }

    private suspend fun startChild(port: Int, seedOffset: String, peerId: String): Pair<Process, java.io.BufferedReader> {
        val javaBin = File(System.getProperty("java.home"), "bin/java").absolutePath
        val classpath = System.getProperty("java.class.path")
        val pb = ProcessBuilder(
            javaBin, "-cp", classpath,
            "com.neop2p.data.p2p.RnsTwoProcessServerMainKt", port.toString(), seedOffset, peerId
        )
        pb.redirectErrorStream(true)
        val proc = pb.start()
        val reader = proc.inputStream.bufferedReader()
        val readyLine = withTimeout(30_000) {
            var line: String?
            while (true) {
                line = reader.readLine() ?: throw IllegalStateException("child exited early")
                if (line.startsWith("READY ")) break
                if (line.startsWith("CHILD_ERROR")) throw IllegalStateException("child failed: $line")
            }
            line
        }
        readyLine.removePrefix("READY ")
        return proc to reader
    }

    @Test
    fun `one parent discovers and trades with two peers simultaneously`() = runBlocking {
        // Disjoint port ranges: A in 20000-39999, B in 50000-64999.
        val portA = 20000 + (System.currentTimeMillis() % 20000).toInt()
        val portB = 50000 + (System.currentTimeMillis() % 15000).toInt()

        // 1. Start both child JVMs (real Reticulum + TCP servers). Child B
        //    announces as "peerC" (distinct identity per child).
        val (procA, outA) = startChild(portA, "7", "peerA")
        childA = procA
        val (procB, outB) = startChild(portB, "31", "peerC")
        childB = procB

        // 2. Parent: Reticulum + RnsSession (peerB) with TWO TCP clients.
        Reticulum.start(configDir = configDir.absolutePath, enableTransport = true)
        tcpA = TCPClientInterface(name = "ClientA", targetHost = "127.0.0.1", targetPort = portA)
        Transport.registerInterface(tcpA!!.toRef())
        tcpA!!.start()
        tcpB = TCPClientInterface(name = "ClientB", targetHost = "127.0.0.1", targetPort = portB)
        Transport.registerInterface(tcpB!!.toRef())
        tcpB!!.start()

        session = RnsSession(
            configDir = configDir.absolutePath,
            // Unique seed: 13 (integration parent), 29 (flap parent), 37 (latency) taken.
            seed = ByteArray(64) { (it + 41).toByte() },
            myPeerId = "peerB"
        )
        session.start().getOrThrow()

        val linkDeadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < linkDeadline &&
            (tcpA?.online?.value != true || tcpB?.online?.value != true)
        ) {
            Thread.sleep(100)
        }
        assertTrue("both TCP links must come online", tcpA?.online?.value == true && tcpB?.online?.value == true)
        session.reannounce()

        // 3. Discover BOTH peers. peerSeen is replay=0 — one channel collector
        //    never drops an emission between sequential first() calls.
        val seen = Channel<String>(capacity = 8)
        val seenJob = launch { session.peerSeen.collect { seen.send(it) } }
        val peers = mutableSetOf<String>()
        withTimeout(60_000) {
            while (peers.size < 2) {
                peers.add(seen.receive())
            }
        }
        seenJob.cancel()
        assertTrue("must discover peerA, got $peers", peers.contains("peerA"))
        assertTrue("must discover peerC, got $peers", peers.contains("peerC"))

        // 4. Both children send "hello from child" — collect both.
        val inbox = Channel<RnsSession.Inbound>(capacity = 16)
        val inboxJob = launch { session.incoming.collect { inbox.send(it) } }
        val hellos = mutableMapOf<String, String>()
        withTimeout(60_000) {
            while (hellos.size < 2) {
                val msg = inbox.receive()
                if (msg.type == "chat") hellos[msg.fromPeerId] = msg.data.toString(Charsets.UTF_8)
            }
        }
        assertEquals("hello from child", hellos["peerA"])
        assertEquals("hello from child", hellos["peerC"])

        // 5. Reply to both + deliver LXMF signaling to both.
        for (peerId in listOf("peerA", "peerC")) {
            val sent = session.send(peerId, "hello from parent".encodeToByteArray(), "chat")
            assertTrue("send to $peerId must succeed: ${sent.exceptionOrNull()}", sent.isSuccess)
            val status = session.sendOfferStatus(peerId, "offer_1", "MATCHED", "peerB", "tb1qabc", "peerB")
            assertTrue("offer_status to $peerId must succeed: ${status.exceptionOrNull()}", status.isSuccess)
        }
        inboxJob.cancel()

        // 6. Both children print RECEIVED + SIGNAL and exit 0.
        val exitA = withTimeout(60_000) { childA!!.waitFor() }
        val outAtext = outA.readText()
        assertEquals("child A must exit 0, output: $outAtext", 0, exitA)
        assertTrue("child A must print RECEIVED, got: $outAtext", outAtext.contains("RECEIVED peerB hello from parent"))
        assertTrue("child A must print SIGNAL, got: $outAtext", outAtext.contains("SIGNAL offer_status"))

        val exitB = withTimeout(60_000) { childB!!.waitFor() }
        val outBtext = outB.readText()
        assertEquals("child B must exit 0, output: $outBtext", 0, exitB)
        assertTrue("child B must print RECEIVED, got: $outBtext", outBtext.contains("RECEIVED peerB hello from parent"))
        assertTrue("child B must print SIGNAL, got: $outBtext", outBtext.contains("SIGNAL offer_status"))
    }
}
