package com.neop2p.data.p2p

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import network.reticulum.Reticulum
import network.reticulum.interfaces.tcp.TCPServerInterface
import network.reticulum.interfaces.toRef
import network.reticulum.transport.Transport
import java.nio.file.Files

/**
 * Child JVM for the two-process RNS/LXMF integration test.
 *
 * Started by [RnsTwoProcessIntegrationTest] via ProcessBuilder. Runs a real
 * Reticulum instance (transport mode) with a TCP server interface, hosts an
 * [RnsSession] (peer "peerA"), and:
 *
 *  1. prints `READY <destHashHex>` once the session is up,
 *  2. re-announces every 2s so a late-starting parent still learns us,
 *  3. waits for the parent's announce, sends `hello from child` to it,
 *  4. waits for the parent's reply and prints `RECEIVED <peerId> <payload>`,
 *  5. exits 0 on success, non-zero on failure.
 */
fun main(args: Array<String>) {
    val port = args[0].toInt()
    val configDir = Files.createTempDirectory("rns-int-server-").toFile()
    try {
        Reticulum.start(configDir = configDir.absolutePath, enableTransport = true)
        val server = TCPServerInterface(name = "Srv", bindAddress = "127.0.0.1", bindPort = port)
        // Spawned per-client interfaces must be registered with Transport or
        // the server's outbound broadcasts never reach connected clients.
        server.onClientConnected = { iface ->
            Transport.registerInterface(iface.toRef())
        }
        Transport.registerInterface(server.toRef())
        server.start()

        val session = RnsSession(
            configDir = configDir.absolutePath,
            // Unique seed: RnsSessionTest uses ByteArray(64){it.toByte()} and
            // Transport keeps registered destinations across stop()/start() in
            // the same JVM, so a colliding identity would make the parent skip
            // our announce as a "local destination".
            seed = ByteArray(64) { (it + 7).toByte() },
            myPeerId = "peerA"
        )
        session.start().getOrThrow()
        println("READY ${session.myDestHashHex()}")
        System.out.flush()

        runBlocking {
            val reannounceJob = launch {
                while (true) {
                    delay(2000)
                    session.reannounce()
                }
            }
            // Buffered collector: _incoming is a SharedFlow(replay=0) — a
            // message arriving between two sequential first() calls is
            // DROPPED (no collector active), which made the SIGNAL step
            // flaky. One collector feeding a channel never drops.
            val inbox = kotlinx.coroutines.channels.Channel<RnsSession.Inbound>(capacity = 16)
            val collectJob = launch {
                session.incoming.collect { inbox.send(it) }
            }
            try {
                val parentPeerId = withTimeout(30_000) { session.peerSeen.first() }
                val sent = session.send(parentPeerId, "hello from child".encodeToByteArray(), "chat")
                if (sent.isFailure) {
                    println("SEND_FAIL ${sent.exceptionOrNull()?.message}")
                    System.out.flush()
                    kotlin.system.exitProcess(2)
                }
                val inbound = withTimeout(30_000) { inbox.receive() }
                println("RECEIVED ${inbound.fromPeerId} ${inbound.data.toString(Charsets.UTF_8)}")
                System.out.flush()
                // Phase 3: exercise the signaling channel over a real link —
                // the parent replies with an offer_status; the child must
                // receive it and print it.
                val status = withTimeout(30_000) { inbox.receive() }
                println("SIGNAL ${status.type} ${status.data.toString(Charsets.UTF_8)}")
                System.out.flush()
            } finally {
                collectJob.cancel()
                reannounceJob.cancel()
            }
        }
        session.stop()
        server.detach()
        kotlin.system.exitProcess(0)
    } catch (e: Exception) {
        println("CHILD_ERROR ${e.message}")
        System.out.flush()
        kotlin.system.exitProcess(1)
    } finally {
        configDir.deleteRecursively()
    }
}
