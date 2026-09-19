package com.neop2p.admind.web

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.host
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The loopback host of the arbitrator console.
 *
 * Security model (see docs/superpowers/specs/...arbitrator-admin-console-design):
 *  - binds 127.0.0.1 only — reachable remotely only through an SSH tunnel;
 *  - a random per-process bearer token, printed once in the URL fragment so it
 *    never reaches a server log or a Referer header;
 *  - the `Host` header must be loopback, which defeats DNS rebinding from a
 *    page the operator may visit in the same browser;
 *  - no cookies (so no CSRF surface) and no CORS headers;
 *  - POSTs must be `application/json`, so a cross-origin HTML form cannot
 *    trigger a state change.
 */
object ConsoleServer {

    const val DEFAULT_PORT = 8787
    private val ALLOWED_HOSTS = setOf("127.0.0.1", "localhost", "::1")

    /** 32 SecureRandom bytes, base64url without padding (~43 chars). */
    fun newToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun consoleUrl(port: Int, token: String): String = "http://127.0.0.1:$port/#token=$token"

    fun installConsole(
        app: Application,
        token: String,
        network: String,
        peerId: String,
        api: () -> ConsoleApi,
    ) {
        app.install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }

        // One gate for every request so a new route cannot be added outside it.
        app.install(
            createApplicationPlugin("ConsoleGuard") {
                onCall { call ->
                    if (hostNameOf(call) !in ALLOWED_HOSTS) {
                        call.respond(HttpStatusCode.Forbidden, ErrorView("forbidden host"))
                        return@onCall
                    }
                    if (!call.request.local.uri.startsWith("/api/")) return@onCall

                    val provided = call.request.headers[HttpHeaders.Authorization] ?: ""
                    if (!constantTimeEquals(provided, "Bearer $token")) {
                        call.respond(HttpStatusCode.Unauthorized, ErrorView("unauthorized"))
                        return@onCall
                    }

                    val contentType = call.request.headers[HttpHeaders.ContentType]
                        ?.substringBefore(';')?.trim()?.lowercase()
                    if (call.request.local.method == HttpMethod.Post && contentType != JSON) {
                        call.respond(HttpStatusCode.UnsupportedMediaType, ErrorView("application/json required"))
                        return@onCall
                    }
                }
            }
        )

        app.routing {
            // The page itself is public: it carries no dispute data, and the
            // token arrives in the fragment, which the browser never sends.
            get("/") {
                val html = ConsoleServer::class.java.getResourceAsStream("/console/index.html")
                    ?.bufferedReader()?.use { it.readText() }
                    ?: "<h1>NEO-P2P console asset missing</h1>"
                call.respondText(html, ContentType.Text.Html)
            }
            route("/api") {
                get("/health") { call.respond(HealthView("ok", network, peerId)) }
                get("/disputes") { call.respond(api().list()) }
                get("/disputes/{id}") {
                    val id = call.parameters["id"].orEmpty()
                    api().detail(id)
                        .onSuccess { call.respond(it) }
                        .onFailure { respondRefusal(call, it) }
                }
                post("/disputes/{id}/plan") {
                    val id = call.parameters["id"].orEmpty()
                    api().plan(id, call.receive<PlanRequest>().decision)
                        .onSuccess { call.respond(it) }
                        .onFailure { respondRefusal(call, it) }
                }
                post("/disputes/{id}/resolve") {
                    val id = call.parameters["id"].orEmpty()
                    val body = call.receive<ResolveRequest>()
                    api().resolve(id, body.decision, body.notes, body.confirm)
                        .onSuccess { call.respond(it) }
                        .onFailure { respondRefusal(call, it) }
                }
            }
        }
    }

    /** Maps a [Refusal] to its HTTP status; anything else is a 500. */
    suspend fun respondRefusal(call: ApplicationCall, error: Throwable) {
        val refusal = error as? Refusal
        if (refusal == null) {
            call.respond(HttpStatusCode.InternalServerError, ErrorView("internal error"))
            return
        }
        val status = when (refusal.status) {
            400 -> HttpStatusCode.BadRequest
            404 -> HttpStatusCode.NotFound
            409 -> HttpStatusCode.Conflict
            502 -> HttpStatusCode.BadGateway
            else -> HttpStatusCode.UnprocessableEntity
        }
        call.respond(status, ErrorView(refusal.message))
    }

    /** Starts the server (non-blocking) and returns it for lifecycle shutdown. */
    fun start(
        token: String,
        network: String,
        peerId: String,
        api: () -> ConsoleApi,
        host: String = "127.0.0.1",
        port: Int = DEFAULT_PORT,
    ): EmbeddedServer<*, *> =
        embeddedServer(CIO, host = host, port = port) {
            installConsole(this, token, network, peerId, api)
        }.also { it.start(wait = false) }

    private const val JSON = "application/json"

    /** Host without its port, so `127.0.0.1:8787` and `[::1]:8787` both match. */
    private fun hostNameOf(call: ApplicationCall): String {
        val raw = call.request.host().trim()
        return when {
            raw.startsWith("[") -> raw.substringAfter("[").substringBefore("]")
            else -> raw.substringBefore(":")
        }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
}

@Serializable data class ErrorView(val error: String)

@Serializable data class HealthView(val status: String, val network: String, val peerId: String)
