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
 * Child JVM for [RnsSoakTest] — the long-running soak harness (J2/J3,
 * 2026-09-01).
 *
 * Starts a real Reticulum transport + TCP server + [RnsSession] (peer "soakA"),
 * prints `READY`, waits for the parent, then loops [iterations] times:
 *
 *  1. sends `soak-<i>` chat to the parent,
 *  2. waits for the parent's `chat` reply,
 *  3. waits for the parent's `offer_status` (soak-<i> MATCHED).
 *
 * Prints `ITER <i>` after each completed round-trip so the parent can sample
 * fd/heap between iterations (accelerated-clock soak: the window is bounded by
 * the parent, not by wall-clock time). Exits 0 after the last iteration.
 *
 * Args: <port> <seedOffset> <iterations>
 */
fun main(args: Array<String>) {
    val port = args[0].toInt()
    val seedOffset = args[1].toInt()
    val iterations = args[2].toInt()
    val configDir = Files.createTempDirectory("rns-soak-server-").toFile()
    try {
        Reticulum.start(configDir = configDir.absolutePath, enableTransport = true)
        val server = TCPServerInterface(name = "Srv", bindAddress = "127.0.0.1", bindPort = port)
        server.onClientConnected = { iface -> Transport.registerInterface(iface.toRef()) }
        Transport.registerInterface(server.toRef())
        server.start()

        val session = RnsSession(
            configDir = configDir.absolutePath,
            seed = ByteArray(64) { (it + seedOffset).toByte() },
            myPeerId = "soakA"
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
            val inbox = kotlinx.coroutines.channels.Channel<RnsSession.Inbound>(capacity = 64)
            val collectJob = launch {
                session.incoming.collect { inbox.send(it) }
            }
            try {
                val parentPeerId = withTimeout(30_000) { session.peerSeen.first() }
                for (i in 0 until iterations) {
                    val sent = session.send(parentPeerId, "soak-$i".encodeToByteArray(), "chat")
                    if (sent.isFailure) {
                        println("SEND_FAIL ${sent.exceptionOrNull()?.message}")
                        System.out.flush()
                        kotlin.system.exitProcess(2)
                    }
                    // Classify inbound until this iteration's chat reply AND
                    // offer_status reply both arrive (LXMF order not guaranteed).
                    var gotChat = false
                    var gotSignal = false
                    withTimeout(30_000) {
                        while (!gotChat || !gotSignal) {
                            val msg = inbox.receive()
                            when {
                                msg.type == "chat" && msg.data.toString(Charsets.UTF_8) == "soak-reply-$i" ->
                                    gotChat = true
                                msg.type == "offer_status" ->
                                    gotSignal = true
                            }
                        }
                    }
                    println("ITER $i")
                    System.out.flush()
                }
                println("SOAK_DONE")
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
