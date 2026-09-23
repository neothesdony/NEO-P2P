package com.neop2p.data.update

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the latest published release from the GitHub Releases API.
 *
 * Uses the app's single injected [HttpClient] (the HTTP chokepoint — see
 * `.github/scripts/check-http-chokepoint.sh`). `api.github.com` is intentionally
 * NOT in `ExplorerPins`: this is a notify-only version check that never
 * downloads code, so a MITM can at worst lie about a version number.
 *
 * Fail-closed: any non-2xx, timeout, or parse failure returns `null` — the
 * caller must never prompt on a value it could not verify.
 */
@Singleton
class UpdateChecker @Inject constructor(
    private val client: HttpClient
) {

    data class LatestRelease(val tag: String, val htmlUrl: String)

    suspend fun fetchLatest(): LatestRelease? = try {
        val text: String = client.get(RELEASES_API) {
            header(HttpHeaders.Accept, "application/vnd.github+json")
        }.body()
        parseLatestRelease(text)
    } catch (e: Exception) {
        null
    }

    companion object {
        const val RELEASES_API =
            "https://api.github.com/repos/neothesdony/NEO-P2P/releases/latest"

        private val json = Json { ignoreUnknownKeys = true }

        /** Parse a `releases/latest` response body; `null` if tag or URL is missing/blank. */
        fun parseLatestRelease(body: String): LatestRelease? {
            val release = runCatching {
                json.decodeFromString<GithubRelease>(body)
            }.getOrNull() ?: return null
            val tag = release.tagName?.trim().orEmpty()
            val url = release.htmlUrl?.trim().orEmpty()
            if (tag.isEmpty() || url.isEmpty()) return null
            return LatestRelease(tag, url)
        }
    }
}

@Serializable
private data class GithubRelease(
    @SerialName("tag_name") val tagName: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null
)
