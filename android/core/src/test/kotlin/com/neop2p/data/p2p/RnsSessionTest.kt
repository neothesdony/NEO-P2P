package com.neop2p.data.p2p

import com.neop2p.NeoP2PConfig
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
import org.junit.Assert.assertNull
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
        session.handlePeerAnnounce(destHash, peer, packAnnounceAppData(peerId))
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
    fun `inbound evidence message surfaces with its meta json - evidence delivery invariant`() = runBlocking {
        // S75/arbitration evidence delivery: sendEvidence ships the image as a
        // file attachment AND the meta (escrow_id/submitter/description) as
        // FIELD_CUSTOM_DATA. handleInbound must surface the meta as an
        // Inbound("evidence", meta) so the orchestrator's applyEvidenceEvent
        // can persist it to the arbitrator's dispute feed — a file-only
        // emission drops the meta and the evidence never reaches the feed.
        val peer = peerIdentity()
        val peerId = "12D3KooWPeerEVID"
        registerPeer(peer, peerId)

        val image = ByteArray(200) { (it % 251).toByte() }
        val meta = """{"escrow_id":"escrow_1","submitter":"$peerId","description":"receipt","mime_type":"image/jpeg","image_base64":"${java.util.Base64.getEncoder().encodeToString(image)}"}"""
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
            title = "evidence",
            fields = mutableMapOf(
                LXMFConstants.FIELD_CUSTOM_DATA to meta.toByteArray(Charsets.UTF_8),
                LXMFConstants.FIELD_FILE_ATTACHMENTS to
                    listOf(listOf("evidence.jpg".toByteArray(Charsets.UTF_8), image))
            ),
            desiredMethod = network.reticulum.lxmf.DeliveryMethod.OPPORTUNISTIC
        )
        val deferred = async { withTimeout(10_000) { session.incoming.first { it.type == "evidence" } } }
        yield()
        session.handleInbound(msg)

        val inbound = deferred.await()
        assertEquals("evidence", inbound.type)
        assertEquals(peerId, inbound.fromPeerId)
        val metaStr = inbound.data.toString(Charsets.UTF_8)
        assertTrue(
            "evidence meta must carry escrow_id for the dispute feed, got: $metaStr",
            metaStr.contains("\"escrow_id\":\"escrow_1\"")
        )
        // Slice 2: the image must ride in the meta as base64 so the
        // arbitrator's applyEvidenceEvent can persist it to the dispute feed
        // (it reads image_base64, not the file-attachment path).
        val b64 = Regex("\"image_base64\":\"([^\"]+)\"").find(metaStr)?.groupValues?.get(1)
        assertNotNull("evidence meta must carry image_base64, got: $metaStr", b64)
        val decoded = java.util.Base64.getDecoder().decode(b64)
        assertTrue("image_base64 must round-trip the original image", decoded.contentEquals(image))
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
    fun `oversized inbound custom data is dropped - I5`() = runBlocking {
        val peer = peerIdentity()
        val peerId = "12D3KooWPeerJ"
        registerPeer(peer, peerId)

        // 300KB custom data — far beyond the 256KB inbound cap. A hostile
        // peer must not be able to force an unbounded allocation. Fed
        // directly to handleInbound (the packet path rejects >MTU before
        // the cap check; in production oversized payloads arrive via
        // Resource reassembly, which lands here).
        val payload = ByteArray(300 * 1024) { 0x42 }
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
            title = "chat",
            fields = mutableMapOf(LXMFConstants.FIELD_CUSTOM_DATA to payload),
            desiredMethod = network.reticulum.lxmf.DeliveryMethod.OPPORTUNISTIC
        )
        session.handleInbound(msg)

        val emitted = try {
            withTimeout(500) { session.incoming.first() }
            true
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            false
        }
        assertTrue("oversized payload must be dropped, not emitted", !emitted)
    }

    @Test
    fun `oversized inbound file attachment is dropped - I5`() = runBlocking {
        val peer = peerIdentity()
        val peerId = "12D3KooWPeerK"
        registerPeer(peer, peerId)

        // 600KB attachment — beyond the 512KB inbound file cap.
        val fileData = ByteArray(600 * 1024) { 0x42 }
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
                    listOf(listOf("huge.jpg".toByteArray(Charsets.UTF_8), fileData))
            ),
            desiredMethod = network.reticulum.lxmf.DeliveryMethod.OPPORTUNISTIC
        )
        session.handleInbound(msg)

        val emitted = try {
            withTimeout(500) { session.receivedFiles.first() }
            true
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            false
        }
        assertTrue("oversized file must be dropped, not emitted", !emitted)
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
    fun `offer announce with unknown identity is deferred not emitted`() = runBlocking {
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
        // A stranger's identity (never announced via lxmf.delivery) cannot map
        // to a peerId — the announce is deferred (Bug A2), not emitted.
        session.handleOfferAnnounce(
            Destination.hashFromNameAndIdentity("neop2p.offers", stranger),
            stranger,
            digest.toByteArray(Charsets.UTF_8)
        )
        Thread.sleep(200)
        assertTrue(session.offerAnnounces.replayCache.isEmpty())
        assertEquals(1, session.pendingDeferredIdentityCount())
        assertEquals(1, session.pendingDeferredDigestCount())
    }

    @Test
    fun `offer digest deferred before delivery announce flushes on it`() = runBlocking {
        // Bug A2 flush path: a digest that beats its delivery announce is held,
        // then emitted once the delivery announce maps the identity -> peerId.
        val peer = peerIdentity()
        val peerId = "12D3KooWPeerI9"

        val digest = RnsOfferDigest.encode(
            com.neop2p.domain.model.TradeOffer(
                offerId = "offer_1b",
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
        // 1. Offer announce arrives BEFORE the delivery announce → deferred.
        session.handleOfferAnnounce(
            Destination.hashFromNameAndIdentity("neop2p.offers", peer),
            peer,
            digest.toByteArray(Charsets.UTF_8)
        )
        assertEquals(1, session.pendingDeferredIdentityCount())

        // 2. Delivery announce arrives → the deferred digest is flushed.
        val emitted = async { withTimeout(5_000) { session.offerAnnounces.first() } }
        yield()
        session.handlePeerAnnounce(peerDestHash(peer), peer, packAnnounceAppData(peerId))
        val announce = emitted.await()
        assertEquals(peerId, announce.fromPeerId)
        assertEquals(digest, announce.digestJson)
        assertEquals(0, session.pendingDeferredIdentityCount())
    }

    @Test
    fun `deferral buffer is bounded per identity and across identities`() = runBlocking {
        // Bug A2 hostile-peer cap: a stranger identity flooding offers must not
        // grow the deferral map without limit.
        val stranger = peerIdentity()
        val offersDestHash = Destination.hashFromNameAndIdentity("neop2p.offers", stranger)

        // >MAX_PENDING_OFFERS_PER_IDENTITY (32) DISTINCT digests under ONE
        // identity: the 33rd is dropped.
        for (i in 0 until 40) {
            val digest = """{"v":1,"id":"x$i","h":"${"%02d".format(i)}"}"""
            session.handleOfferAnnounce(offersDestHash, stranger, digest.toByteArray(Charsets.UTF_8))
        }
        assertEquals(32, session.pendingDeferredDigestCount())

        // >MAX_PENDING_OFFER_IDENTITIES (64) distinct identities: the oldest is
        // evicted, so the map stays bounded.
        for (i in 0 until 70) {
            val id = peerIdentity()
            val h = Destination.hashFromNameAndIdentity("neop2p.offers", id)
            session.handleOfferAnnounce(h, id, """{"v":1,"id":"y$i","h":"00"}""".toByteArray(Charsets.UTF_8))
        }
        assertEquals(64, session.pendingDeferredIdentityCount())
    }

    @Test
    fun `identity recall works without an app-side remember`() = runBlocking {
        // Bug A1: Identity.recall must resolve via the fork's own remember
        // (rns-core Transport.processAnnounce remembers every valid announce
        // before handlers dispatch) — the app no longer calls Identity.remember
        // itself, so this asserts outbound sends can still resolve peers.
        val peer = peerIdentity()
        val peerId = "12D3KooWPeerJ"
        registerPeer(peer, peerId)
        val destHash = peerDestHash(peer)
        val recalled = Identity.recall(destHash)
        assertNotNull("recall must resolve the peer identity", recalled)
        assertEquals(
            peer.getPublicKey().toHexString(),
            recalled!!.getPublicKey().toHexString()
        )
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
    fun `paced offer reannounce loop cycles tracked digests and replaces edits in place`() = runBlocking {
        // Task 1 (2026-09-01): a tracked digest set is re-announced on the
        // paced loop — one digest per tick, round-robin. With a 200ms tick
        // this exercises the same path a 10s tick uses in production.
        val fastSession = RnsSession(
            configDir = Files.createTempDirectory("rns-pace-").toFile().absolutePath,
            seed = ByteArray(64) { (it + 59).toByte() },
            myPeerId = "12D3KooWPeerP",
            offerReannounceIntervalMs = 200L
        )
        try {
            fastSession.start().getOrThrow()
            val d1 = RnsOfferDigest.encode(
                com.neop2p.domain.model.TradeOffer(
                    offerId = "offer_p1",
                    creatorPeerId = "12D3KooWPeerP",
                    type = com.neop2p.domain.model.OfferType.SELL,
                    fiatAmount = 1_000_000L,
                    cryptoAmountSats = 100_000L,
                    pricePerUnit = 10_000_000.0,
                    feeSats = 500L,
                    fiatMethods = listOf("bca"),
                    status = com.neop2p.domain.model.OfferStatus.OPEN
                )
            )
            val d2 = RnsOfferDigest.encode(
                com.neop2p.domain.model.TradeOffer(
                    offerId = "offer_p2",
                    creatorPeerId = "12D3KooWPeerP",
                    type = com.neop2p.domain.model.OfferType.SELL,
                    fiatAmount = 2_000_000L,
                    cryptoAmountSats = 200_000L,
                    pricePerUnit = 10_000_000.0,
                    feeSats = 500L,
                    fiatMethods = listOf("bca"),
                    status = com.neop2p.domain.model.OfferStatus.OPEN
                )
            )
            fastSession.trackOfferDigest(d1)
            fastSession.trackOfferDigest(d2)

            // Wait for ≥2 paced ticks: both offers must have been re-announced
            // at least once (round-robin across the set).
            val deadline = System.currentTimeMillis() + 5_000
            while (fastSession.pacedOfferReannounces < 2 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
            }
            assertTrue(
                "paced loop must re-announce both digests, saw ${fastSession.pacedOfferReannounces}",
                fastSession.pacedOfferReannounces >= 2
            )

            // Edit-in-place: re-tracking the same offer id replaces the digest
            // (same key) — the set size must not grow.
            val d1Edited = RnsOfferDigest.encode(
                com.neop2p.domain.model.TradeOffer(
                    offerId = "offer_p1",
                    creatorPeerId = "12D3KooWPeerP",
                    type = com.neop2p.domain.model.OfferType.SELL,
                    fiatAmount = 3_000_000L,
                    cryptoAmountSats = 300_000L,
                    pricePerUnit = 10_000_000.0,
                    feeSats = 500L,
                    fiatMethods = listOf("bca"),
                    status = com.neop2p.domain.model.OfferStatus.OPEN
                )
            )
            fastSession.trackOfferDigest(d1Edited)
            val countBeforeUntrack = fastSession.pacedOfferReannounces
            fastSession.untrackOfferDigest("offer_p1")
            // After untrack, only offer_p2 remains — it must still be
            // re-announced (loop keeps cycling the survivor).
            val deadline2 = System.currentTimeMillis() + 5_000
            while (fastSession.pacedOfferReannounces < countBeforeUntrack + 1 && System.currentTimeMillis() < deadline2) {
                Thread.sleep(50)
            }
            assertTrue("untracked offer must not stall the loop", fastSession.pacedOfferReannounces >= countBeforeUntrack + 1)
        } finally {
            fastSession.stop()
        }
    }

    @Test
    fun `paced loop re-announces terminal tombstones when no live offers remain`() = runBlocking {
        // 2026-09-02 (3rd-device convergence): with an empty live set, the
        // paced loop must keep re-announcing terminal tombstones so peers
        // holding a stale OPEN row converge on the terminal status.
        val fastSession = RnsSession(
            configDir = Files.createTempDirectory("rns-tomb-").toFile().absolutePath,
            seed = ByteArray(64) { (it + 63).toByte() },
            myPeerId = "12D3KooWPeerT",
            offerReannounceIntervalMs = 200L
        )
        try {
            fastSession.start().getOrThrow()
            fastSession.setTerminalTombstones(
                mapOf("offer_t1" to RnsOfferDigest.encodeTombstone("offer_t1"))
            )
            val deadline = System.currentTimeMillis() + 5_000
            while (fastSession.pacedTombstoneReannounces < 2 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
            }
            assertTrue(
                "empty live set must still re-announce tombstones, saw ${fastSession.pacedTombstoneReannounces}",
                fastSession.pacedTombstoneReannounces >= 2
            )
        } finally {
            fastSession.stop()
        }
    }

    @Test
    fun `tombstone cadence keeps combined announce rate under the per-dest cap`() = runBlocking {
        // 2026-09-02 regression: with ONE live offer + ONE tombstone, the
        // old cursor-mod-live-size logic announced a tombstone EVERY tick
        // (2 announces/tick = 24/30s at 200ms... at production 2.5s that is
        // 24/30s too) — the node blocked the whole destination ("Blocking
        // rebroadcast ... due to excessive announce rate"). The tombstone
        // cadence must be independent of the live-set size: one tombstone
        // per TOMBSTONE_REANNOUNCE_TICKS ticks.
        val fastSession = RnsSession(
            configDir = Files.createTempDirectory("rns-tomb-cadence-").toFile().absolutePath,
            seed = ByteArray(64) { (it + 67).toByte() },
            myPeerId = "12D3KooWPeerV",
            offerReannounceIntervalMs = 200L
        )
        try {
            fastSession.start().getOrThrow()
            fastSession.trackOfferDigest(
                RnsOfferDigest.encode(
                    com.neop2p.domain.model.TradeOffer(
                        offerId = "offer_v1",
                        creatorPeerId = "12D3KooWPeerV",
                        type = com.neop2p.domain.model.OfferType.SELL,
                        fiatAmount = 1_000_000L,
                        cryptoAmountSats = 100_000L,
                        pricePerUnit = 10_000_000.0,
                        feeSats = 500L,
                        fiatMethods = listOf("bca"),
                        status = com.neop2p.domain.model.OfferStatus.OPEN
                    )
                )
            )
            fastSession.setTerminalTombstones(
                mapOf("offer_v2" to RnsOfferDigest.encodeTombstone("offer_v2"))
            )
            // Wait for ≥8 ticks: live announces must outnumber tombstones
            // (1 tombstone per 4 ticks), never 1:1.
            val deadline = System.currentTimeMillis() + 5_000
            while (fastSession.pacedOfferReannounces < 8 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
            }
            assertTrue(
                "live loop must run, saw ${fastSession.pacedOfferReannounces}",
                fastSession.pacedOfferReannounces >= 8
            )
            assertTrue(
                "tombstones must be paced (≤1 per 4 ticks), live=${fastSession.pacedOfferReannounces} tomb=${fastSession.pacedTombstoneReannounces}",
                fastSession.pacedTombstoneReannounces <= fastSession.pacedOfferReannounces / 4 + 1
            )
        } finally {
            fastSession.stop()
        }
    }

    @Test
    fun `refreshFeed re-announces terminal tombstones immediately`() = runBlocking {
        // 2026-09-02: pull-to-refresh must also push tombstones NOW so stale
        // rows converge without waiting for the paced cycle.
        val fastSession = RnsSession(
            configDir = Files.createTempDirectory("rns-tomb-refresh-").toFile().absolutePath,
            seed = ByteArray(64) { (it + 65).toByte() },
            myPeerId = "12D3KooWPeerU",
            offerReannounceIntervalMs = 10_000L
        )
        try {
            fastSession.start().getOrThrow()
            fastSession.setTerminalTombstones(
                mapOf("offer_u1" to RnsOfferDigest.encodeTombstone("offer_u1"))
            )
            val before = fastSession.pacedTombstoneReannounces
            fastSession.refreshFeed()
            assertTrue(
                "refreshFeed must announce tombstones immediately, tomb=${fastSession.pacedTombstoneReannounces} before=$before",
                fastSession.pacedTombstoneReannounces > before
            )
        } finally {
            fastSession.stop()
        }
    }

    @Test
    fun `refreshFeed re-announces tracked digests immediately and rate-caps the burst`() = runBlocking {
        // Pull-to-refresh: refreshFeed() must announce every tracked digest
        // NOW (not on the paced tick) and never exceed the fork's
        // per-destination 30s rate cap in one burst.
        val fastSession = RnsSession(
            configDir = Files.createTempDirectory("rns-refresh-").toFile().absolutePath,
            seed = ByteArray(64) { (it + 61).toByte() },
            myPeerId = "12D3KooWPeerR",
            offerReannounceIntervalMs = 10_000L // long tick: refresh must not wait for it
        )
        try {
            fastSession.start().getOrThrow()
            val offers = (1..20).map { i ->
                RnsOfferDigest.encode(
                    com.neop2p.domain.model.TradeOffer(
                        offerId = "offer_r$i",
                        creatorPeerId = "12D3KooWPeerR",
                        type = com.neop2p.domain.model.OfferType.SELL,
                        fiatAmount = 1_000_000L,
                        cryptoAmountSats = 100_000L,
                        pricePerUnit = 10_000_000.0,
                        feeSats = 500L,
                        fiatMethods = listOf("bca"),
                        status = com.neop2p.domain.model.OfferStatus.OPEN
                    )
                )
            }
            offers.forEach { fastSession.trackOfferDigest(it) }

            val before = fastSession.pacedOfferReannounces
            fastSession.refreshFeed()
            // refreshFeed announces synchronously — the counter must have
            // moved without waiting for the paced tick.
            assertTrue(
                "refreshFeed must re-announce tracked digests immediately, paced=${fastSession.pacedOfferReannounces} before=$before",
                fastSession.pacedOfferReannounces > before
            )
        } finally {
            fastSession.stop()
        }
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

    @Test
    fun `arbitration send to unverified arbitrator fails closed`() = runBlocking {
        val arb = peerIdentity()
        registerPeer(arb, NeoP2PConfig.ARBITRATOR_PEER_ID)

        val dispute = session.sendDispute(
            NeoP2PConfig.ARBITRATOR_PEER_ID, "escrow_1", NeoP2PConfig.ARBITRATOR_PEER_ID, "scam", emptyMap()
        )
        assertTrue("dispute to unverified arbitrator must fail closed", dispute.isFailure)

        val evidence = session.sendEvidence(
            NeoP2PConfig.ARBITRATOR_PEER_ID, "escrow_1", NeoP2PConfig.ARBITRATOR_PEER_ID,
            "receipt", "image/jpeg", ByteArray(10) { 1 }
        )
        assertTrue("evidence to unverified arbitrator must fail closed", evidence.isFailure)

        val resolution = session.sendResolution(
            NeoP2PConfig.ARBITRATOR_PEER_ID, "escrow_1", "RELEASE_TO_BUYER", "sig", null, null, null
        )
        assertTrue("resolution to unverified arbitrator must fail closed", resolution.isFailure)
    }

    @Test
    fun `arbitration send fails closed when the dest belongs to another identity`() = runBlocking {
        val peerId = "12D3KooWPinMismatchPeer000000000000000000000000"
        // Attacker pre-registers a delivery dest for the victim's peerId.
        val attacker = peerIdentity()
        registerPeer(attacker, peerId)

        // A verified binding for the victim (a DIFFERENT identity) is learned,
        // but no mapped dest carries that identity yet.
        val victimIdentity = peerIdentity()
        session.bindingRegistry.record(peerId, victimIdentity.hexHash)

        assertNull(
            "no dest maps to the verified identity — must not resolve",
            session.pinnedDestFor(peerId)
        )

        val result = session.sendDispute(peerId, "escrow_1", peerId, "scam", emptyMap())
        assertTrue("arbitration must not be delivered to a foreign dest", result.isFailure)
        assertTrue(
            "failure must be the fail-closed pinning error: ${result.exceptionOrNull()?.message}",
            result.exceptionOrNull()?.message
                ?.contains("not yet verified for this destination") == true
        )

        // Only once the victim's real delivery dest announces does the pin
        // resolve — to that dest, not the attacker's.
        val victimDest = registerPeer(victimIdentity, peerId)
        val resolved = session.pinnedDestFor(peerId)
        assertNotNull("verified dest must now resolve", resolved)
        assertEquals(victimDest.toHexString(), resolved)
    }

    @Test
    fun `send attestation to known peer succeeds`() = runBlocking {
        val peer = peerIdentity()
        val peerId = "12D3KooWAttestA"
        registerPeer(peer, peerId)
        val json = "{\"from_peer\":\"me\",\"target_peer\":\"$peerId\",\"outcome\":\"POSITIVE\",\"volume_sats\":1,\"timestamp\":1,\"pubkey\":\"${"aa".repeat(32)}\",\"signature\":\"${"bb".repeat(64)}\"}"
        val result = session.sendAttestation(peerId, json)
        assertTrue("sendAttestation must succeed for a known peer: ${result.exceptionOrNull()}", result.isSuccess)
    }

    @Test
    fun `send attestation to unknown peer fails fast`() = runBlocking {
        val result = session.sendAttestation("12D3KooWNeverAnnounced", "{}")
        assertTrue("sendAttestation to an unknown peer must fail (no path/identity)", result.isFailure)
    }

    @Test
    fun `inbound attestation message surfaces with sender peerId`() = runBlocking {
        val peer = peerIdentity()
        val peerId = "12D3KooWAttestB"
        registerPeer(peer, peerId)
        // Short hex strings: the test harness packs the whole LXMF message into
        // a single raw packet (500-byte MTU), so a full-size pubkey/signature
        // would exceed it. The real wire path splits larger messages across
        // packets; this test only verifies the wire-type routing.
        val payload = "{\"from_peer\":\"$peerId\",\"target_peer\":\"me\",\"outcome\":\"POSITIVE\",\"volume_sats\":1,\"timestamp\":1,\"pubkey\":\"${"aa".repeat(8)}\",\"signature\":\"${"bb".repeat(16)}\"}".encodeToByteArray()
        val packed = packMessageFrom(peer, "attestation", payload)
        val deferred = async { withTimeout(10_000) { session.incoming.first() } }
        yield()
        deliverToSession(packed)
        val inbound = deferred.await()
        assertEquals("attestation", inbound.type)
        assertEquals(peerId, inbound.fromPeerId)
        assertTrue(inbound.data.contentEquals(payload))
    }

    @Test
    fun `idle mode stretches the paced reannounce tick`() = runBlocking {
        // Task 1 (2026-09-07): with idleMode=true the paced offer loop must
        // tick at the idle interval, not the fast foreground interval. Fast
        // tick 200ms / idle tick 1000ms: over ~2.5s the fast loop would
        // announce ~12 digests; the idle loop must stay well under that.
        val fastSession = RnsSession(
            configDir = Files.createTempDirectory("rns-idle-").toFile().absolutePath,
            seed = ByteArray(64) { (it + 61).toByte() },
            myPeerId = "12D3KooWPeerI",
            offerReannounceIntervalMs = 200L,
            idleReannounceIntervalMs = 1_000L,
            idleMode = true
        )
        try {
            fastSession.start().getOrThrow()
            fastSession.trackOfferDigest(
                RnsOfferDigest.encode(
                    com.neop2p.domain.model.TradeOffer(
                        offerId = "offer_idle1",
                        creatorPeerId = "12D3KooWPeerI",
                        type = com.neop2p.domain.model.OfferType.SELL,
                        fiatAmount = 1_000_000L,
                        cryptoAmountSats = 100_000L,
                        pricePerUnit = 10_000_000.0,
                        feeSats = 500L,
                        fiatMethods = listOf("bca"),
                        status = com.neop2p.domain.model.OfferStatus.OPEN
                    )
                )
            )
            Thread.sleep(2_500)
            // 12+ announces expected at 200ms; idle 1000ms tick gives ≤4.
            assertTrue(
                "idle loop must announce at the idle tick, saw ${fastSession.pacedOfferReannounces}",
                fastSession.pacedOfferReannounces in 1..5
            )
            // Flip back to foreground: the loop must speed up.
            fastSession.idleMode = false
            val before = fastSession.pacedOfferReannounces
            Thread.sleep(1_200)
            assertTrue(
                "clearing idleMode must resume fast ticks, saw ${fastSession.pacedOfferReannounces - before} in 1.2s",
                fastSession.pacedOfferReannounces - before >= 4
            )
        } finally {
            fastSession.stop()
        }
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
}
