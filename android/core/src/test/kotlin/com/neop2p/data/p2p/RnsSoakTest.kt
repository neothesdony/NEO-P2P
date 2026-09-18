package com.neop2p.data.p2p

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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
 * J2/J3 (2026-09-01): bounded soak over the two-process LXMF path.
 *
 * A child JVM ([RnsSoakServerMain]) and this parent run [ITERATIONS] chat +
 * offer_status round-trips. Between iterations the parent samples fd count
 * (/proc/self/fd) and heap; the asserts bound fd growth (leak) and heap growth
 * across the whole window. The window is bounded by [ITERATIONS] (accelerated
 * clock) so it runs in CI as a plain unit test; a longer soak is a manual
 * `main` with a larger iteration count.
 *
 * This is the "no soak instrumentation" UNKNOWN's seed: fd + heap sampled per
 * iteration, exception-free loop, clean exit.
 */
class RnsSoakTest {

    private lateinit var configDir: File
    private lateinit var session: RnsSession
    private var child: Process? = null
    private var tcp: TCPClientInterface? = null

    @Before
    fun setUp() {
        Reticulum.stop()
        configDir = Files.createTempDirectory("rns-soak-parent-").toFile()
    }

    @After
    fun tearDown() {
        runCatching { tcp?.detach() }
        runCatching { session.stop() }
        runCatching { Reticulum.stop() }
        child?.destroy()
        child?.waitFor(5, TimeUnit.SECONDS)
        configDir.deleteRecursively()
    }

    private fun fdCount(): Int = try {
        File("/proc/self/fd").listFiles()?.size ?: -1
    } catch (_: Exception) {
        -1
    }

    @Test
    fun `soak window shows flat fds and bounded heap`() = runBlocking {
        val port = 40000 + (System.currentTimeMillis() % 10000).toInt()
        val iterations = 6

        val javaBin = File(System.getProperty("java.home"), "bin/java").absolutePath
        val classpath = System.getProperty("java.class.path")
        val pb = ProcessBuilder(
            javaBin, "-cp", classpath,
            "com.neop2p.data.p2p.RnsSoakServerMainKt",
            port.toString(), "61", iterations.toString()
        )
        pb.redirectErrorStream(true)
        child = pb.start()
        val childOut = child!!.inputStream.bufferedReader()

        val readyLine = withTimeout(30_000) {
            var line: String?
            while (true) {
                line = childOut.readLine() ?: throw IllegalStateException("soak child exited early")
                if (line.startsWith("READY ")) break
                if (line.startsWith("CHILD_ERROR")) throw IllegalStateException("child failed: $line")
            }
            line
        }
        readyLine.removePrefix("READY ")

        Reticulum.start(configDir = configDir.absolutePath, enableTransport = true)
        tcp = TCPClientInterface(name = "SoakClient", targetHost = "127.0.0.1", targetPort = port)
        Transport.registerInterface(tcp!!.toRef())
        tcp!!.start()

        session = RnsSession(
            configDir = configDir.absolutePath,
            // UNIQUE seed: 67 free in the harness (7/23/29/31/37/41/47/53/59/61 taken).
            seed = ByteArray(64) { (it + 67).toByte() },
            myPeerId = "soakParent"
        )
        session.start().getOrThrow()

        val linkDeadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < linkDeadline && tcp?.online?.value != true) {
            Thread.sleep(100)
        }
        assertTrue("TCP link must come online", tcp?.online?.value == true)
        session.reannounce()

        // Wait for the child to announce, then reply to each of its soak
        // round-trips: every child chat gets a `soak-reply-<i>` chat AND an
        // offer_status (the child's ITER completes only after both). The
        // child's stdout is pumped in the background so Reticulum's logging
        // never fills the pipe and stalls the child's runBlocking dispatcher.
        val inbox = Channel<RnsSession.Inbound>(capacity = 64)
        val inboxJob = launch { session.incoming.collect { inbox.send(it) } }
        val childLines = Channel<String>(capacity = Channel.UNLIMITED)
        val pumpJob = launch(Dispatchers.IO) {
            while (true) {
                val line = childOut.readLine() ?: break
                childLines.trySend(line)
            }
        }
        val fdsByIter = mutableListOf<Int>()
        val heapByIter = mutableListOf<Long>()
        fdsByIter.add(fdCount())
        heapByIter.add(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())

        val childPeerId = withTimeout(30_000) { session.peerSeen.first() }
        assertEquals("soakA", childPeerId)

        // Reply loop: consume the child's `soak-<i>` chats, reply to each,
        // then wait for the child's ITER line to sample fds/heap.
        withTimeout(90_000) {
            var completed = 0
            while (completed < iterations) {
                val msg = inbox.receive()
                if (msg.type != "chat") continue
                val body = msg.data.toString(Charsets.UTF_8)
                if (!body.startsWith("soak-")) continue
                val i = body.removePrefix("soak-").toIntOrNull() ?: continue
                val replyOk = session.send(childPeerId, "soak-reply-$i".encodeToByteArray(), "chat")
                assertTrue("soak-reply-$i must send: ${replyOk.exceptionOrNull()}", replyOk.isSuccess)
                val sigOk = session.sendOfferStatus(childPeerId, "soak-$i", "MATCHED", "soakParent", "tb1qabc", "soakParent")
                assertTrue("soak signal must send: ${sigOk.exceptionOrNull()}", sigOk.isSuccess)
                // Wait for the child to print ITER i (its round-trip done).
                withTimeout(30_000) {
                    while (true) {
                        val line = childLines.receive()
                        if (line.startsWith("CHILD_ERROR")) throw IllegalStateException("child failed: $line")
                        if (line.startsWith("ITER ")) break
                    }
                }
                completed++
                // Sample fds + heap at each completed iteration.
                fdsByIter.add(fdCount())
                heapByIter.add(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
            }
        }
        inboxJob.cancel()

        // The child drove all iterations; assert exit clean + DONE_OK.
        val exit = withTimeout(60_000) { child!!.waitFor() }
        var doneOk = false
        withTimeoutOrNull(10_000) {
            while (true) {
                val line = childLines.receive()
                if (line.startsWith("SOAK_DONE")) {
                    doneOk = true
                    break
                }
            }
        }
        assertEquals("soak child must exit 0", 0, exit)
        assertTrue("soak child must print SOAK_DONE", doneOk)

        // J3 asserts: fd count flat across the window (no fd leak) and heap
        // growth bounded (the harness window is small; a real leak would show).
        val fdsPeak = fdsByIter.maxOrNull() ?: 0
        val fdsStart = fdsByIter.first()
        assertTrue("fd leak across soak: start=$fdsStart peak=$fdsPeak", fdsPeak <= fdsStart + 8)
        val heapStart = heapByIter.first()
        val heapPeak = heapByIter.maxOrNull() ?: 0
        val heapDeltaMb = (heapPeak - heapStart) / (1024 * 1024)
        assertTrue("heap grew ${heapDeltaMb}MB across soak window", heapDeltaMb < 256)
    }
}
