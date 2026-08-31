package com.neop2p.data.p2p

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import network.reticulum.Reticulum
import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import network.reticulum.lxmf.LXMFConstants
import network.reticulum.lxmf.LXMessage
import network.reticulum.packet.Packet
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.msgpack.core.MessagePack
import java.io.ByteArrayOutputStream
import java.nio.file.Files

/**
 * In-JVM unit tests for [RnsSession] (RNS + LXMF transport core).
 *
 * Reticulum is a JVM-wide singleton, so peer-to-peer delivery cannot be
 * exercised in-process (two instances in one JVM is impossible). These tests
 * cover the router wiring that the app depends on:
 *
 *  - announce appData parsing (peerId <-> LXMF dest hash mapping)
 *  - send() building a DIRECT LXMF message to a known peer
 *  - inbound delivery: a packed LXMF message from a peer, fed through the
 *    delivery destination's packetCallback (the same path Transport.inbound
 *    takes in production), surfaces on [RnsSession.incoming] with the
 *    sender's peerId
 *  - file attachments surface on [RnsSession.receivedFiles]
 *
 * Peer-to-peer delivery over a real link is covered by the two-process
 * integration test (JVM A <-> JVM B over TCP).
 */
class RnsSessionTest {

    private lateinit var session: RnsSession
    private lateinit var configDir: java.io.File

    @Before
    fun setUp() {
        configDir = Files.createTempDirectory("rns-session-test-").toFile()
        session = RnsSession(
            configDir = configDir.absolutePath,
            seed = ByteArray(64) { it.toByte() },
            myPeerId = "12D3KooWTestPeer0000000000000000000000000000000000000000000000"
        )
        session.start().getOrThrow()
    }

    @After
    fun tearDown() {
        session.stop()
        configDir.deleteRecursively()
    }

    private fun packAnnounceAppData(displayName: String): ByteArray {
        val buffer = ByteArrayOutputStream()
        val packer = MessagePack.newDefaultPacker(buffer)
        packer.packArrayHeader(2)
        val nameBytes = displayName.toByteArray(Charsets.UTF_8)
        packer.packBinaryHeader(nameBytes.size)
        packer.writePayload(nameBytes)
        packer.packNil()
        packer.close()
        return buffer.toByteArray()
    }

    private fun peerIdentity(): Identity = Identity.create()

    private fun peerDestHash(peer: Identity): ByteArray =
        Destination.hashFromNameAndIdentity("lxmf.delivery", peer)

    /**
     * Register a synthetic peer announce (what Transport would deliver to the
     * announce handler after a real announce packet) and remember the peer's
     * public key so Identity.recall() works for outbound sends.
     */
    private fun registerPeer(peer: Identity, peerId: String): ByteArray {
        val destHash = peerDestHash(peer)
        Identity.remember(
            packetHash = ByteArray(32) { 0x11 },
            destHash = destHash,
            publicKey = peer.getPublicKey(),
            appData = null
        )
        session.handlePeerAnnounce(destHash, packAnnounceAppData(peerId))
        return destHash
    }

    /** Pack an LXMF message FROM [peer] TO our delivery destination. */
    private fun packMessageFrom(peer: Identity, title: String, data: ByteArray): ByteArray {
        val sourceDest = Destination.create(
            identity = peer,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "lxmf",
            "delivery"
        )
        val ourDest = session.deliveryDestination()!!
        val destDest = Destination.create(
            identity = ourDest.identity,
            direction = DestinationDirection.OUT,
            type = DestinationType.SINGLE,
            appName = "lxmf",
            "delivery"
        )
        val msg = LXMessage.create(
            destination = destDest,
            source = sourceDest,
            content = "",
            title = title,
            fields = mutableMapOf(LXMFConstants.FIELD_CUSTOM_DATA to data),
            desiredMethod = network.reticulum.lxmf.DeliveryMethod.OPPORTUNISTIC
        )
        return msg.pack()
    }

    /** Feed a packed message through our delivery destination's packetCallback. */
    private fun deliverToSession(packed: ByteArray) {
        val ourDest = session.deliveryDestination()!!
        val afterDestHash = packed.copyOfRange(LXMFConstants.DESTINATION_LENGTH, packed.size)
        val packet = Packet.createRaw(
            destinationHash = ourDest.hash,
            data = afterDestHash,
            destinationType = DestinationType.SINGLE
        )
        // packet.destination is `internal` in rns-core — set via reflection so
        // packet.prove() (called by handleDeliveryPacket) can find our identity.
        val field = Packet::class.java.getDeclaredField("destination")
        field.isAccessible = true
        field.set(packet, ourDest)
        val callback = ourDest.packetCallback
        assertNotNull("delivery destination must have a packet callback", callback)
        callback!!.invoke(afterDestHash, packet)
    }

    @Test
    fun `announce appData maps peerId to LXMF dest hash`() {
        val peer = peerIdentity()
        val peerId = "12D3KooWPeerA"
        val destHash = registerPeer(peer, peerId)

        assertEquals(destHash.toHexString(), session.destHashOf(peerId))
        assertEquals(peerId, session.peerIdOfDestHash(destHash.toHexString()))
        assertTrue(session.knownPeers().contains(peerId))
        assertTrue(session.lastSeen(peerId) > 0)
    }

    @Test
    fun `send to known peer queues a DIRECT LXMF message`() = runBlocking {
        val peer = peerIdentity()
        val peerId = "12D3KooWPeerB"
        registerPeer(peer, peerId)

        val payload = "hello over lxmf".encodeToByteArray()
        val result = session.send(peerId, payload, "chat")
        assertTrue("send must succeed for a known peer: ${result.exceptionOrNull()}", result.isSuccess)
    }

    @Test
    fun `send to unknown peer fails fast`() = runBlocking {
        val result = session.send("12D3KooWNeverAnnounced", "x".encodeToByteArray(), "chat")
        assertTrue("send to an unknown peer must fail (no path/identity)", result.isFailure)
    }

    @Test
    fun `inbound LXMF message surfaces with sender peerId`() = runBlocking {
        val peer = peerIdentity()
        val peerId = "12D3KooWPeerC"
        registerPeer(peer, peerId)

        val payload = "{\"type\":\"chat\",\"hello\":1}".encodeToByteArray()
        val packed = packMessageFrom(peer, "chat", payload)
        // Collect BEFORE delivering: the shared flow has replay=0, so an
        // emission with no active collector is dropped. yield() lets the
        // async collector subscribe before the synchronous delivery below.
        val deferred = async { withTimeout(10_000) { session.incoming.first() } }
        yield()
        deliverToSession(packed)

        val inbound = deferred.await()
        assertEquals("chat", inbound.type)
        assertEquals(peerId, inbound.fromPeerId)
        assertTrue(inbound.data.contentEquals(payload))
    }

    @Test
    fun `inbound file attachment surfaces on receivedFiles`() = runBlocking {
        val peer = peerIdentity()
        val peerId = "12D3KooWPeerD"
        registerPeer(peer, peerId)

        // Keep the packed message under the 500-byte packet MTU — the point
        // is attachment-field parsing, not Resource transfer (that is covered
        // by the two-process integration test).
        val fileData = ByteArray(200) { (it % 251).toByte() }
        val sourceDest = Destination.create(
            identity = peer,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "lxmf",
            "delivery"
        )
        val ourDest = session.deliveryDestination()!!
        val destDest = Destination.create(
            identity = ourDest.identity,
            direction = DestinationDirection.OUT,
            type = DestinationType.SINGLE,
            appName = "lxmf",
            "delivery"
        )
        val msg = LXMessage.create(
            destination = destDest,
            source = sourceDest,
            content = "",
            title = "file",
            fields = mutableMapOf(
                LXMFConstants.FIELD_FILE_ATTACHMENTS to
                    listOf(listOf("receipt.jpg".toByteArray(Charsets.UTF_8), fileData))
            ),
            desiredMethod = network.reticulum.lxmf.DeliveryMethod.OPPORTUNISTIC
        )
        val deferred = async { withTimeout(10_000) { session.receivedFiles.first() } }
        yield()
        deliverToSession(msg.pack())

        val file = deferred.await()
        assertEquals(peerId, file.fromPeerId)
        assertEquals("receipt.jpg", file.fileName)
        assertTrue(file.data.contentEquals(fileData))
    }

    @Test
    fun `isDirect is false without an established link`() {
        val peer = peerIdentity()
        val peerId = "12D3KooWPeerE"
        registerPeer(peer, peerId)
        // No link has been established in this in-JVM test (no interfaces),
        // so isDirect must be honest: false.
        assertTrue(!session.isDirect(peerId))
    }

    @Test
    fun `offer announce with matching identity maps to peerId`() = runBlocking {
        val peer = peerIdentity()
        val peerId = "12D3KooWPeerF"
        registerPeer(peer, peerId)

        val digest = RnsOfferDigest.encode(
            com.neop2p.domain.model.TradeOffer(
                offerId = "offer_1",
                creatorPeerId = peerId,
                type = com.neop2p.domain.model.OfferType.SELL,
                fiatAmount = 1_000_000L,
                cryptoAmountSats = 100_000L,
                pricePerUnit = 10_000_000.0,
                feeSats = 500L,
                fiatMethods = listOf("bca"),
                status = com.neop2p.domain.model.OfferStatus.OPEN,
                createdAt = System.currentTimeMillis()
            )
        )
        val deferred = async { withTimeout(10_000) { session.offerAnnounces.first() } }
        yield()
        session.handleOfferAnnounce(
            Destination.hashFromNameAndIdentity("neop2p.offers", peer),
            peer,
            digest.toByteArray(Charsets.UTF_8)
        )

        val announce = deferred.await()
        assertEquals(peerId, announce.fromPeerId)
        assertEquals(digest, announce.digestJson)
    }

    @Test
    fun `offer announce with unknown identity is dropped`() = runBlocking {
        val peer = peerIdentity()
        val peerId = "12D3KooWPeerG"
        registerPeer(peer, peerId)
        val stranger = peerIdentity()

        val digest = RnsOfferDigest.encode(
            com.neop2p.domain.model.TradeOffer(
                offerId = "offer_2",
                creatorPeerId = peerId,
                type = com.neop2p.domain.model.OfferType.SELL,
                fiatAmount = 1_000_000L,
                cryptoAmountSats = 100_000L,
                pricePerUnit = 10_000_000.0,
                feeSats = 500L,
                fiatMethods = listOf("bca"),
                status = com.neop2p.domain.model.OfferStatus.OPEN,
                createdAt = System.currentTimeMillis()
            )
        )
        // A stranger's identity (never announced via lxmf.delivery) must not
        // map to any peerId — the announce is dropped.
        session.handleOfferAnnounce(
            Destination.hashFromNameAndIdentity("neop2p.offers", stranger),
            stranger,
            digest.toByteArray(Charsets.UTF_8)
        )
        // Give any (wrong) emission a moment to surface.
        Thread.sleep(200)
        assertTrue(session.offerAnnounces.replayCache.isEmpty())
    }

    @Test
    fun `publishOffer announces the offers destination with digest appData`() {
        val digest = RnsOfferDigest.encode(
            com.neop2p.domain.model.TradeOffer(
                offerId = "offer_3",
                creatorPeerId = "12D3KooWPeerH",
                type = com.neop2p.domain.model.OfferType.SELL,
                fiatAmount = 1_000_000L,
                cryptoAmountSats = 100_000L,
                pricePerUnit = 10_000_000.0,
                feeSats = 500L,
                fiatMethods = listOf("bca"),
                status = com.neop2p.domain.model.OfferStatus.OPEN,
                createdAt = System.currentTimeMillis()
            )
        )
        val result = session.publishOffer(digest)
        assertTrue("publishOffer must succeed: ${result.exceptionOrNull()}", result.isSuccess)
    }

    @Test
    fun `signaling sends to known peer queue DIRECT LXMF messages`() = runBlocking {
        val peer = peerIdentity()
        val peerId = "12D3KooWPeerI"
        registerPeer(peer, peerId)

        val status = session.sendOfferStatus(peerId, "offer_4", "MATCHED", "12D3KooWPeerI", "tb1qabc", "12D3KooWPeerI")
        assertTrue("offer_status send must succeed: ${status.exceptionOrNull()}", status.isSuccess)

        val escrow = session.sendEscrowStatus(peerId, "escrow_1", "FUNDED", mapOf("funding_tx_id" to "abc"))
        assertTrue("escrow_status send must succeed: ${escrow.exceptionOrNull()}", escrow.isSuccess)

        val dispute = session.sendDispute(peerId, "escrow_1", peerId, "scam", mapOf("psbt_hex" to "deadbeef"))
        assertTrue("dispute send must succeed: ${dispute.exceptionOrNull()}", dispute.isSuccess)

        val resolution = session.sendResolution(peerId, "escrow_1", "RELEASE_TO_BUYER", "sig", "notes", "tb1qrefund", "txhex")
        assertTrue("resolution send must succeed: ${resolution.exceptionOrNull()}", resolution.isSuccess)

        val evidence = session.sendEvidence(peerId, "escrow_1", peerId, "receipt", "image/jpeg", ByteArray(200) { 1 })
        assertTrue("evidence send must succeed: ${evidence.exceptionOrNull()}", evidence.isSuccess)

        val request = session.sendOfferRequest(peerId, "offer_4")
        assertTrue("offer_request send must succeed: ${request.exceptionOrNull()}", request.isSuccess)

        val offer = session.sendOffer(peerId, "{\"offer_id\":\"offer_4\"}")
        assertTrue("offer send must succeed: ${offer.exceptionOrNull()}", offer.isSuccess)
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
}
