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
 * Child JVM for the RNS fault-injection test ([RnsFaultInjectionTest]).
 *
 * Same shape as [RnsTwoProcessServerMain] but waits for TWO inbound
 * messages: the baseline reply, then a post-flap message. Prints
 * `RECEIVED2 <peerId> <payload>` for the second one so the parent can
 * deterministically sequence the proxy kill between them.
 */
fun main(args: Array<String>) {
    val port = args[0].toInt()
    val configDir = Files.createTempDirectory("rns-flap-server-").toFile()
    try {
        Reticulum.start(configDir = configDir.absolutePath, enableTransport = true)
        val server = TCPServerInterface(name = "Srv", bindAddress = "127.0.0.1", bindPort = port)
        server.onClientConnected = { iface ->
            Transport.registerInterface(iface.toRef())
        }
        Transport.registerInterface(server.toRef())
        server.start()

        val session = RnsSession(
            configDir = configDir.absolutePath,
            // UNIQUE seed: RnsTwoProcessServerMain uses (it+7) — the parent
            // JVM's Transport keeps registered destinations across tests, so
            // a colliding identity would make the parent skip our announce as
            // a "local destination" (baseline timeout flake in full-suite runs).
            seed = ByteArray(64) { (it + 23).toByte() },
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
            try {
                val parentPeerId = withTimeout(30_000) { session.peerSeen.first() }
                val sent = session.send(parentPeerId, "hello from child".encodeToByteArray(), "chat")
                if (sent.isFailure) {
                    println("SEND_FAIL ${sent.exceptionOrNull()?.message}")
                    System.out.flush()
                    kotlin.system.exitProcess(2)
                }
                val baseline = withTimeout(30_000) { session.incoming.first() }
                println("RECEIVED ${baseline.fromPeerId} ${baseline.data.toString(Charsets.UTF_8)}")
                System.out.flush()
                // Second message arrives only after the proxy kill + reconnect.
                // The parent re-sends failed DIRECT signaling on the next
                // announce (fresh path) — this is the S05/S06 invariant.
                val postFlap = withTimeout(60_000) { session.incoming.first() }
                println("RECEIVED2 ${postFlap.fromPeerId} ${postFlap.type}")
                System.out.flush()
            } finally {
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
