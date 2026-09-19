package com.neop2p.admind.web

import com.neop2p.NeoP2PConfig
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * HTTP-boundary coverage for the loopback console: the token gate, the Host
 * allowlist (DNS rebinding), the JSON content-type requirement, the public
 * static page, and the health probe. Route logic lives in [ConsoleApiTest].
 */
class ConsoleServerTest {

    private val token = "test-token"

    @Before fun setUp() { NeoP2PConfig.network = "mainnet" }
    @After fun tearDown() { NeoP2PConfig.network = NeoP2PConfig.DEFAULT_NETWORK }

    private fun ApplicationTestBuilder.installConsole(api: () -> ConsoleApi = { error("not used here") }) {
        application {
            ConsoleServer.installConsole(this, token, "mainnet", "12D3KooWpeer", api)
        }
    }

    @Test fun `health requires the bearer token`() = testApplication {
        installConsole()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/health").status)
    }

    @Test fun `health rejects a wrong token`() = testApplication {
        installConsole()
        val res = client.get("/api/health") { header(HttpHeaders.Authorization, "Bearer nope") }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }

    @Test fun `health returns network and peerId with the token`() = testApplication {
        installConsole()
        val res = client.get("/api/health") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, res.status)
        val body = res.bodyAsText()
        assertTrue(body.contains("mainnet"))
        assertTrue(body.contains("12D3KooWpeer"))
    }

    @Test fun `a foreign Host header is refused (DNS rebinding)`() = testApplication {
        installConsole()
        val res = client.get("/") { header(HttpHeaders.Host, "evil.example") }
        assertEquals(HttpStatusCode.Forbidden, res.status)
    }

    @Test fun `the console page is served unauthenticated`() = testApplication {
        installConsole()
        val res = client.get("/")
        assertEquals(HttpStatusCode.OK, res.status)
        assertTrue(res.bodyAsText().contains("NEO-P2P"))
    }

    @Test fun `a cross-origin form content type is rejected`() = testApplication {
        installConsole()
        val res = client.post("/api/disputes/x/plan") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
            setBody("decision=RELEASE_TO_BUYER")
        }
        assertEquals(HttpStatusCode.UnsupportedMediaType, res.status)
    }

    @Test fun `an empty dispute store returns an empty list`() = testApplication {
        installConsole { emptyApi() }
        val res = client.get("/api/disputes") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("[]", res.bodyAsText())
    }

    @Test fun `an unknown dispute is a 404`() = testApplication {
        installConsole { emptyApi() }
        val res = client.get("/api/disputes/nope") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.NotFound, res.status)
    }

    @Test fun `plan maps a legacy dispute to 422 over http`() = testApplication {
        val fixture = DisputeFixture()
        installConsole {
            ConsoleApi(
                disputes = FakeDisputes(mutableMapOf(fixture.escrowId to fixture.record(buyerAddressAttestation = null))),
                evidence = FakeEvidence(),
                resolutions = FakeResolutions(),
                arbitratorPrivKeyHex = { error("unused") },
                senderProvider = { FakeSender() },
            )
        }
        val res = client.post("/api/disputes/${fixture.escrowId}/plan") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"decision":"RELEASE_TO_BUYER"}""")
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, res.status)
        assertTrue(res.bodyAsText().contains("not attested"))
    }

    @Test fun `resolve without confirm is a 400 over http`() = testApplication {
        installConsole {
            ConsoleApi(
                disputes = FakeDisputes(mutableMapOf("esc-1" to testRecord())),
                evidence = FakeEvidence(),
                resolutions = FakeResolutions(),
                arbitratorPrivKeyHex = { "arbiter-key" },
                senderProvider = { FakeSender() },
            )
        }
        val res = client.post("/api/disputes/esc-1/resolve") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"decision":"RELEASE_TO_BUYER"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }

    @Test fun `resolve returns the delivered view without the private key`() = testApplication {
        val fixture = DisputeFixture()
        installConsole {
            ConsoleApi(
                disputes = FakeDisputes(mutableMapOf(fixture.escrowId to fixture.record())),
                evidence = FakeEvidence(),
                resolutions = FakeResolutions(),
                arbitratorPrivKeyHex = { "arbiter-key" },
                senderProvider = { FakeSender() },
                sign = { _, _, _ -> Result.success("sig") },
            )
        }
        val res = client.post("/api/disputes/${fixture.escrowId}/resolve") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"decision":"RELEASE_TO_BUYER","confirm":true}""")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        val body = res.bodyAsText()
        assertTrue(body.contains("\"delivered\":true"))
        assertFalse(body.contains("arbiter-key"))
    }

    private fun emptyApi() = ConsoleApi(
        disputes = FakeDisputes(),
        evidence = FakeEvidence(),
        resolutions = FakeResolutions(),
        arbitratorPrivKeyHex = { error("unused") },
        senderProvider = { FakeSender() },
    )
}
