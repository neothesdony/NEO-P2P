package com.neop2p.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun parsesTagAndHtmlUrl() {
        val json = """
            {
              "tag_name": "v0.1.2",
              "html_url": "https://github.com/neothesdony/NEO-P2P/releases/tag/v0.1.2",
              "name": "0.1.2",
              "body": "notes",
              "draft": false,
              "prerelease": false
            }
        """.trimIndent()
        val release = UpdateChecker.parseLatestRelease(json)
        assertEquals("v0.1.2", release?.tag)
        assertEquals("https://github.com/neothesdony/NEO-P2P/releases/tag/v0.1.2", release?.htmlUrl)
    }

    @Test
    fun ignoresUnknownFields() {
        val json = """{"tag_name":"v1.0.0","html_url":"https://x/y","extra":{"nested":1}}"""
        assertEquals("v1.0.0", UpdateChecker.parseLatestRelease(json)?.tag)
    }

    @Test
    fun missingFieldsReturnNull() {
        assertNull(UpdateChecker.parseLatestRelease("""{"tag_name":"v1.0.0"}"""))
        assertNull(UpdateChecker.parseLatestRelease("""{"html_url":"https://x/y"}"""))
        assertNull(UpdateChecker.parseLatestRelease("""{"tag_name":"","html_url":"https://x/y"}"""))
    }

    @Test
    fun garbageReturnsNull() {
        assertNull(UpdateChecker.parseLatestRelease("not json"))
        assertNull(UpdateChecker.parseLatestRelease(""))
    }

    @Test
    fun endpointIsTheConfiguredRepo() {
        assertEquals(
            "https://api.github.com/repos/neothesdony/NEO-P2P/releases/latest",
            UpdateChecker.RELEASES_API
        )
    }
}
