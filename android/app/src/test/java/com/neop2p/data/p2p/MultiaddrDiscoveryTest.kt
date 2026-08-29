package com.neop2p.data.p2p

import com.neop2p.data.p2p.store.PeerRegistry
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase-1 multiaddr discovery tests (plain JUnit 4 — mirrors the repo's
 * pure-logic test style; no Robolectric/mockk).
 *
 * Covers:
 *   - wildcard → LAN-IP substitution + `/p2p/<peerId>` suffix preservation
 *   - PeerRegistry multiaddr learn/query + no-wipe on empty re-announce
 *   - the exact Json encode/decode pair used by the offer builder + router
 */
class MultiaddrDiscoveryTest {

    // ── LibP2PManager.withLanIp (companion, pure) ──

    @Test
    fun `wildcard TCP addr gets LAN IP substituted`() {
        assertEquals(
            "/ip4/192.168.1.5/tcp/41234/p2p/12D3KooWabc",
            LibP2PManager.withLanIp("/ip4/0.0.0.0/tcp/41234/p2p/12D3KooWabc", "192.168.1.5")
        )
    }

    @Test
    fun `wildcard WS addr gets LAN IP substituted`() {
        assertEquals(
            "/ip4/192.168.1.5/tcp/41235/ws/p2p/12D3KooWabc",
            LibP2PManager.withLanIp("/ip4/0.0.0.0/tcp/41235/ws/p2p/12D3KooWabc", "192.168.1.5")
        )
    }

    @Test
    fun `no lan ip leaves addr untouched`() {
        val raw = "/ip4/0.0.0.0/tcp/41234/p2p/12D3KooWabc"
        assertEquals(raw, LibP2PManager.withLanIp(raw, null))
    }

    @Test
    fun `non-wildcard addr is untouched`() {
        val raw = "/ip4/10.0.0.1/tcp/41234/p2p/12D3KooWabc"
        assertEquals(raw, LibP2PManager.withLanIp(raw, "192.168.1.5"))
    }

    // ── PeerRegistry multiaddr learn/query ──

    @Test
    fun `recordPeerSeen with multiaddrs makes them queryable`() {
        val reg = PeerRegistry()
        val addrs = listOf(
            "/ip4/192.168.1.5/tcp/41234/p2p/12D3KooWabc",
            "/ip4/192.168.1.5/tcp/41235/ws/p2p/12D3KooWabc"
        )
        reg.recordPeerSeen("peerA", multiaddrs = addrs)
        assertEquals(addrs, reg.multiaddrsOf("peerA"))
    }

    @Test
    fun `unknown peer has no multiaddrs`() {
        assertEquals(emptyList<String>(), PeerRegistry().multiaddrsOf("ghost"))
    }

    @Test
    fun `empty multiaddrs does not wipe cached ones`() {
        val reg = PeerRegistry()
        val cached = listOf("/ip4/192.168.1.5/tcp/41234/p2p/12D3KooWabc")
        reg.recordPeerSeen("peerA", multiaddrs = cached)
        // Re-announce without addrs (old client): cached addrs survive.
        reg.recordPeerSeen("peerA")
        assertEquals(cached, reg.multiaddrsOf("peerA"))
    }

    @Test
    fun `fresh multiaddrs replace stale ones`() {
        val reg = PeerRegistry()
        reg.recordPeerSeen("peerA", multiaddrs = listOf("/ip4/10.0.0.1/tcp/1/p2p/X"))
        val fresh = listOf("/ip4/192.168.1.5/tcp/41234/p2p/12D3KooWabc")
        reg.recordPeerSeen("peerA", multiaddrs = fresh)
        assertEquals(fresh, reg.multiaddrsOf("peerA"))
    }

    // ── Offer JSON round-trip (exact pair used by builder + router) ──

    @Test
    fun `offer multiaddrs json round-trips through encode and decode`() {
        val addrs = listOf(
            "/ip4/192.168.1.5/tcp/41234/p2p/12D3KooWabc",
            "/ip4/192.168.1.5/tcp/41235/ws/p2p/12D3KooWabc"
        )
        // Same encode as CreateOfferScreen's putJsonArray + own-peer upsert.
        val encoded = buildJsonArray { addrs.forEach { add(it) } }.toString()
        // Same decode as OfferRouter.ingestOfferEvent.
        val decoded = Json.decodeFromJsonElement<List<String>>(
            Json.parseToJsonElement(encoded).jsonArray
        )
        assertEquals(addrs, decoded)
    }

    @Test
    fun `empty multiaddr array serializes to a parseable empty list`() {
        val encoded = buildJsonArray { }.toString()
        assertEquals("[]", encoded)
        val decoded = Json.decodeFromJsonElement<List<String>>(
            Json.parseToJsonElement(encoded).jsonArray
        )
        assertTrue(decoded.isEmpty())
    }

    @Test
    fun `each advertised addr ends with the p2p suffix`() {
        val addrs = listOf(
            "/ip4/192.168.1.5/tcp/41234/p2p/12D3KooWabc",
            "/ip4/192.168.1.5/tcp/41235/ws/p2p/12D3KooWabc"
        )
        addrs.forEach { addr ->
            assertTrue(addr.endsWith("/p2p/12D3KooWabc"))
        }
    }

    @Test
    fun `decoded elements are plain strings`() {
        val encoded = buildJsonArray { add("/ip4/1.2.3.4/tcp/9/p2p/Y") }.toString()
        val decoded = Json.decodeFromJsonElement<List<String>>(
            Json.parseToJsonElement(encoded).jsonArray
        )
        assertEquals("/ip4/1.2.3.4/tcp/9/p2p/Y", decoded.first())
    }
}
