package com.neop2p.data.p2p

import com.neop2p.domain.model.OfferStatus
import com.neop2p.domain.model.OfferType
import com.neop2p.domain.model.TradeOffer
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
 * Child JVM for [RnsLoadTest] — the offer-feed load harness (J1/J2/J3, 2026-09-01).
 *
 * Starts a real Reticulum transport with a TCP server interface + an [RnsSession]
 * (peer "flooder"), prints `READY`, waits for the parent to announce, then
 * publishes [offerCount] offer digests on the `neop2p/offers` destination
 * (one `dest.announce` per offer — the same one-shot mechanism the app's
 * create-offer path uses). Stays alive re-announcing every 2s until the parent
 * signals completion via a `done` chat message, then exits 0.
 *
 * Args: <port> <seedOffset> <offerCount>
 */
fun main(args: Array<String>) {
    val port = args[0].toInt()
    val seedOffset = args[1].toInt()
    val offerCount = args[2].toInt()
    val configDir = Files.createTempDirectory("rns-load-server-").toFile()
    try {
        Reticulum.start(configDir = configDir.absolutePath, enableTransport = true)
        val server = TCPServerInterface(name = "Srv", bindAddress = "127.0.0.1", bindPort = port)
        server.onClientConnected = { iface -> Transport.registerInterface(iface.toRef()) }
        Transport.registerInterface(server.toRef())
        server.start()

        val session = RnsSession(
            configDir = configDir.absolutePath,
            seed = ByteArray(64) { (it + seedOffset).toByte() },
            myPeerId = "flooder"
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
                // Wait for the parent to announce so our announces reach it.
                withTimeout(30_000) { session.peerSeen.first() }

                // Publish the offer flood at a paced cadence — the production
                // cadence (Bug B, 2026-09-01): the fork (a) drops same-second
                // re-announces to one destination (second-granular announce
                // timebase) and (b) rate-limits to MAX_RATE_TIMESTAMPS=16
                // announces per destination per 30s. 2500ms = 12/30s — 25%
                // headroom; 1500ms dropped 8× in the load test, 2000ms was
                // clean, 2500ms is the app's production tick.
                val digests = (0 until offerCount).map { i ->
                    val offer = TradeOffer(
                        offerId = "offer_flood_$i",
                        creatorPeerId = "flooder",
                        type = OfferType.SELL,
                        fiatAmount = 1_000_000L + i,
                        cryptoAmountSats = 50_000L + i,
                        pricePerUnit = 20_000_000.0,
                        fiatMethods = listOf("bca"),
                        status = OfferStatus.OPEN
                    )
                    RnsOfferDigest.encode(offer, "flooder")
                }
                for (digest in digests) {
                    session.publishOffer(digest).getOrThrow()
                    delay(2500)
                }
                println("FLOODED $offerCount")
                System.out.flush()

                // Stay alive until the parent signals done (a `done` chat).
                withTimeout(120_000) {
                    while (true) {
                        val msg = inbox.receive()
                        if (msg.type == "done") break
                    }
                }
                println("DONE_OK")
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
