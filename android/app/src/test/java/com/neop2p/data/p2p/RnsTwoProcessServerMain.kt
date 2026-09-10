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
    // Optional seed offset so multiple two-process tests can run in one JVM
    // without identity collisions (Transport keeps registered destinations
    // across stop()/start() — a colliding identity makes the parent skip the
    // child's announce as a "local destination"). Default 7 = legacy behavior.
    val seedOffset = if (args.size > 1) args[1].toInt() else 7
    // Optional peerId (default "peerA") so multi-child tests can give each
    // child a distinct identity (RnsThreePeerTest uses "peerC" for child B).
    val peerId = if (args.size > 2) args[2] else "peerA"
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
            seed = ByteArray(64) { (it + seedOffset).toByte() },
            myPeerId = peerId
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
                // Collect TWO inbound messages and classify by type — the
                // parent sends chat + offer_status back-to-back, and LXMF
                // delivery order is not guaranteed (a message queued while the
                // link establishes can land after a later one).
                var chat: RnsSession.Inbound? = null
                var signal: RnsSession.Inbound? = null
                withTimeout(60_000) {
                    while (chat == null || signal == null) {
                        val msg = inbox.receive()
                        when (msg.type) {
                            "chat" -> if (chat == null) chat = msg
                            "offer_status" -> if (signal == null) signal = msg
                        }
                    }
                }
                println("RECEIVED ${chat!!.fromPeerId} ${chat!!.data.toString(Charsets.UTF_8)}")
                System.out.flush()
                println("SIGNAL ${signal!!.type} ${signal!!.data.toString(Charsets.UTF_8)}")
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
