package com.neop2p.admind

import com.neop2p.admind.web.ConsoleServer
import com.neop2p.data.p2p.IdentityBlob
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ServeCommandTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val nonArbitrator = IdentityBlob(
        seedPhrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
            .split(" "),
        peerId = "peer-test",
        nostrPubkeyHex = "",
        nostrPrivateKeyHex = "",
        nickname = "Arbitrator",
        lnNodeId = "",
    )

    @Test
    fun `a non-arbitrator mnemonic is refused before the data dir is created`() {
        val dir = tmp.newFolder().toPath().resolve("npa-data")
        assertFalse(Files.exists(dir))

        val ex = assertThrows(IllegalStateException::class.java) {
            // --web must never bind a port before the identity is proven.
            runBlocking { ServeCommand.run(dir, nonArbitrator, webPort = 8787) }
        }

        assertTrue(ex.message!!.contains("not the configured arbitrator"))
        // Fail-closed ordering: refuse before creating the data dir or any socket.
        assertFalse(Files.exists(dir))
    }

    @Test
    fun `the console url carries the token in the fragment`() {
        assertEquals("http://127.0.0.1:8787/#token=abc", ConsoleServer.consoleUrl(8787, "abc"))
    }
}
