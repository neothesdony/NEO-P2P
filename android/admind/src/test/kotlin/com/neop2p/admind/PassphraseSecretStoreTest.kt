package com.neop2p.admind

import com.neop2p.data.p2p.IdentityBlob
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PassphraseSecretStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val passphrase = "correct horse battery staple".toCharArray()

    private fun blob(peerId: String = "peer-1", nickname: String = "Arbitrator") = IdentityBlob(
        seedPhrase = List(11) { "abandon" } + "about",
        peerId = peerId,
        nostrPubkeyHex = "aa".repeat(32),
        nostrPrivateKeyHex = "bb".repeat(32),
        nickname = nickname,
        lnNodeId = ""
    )

    private fun storeFor(path: Path, pass: CharArray = passphrase) =
        PassphraseSecretStore(path, pass, Argon2Params.TEST)

    @Test
    fun `save then load round-trips the identity blob`() {
        val path = tmp.newFile().toPath()
        val store = storeFor(path)
        val saved = blob()
        store.save(saved)
        assertEquals(saved, store.load())
    }

    @Test
    fun `load returns null when the file does not exist`() {
        assertNull(storeFor(tmp.newFolder().toPath().resolve("absent.secret")).load())
    }

    @Test
    fun `wrong passphrase fails closed without leaking the secret`() {
        val path = tmp.newFile().toPath()
        storeFor(path).save(blob())
        try {
            storeFor(path, "not the passphrase".toCharArray()).load()
            fail("expected SecretStoreException")
        } catch (e: SecretStoreException) {
            assertFalse(e.message!!.contains("correct horse battery staple"))
            assertFalse(e.message!!.contains("abandon"))
        }
    }

    @Test
    fun `a tampered ciphertext byte fails closed`() {
        val path = tmp.newFile().toPath()
        storeFor(path).save(blob())
        val bytes = Files.readAllBytes(path)
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()
        Files.write(path, bytes)
        try {
            storeFor(path).load()
            fail("expected SecretStoreException")
        } catch (e: SecretStoreException) {
            // expected
        }
    }

    @Test
    fun `a file without the NPA1 magic is rejected`() {
        val path = tmp.newFile().toPath()
        Files.write(path, ByteArray(64) { 0x42 })
        try {
            storeFor(path).load()
            fail("expected SecretStoreException")
        } catch (e: SecretStoreException) {
            assertTrue(e.message!!.contains("Not a NEO-P2P secret file"))
        }
    }

    @Test
    fun `save replaces an existing secret and leaves no temp file`() {
        val path = tmp.newFile().toPath()
        val store = storeFor(path)
        store.save(blob(peerId = "peer-1"))
        val second = blob(peerId = "peer-2", nickname = "Second")
        store.save(second)
        assertEquals(second, store.load())
        assertFalse(File("$path.tmp").exists())
    }

    @Test
    fun `clear removes the secret and is idempotent`() {
        val path = tmp.newFile().toPath()
        val store = storeFor(path)
        store.save(blob())
        store.clear()
        assertFalse(Files.exists(path))
        store.clear()
        assertNull(store.load())
    }

    @Test
    fun `argon2 params actually feed the derived key`() {
        val path = tmp.newFile().toPath()
        storeFor(path).save(blob())
        try {
            PassphraseSecretStore(path, passphrase, Argon2Params.DEFAULT).load()
            fail("expected SecretStoreException: different params must derive a different key")
        } catch (e: SecretStoreException) {
            // expected
        }
    }
}
