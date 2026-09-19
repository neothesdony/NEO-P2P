package com.neop2p.admind.web

import com.neop2p.NeoP2PConfig
import com.neop2p.data.p2p.DisputeRecord
import com.neop2p.data.p2p.DisputeStore
import com.neop2p.data.p2p.EvidenceStore
import com.neop2p.data.p2p.ResolutionSender
import com.neop2p.data.p2p.ResolutionStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Rule-layer coverage for [ConsoleApi]; the HTTP boundary is in [ConsoleServerTest]. */
class ConsoleApiTest {

    @Before fun setUp() { NeoP2PConfig.network = "mainnet" }
    @After fun tearDown() { NeoP2PConfig.network = NeoP2PConfig.DEFAULT_NETWORK }

    private fun api(
        disputes: DisputeStore,
        evidence: EvidenceStore = FakeEvidence(),
        resolutions: ResolutionStore = FakeResolutions(),
        sender: ResolutionSender = FakeSender(),
        keyHex: String? = null,
        sign: (DisputeRecord, String, String) -> Result<String> = { _, _, _ -> Result.success("sig") },
    ) = ConsoleApi(
        disputes = disputes,
        evidence = evidence,
        resolutions = resolutions,
        arbitratorPrivKeyHex = { keyHex ?: error("key must not be read by a read-only call") },
        senderProvider = { sender },
        sign = sign,
    )

    @Test fun `an empty store lists nothing`() {
        assertTrue(api(FakeDisputes()).list().isEmpty())
    }

    @Test fun `a stored dispute lists with evidence count and tx flags`() {
        val disputes = FakeDisputes(mutableMapOf("esc-1" to testRecord()))
        val evidence = FakeEvidence(mutableListOf(testEvidence()))
        val row = api(disputes, evidence).list().single()
        assertEquals("esc-1", row.escrowId)
        assertEquals("not received", row.reason)
        assertEquals(1, row.evidenceCount)
        assertTrue(row.hasPayoutTx)
        assertEquals(false, row.hasRefundTx)
        assertEquals(listOf("buyer-peer", "seller-peer"), row.targets)
    }

    @Test fun `detail inlines evidence as base64`() {
        val disputes = FakeDisputes(mutableMapOf("esc-1" to testRecord()))
        val evidence = FakeEvidence(mutableListOf(testEvidence()))
        val detail = api(disputes, evidence).detail("esc-1").getOrThrow()
        assertEquals("AQIDBA==", detail.evidence.single().imageBase64)
        assertEquals("buyer-addr", detail.buyerBtcAddress)
        assertEquals("seller-addr", detail.sellerRefundAddress)
    }

    @Test fun `detail on an unknown escrow is a 404 refusal`() {
        val failure = api(FakeDisputes()).detail("nope").exceptionOrNull()
        assertEquals(404, (failure as Refusal).status)
    }

    @Test fun `plan previews the verified release destinations`() {
        val fixture = DisputeFixture()
        val disputes = FakeDisputes(mutableMapOf(fixture.escrowId to fixture.record()))
        val view = api(disputes).plan(fixture.escrowId, "RELEASE_TO_BUYER").getOrThrow()
        assertEquals("RELEASE_TO_BUYER", view.decision)
        assertTrue(view.outputs.any { it.contains(fixture.buyerAddr) })
        assertEquals(listOf("buyer-peer", "seller-peer"), view.targets)
    }

    @Test fun `plan rejects an unknown decision`() {
        val fixture = DisputeFixture()
        val disputes = FakeDisputes(mutableMapOf(fixture.escrowId to fixture.record()))
        val failure = api(disputes).plan(fixture.escrowId, "bogus").exceptionOrNull() as Refusal
        assertEquals(400, failure.status)
    }

    @Test fun `plan on an unknown escrow is a 404`() {
        val failure = api(FakeDisputes()).plan("nope", "RELEASE_TO_BUYER").exceptionOrNull() as Refusal
        assertEquals(404, failure.status)
    }

    @Test fun `plan refuses a legacy dispute with no attestation`() {
        val fixture = DisputeFixture()
        val legacy = fixture.record(buyerAddressAttestation = null)
        val disputes = FakeDisputes(mutableMapOf(fixture.escrowId to legacy))
        val failure = api(disputes).plan(fixture.escrowId, "RELEASE_TO_BUYER").exceptionOrNull() as Refusal
        assertEquals(422, failure.status)
        assertTrue(failure.message.contains("not attested by the buyer key"))
    }

    @Test fun `resolve without confirm is a 400 and never signs`() = runBlocking {
        var signed = false
        val disputes = FakeDisputes(mutableMapOf("esc-1" to testRecord()))
        val failure = api(disputes, keyHex = "arbiter-key", sign = { _, _, _ ->
            signed = true; Result.success("sig")
        }).resolve("esc-1", "RELEASE_TO_BUYER", null, confirm = false).exceptionOrNull() as Refusal
        assertEquals(400, failure.status)
        assertFalse(signed)
    }

    @Test fun `resolve on an unknown escrow is a 404`() = runBlocking {
        val failure = api(FakeDisputes(), keyHex = "arbiter-key")
            .resolve("nope", "RELEASE_TO_BUYER", null, confirm = true).exceptionOrNull() as Refusal
        assertEquals(404, failure.status)
    }

    @Test fun `resolve on an already-resolved dispute is a 409`() = runBlocking {
        val disputes = FakeDisputes(mutableMapOf("esc-1" to testRecord(resolved = true)))
        val failure = api(disputes, keyHex = "arbiter-key")
            .resolve("esc-1", "RELEASE_TO_BUYER", null, confirm = true).exceptionOrNull() as Refusal
        assertEquals(409, failure.status)
    }

    @Test fun `resolve signs delivers and reports the public artifacts`() = runBlocking {
        val fixture = DisputeFixture()
        val disputes = FakeDisputes(mutableMapOf(fixture.escrowId to fixture.record()))
        val resolutions = FakeResolutions()
        val sender = FakeSender()
        val view = api(disputes, resolutions = resolutions, sender = sender, keyHex = "arbiter-key")
            .resolve(fixture.escrowId, "RELEASE_TO_BUYER", "note", confirm = true).getOrThrow()
        assertTrue(view.delivered)
        assertEquals("sig", view.arbitratorSigHex)
        assertEquals(listOf("buyer-peer", "seller-peer"), sender.sent)
        assertEquals(listOf(fixture.escrowId), disputes.resolvedIds)
        assertTrue(resolutions.rows.isEmpty())
    }

    @Test fun `resolve reports a partial delivery as 502 and keeps the failed target`() = runBlocking {
        val fixture = DisputeFixture()
        val disputes = FakeDisputes(mutableMapOf(fixture.escrowId to fixture.record()))
        val resolutions = FakeResolutions()
        val sender = FakeSender().apply { failing = setOf("seller-peer") }
        val failure = api(disputes, resolutions = resolutions, sender = sender, keyHex = "arbiter-key")
            .resolve(fixture.escrowId, "RELEASE_TO_BUYER", null, confirm = true).exceptionOrNull() as Refusal
        assertEquals(502, failure.status)
        assertEquals(listOf("seller-peer"), resolutions.rows[fixture.escrowId]?.targets)
        assertTrue(disputes.resolvedIds.isEmpty())
    }

    @Test fun `a resolved dispute cannot be resolved twice`() = runBlocking {
        val fixture = DisputeFixture()
        val disputes = FakeDisputes(mutableMapOf(fixture.escrowId to fixture.record()))
        val console = api(disputes, keyHex = "arbiter-key")
        console.resolve(fixture.escrowId, "RELEASE_TO_BUYER", null, confirm = true).getOrThrow()
        val failure = console.resolve(fixture.escrowId, "RELEASE_TO_BUYER", null, confirm = true)
            .exceptionOrNull() as Refusal
        assertEquals(409, failure.status)
    }
}
